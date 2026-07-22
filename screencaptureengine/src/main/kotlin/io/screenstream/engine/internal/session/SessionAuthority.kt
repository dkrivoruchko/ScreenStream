package io.screenstream.engine.internal.session

import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.internal.android.AndroidCaptureFact
import io.screenstream.engine.internal.android.AndroidCaptureFactSink
import io.screenstream.engine.internal.android.CaptureMetricsIngressPort
import io.screenstream.engine.internal.android.CaptureMetricsIngressResult
import io.screenstream.engine.internal.android.CaptureMetricsIngressSummaryFact
import io.screenstream.engine.internal.android.CaptureMetricsSampleIngressFact
import io.screenstream.engine.internal.android.CaptureMetricsTerminalIngressFact
import io.screenstream.engine.internal.android.CaptureMetricsReadinessOutcome
import io.screenstream.engine.internal.android.AndroidProjectionCallbackRegistrationReceipt
import io.screenstream.engine.internal.delivery.DeliveryAuthorityPort
import io.screenstream.engine.internal.delivery.DeliveryEntryRequest
import io.screenstream.engine.internal.delivery.DeliveryFailureNotice
import io.screenstream.engine.internal.delivery.ObservationDiagnosticPayload
import io.screenstream.engine.internal.delivery.ObservationDiagnosticRequest
import io.screenstream.engine.internal.delivery.ObservationDiagnosticSite
import io.screenstream.engine.internal.delivery.ObservationStateSnapshot
import io.screenstream.engine.internal.delivery.ObservationStatsSnapshot
import io.screenstream.engine.internal.session.cleanup.ControlLastShutdownPolicy
import io.screenstream.engine.internal.session.cleanup.SessionCleanupReceipts
import io.screenstream.engine.internal.session.runtime.SessionRuntimeCommandPort
import io.screenstream.engine.internal.session.runtime.SessionRuntimeFactPort
import io.screenstream.engine.internal.session.runtime.SessionRuntimeIdentityPlan
import io.screenstream.engine.internal.session.runtime.SessionRuntimeStartRequest
import io.screenstream.engine.internal.session.transitions.DeliveryTransitions
import io.screenstream.engine.internal.session.transitions.LifecycleTransitions
import io.screenstream.engine.internal.session.transitions.RuntimeStartedDecision
import io.screenstream.engine.internal.session.transitions.StartDecision
import io.screenstream.engine.internal.session.transitions.StartupTopologyDecision
import io.screenstream.engine.internal.session.transitions.TerminalTransitions
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicBoolean
import io.screenstream.engine.internal.settlement.OperationArbitration
import io.screenstream.engine.internal.gl.GlOperationKind
import io.screenstream.engine.internal.gl.GlOperationResult
import io.screenstream.engine.internal.gl.ContextIntegrity
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.session.reconciliation.AuthoritativeInput
import io.screenstream.engine.internal.session.reconciliation.ProvisionalBootstrapInput
import io.screenstream.engine.internal.session.reconciliation.ProvisionalFull
import io.screenstream.engine.internal.session.reconciliation.ReconciliationCurrentTopology
import io.screenstream.engine.internal.session.reconciliation.ReconciliationOwner
import io.screenstream.engine.internal.session.reconciliation.Resolved
import io.screenstream.engine.internal.session.reconciliation.TopologyStamp
import io.screenstream.engine.internal.target.TargetConstructionFailureFact
import io.screenstream.engine.internal.target.TargetConstructionFoldDisposition
import io.screenstream.engine.internal.target.TargetConstructionInstalledFact
import io.screenstream.engine.internal.target.TargetRequestedIdentity
import io.screenstream.engine.internal.android.AndroidTargetPlatformResult
import io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.session.reconciliation.ReconciliationTargetTopologyFacts
import io.screenstream.engine.internal.target.TargetMode
import io.screenstream.engine.internal.android.CaptureMetricsTerminalKind
import io.screenstream.engine.internal.android.CaptureMetricsTerminalPhase
import kotlin.concurrent.withLock

/** The sole Session lifecycle/currentness/publication/terminal commit authority. */
internal class SessionAuthority internal constructor(
    private val runtime: SessionRuntimeCommandPort,
    private val wallClockMillis: () -> Long,
) : SessionRuntimeFactPort, CaptureMetricsIngressPort, AndroidCaptureFactSink, DeliveryAuthorityPort {
    private val sessionGate = ReentrantLock(false)
    private val inbox = SessionFactInbox()
    private var state = SessionState()
    private var diagnosticSequence = 1L
    private val ownerStopRequested = AtomicBoolean(false)

    init {
        runtime.bind(this, this, this, this, ::drainFacts)
    }

    internal fun start(request: SessionRuntimeStartRequest): SessionStartAdmission {
        while (true) {
            val observed = sessionGate.withLock { state }
            val first = reserveIdentities(observed, 50) ?: return SessionStartAdmission.Rejected
            val decision = LifecycleTransitions.acceptStart(
                lifecycle = observed.lifecycle,
                startupIdentity = first,
                desiredRevision = first + 1L,
                geometryGeneration = first + 2L,
                lifecycleEpoch = first + 3L,
                parameters = request.parameters,
            )
            if (decision !is StartDecision.Accepted) return SessionStartAdmission.Rejected
            val nextState = observed.copy(
                lifecycle = decision.lifecycle,
                desired = SessionDesiredParameters(first + 1L, request.parameters),
                currentness = decision.currentness,
                admissions = SessionAdmissions.Closed,
                metricsIngress = SessionMetricsIngressState(observationIdentity = first + 4L),
                startup = SessionStartupState(
                    identities = runtimeIdentityPlan(first),
                    stage = SessionStartupStage.AwaitingRuntime,
                ),
                nextIdentity = first + 50L,
            )
            val committed = SessionStartAdmission.Accepted(
                CommittedTurn(
                    publication = SessionPublicationAction(starting = true),
                    runtimeAction = SessionRuntimeAction.Start(
                        request,
                        first,
                        runtimeIdentityPlan(first),
                    ),
                ),
            )
            val installed = sessionGate.withLock {
                if (state !== observed) false else true.also { state = nextState }
            }
            if (!installed) continue
            execute(committed.turn)
            return committed
        }
    }

    internal fun stop() {
        ownerStopRequested.set(true)
        runtime.signal()
    }

    internal fun snapshot(): SessionState = sessionGate.withLock { state }

    override fun publishRuntimeStarted(fact: SessionRuntimeStartedFact) = offer(fact)
    override fun publishRuntimeStartupFailed(fact: SessionRuntimeStartupFailedFact) = offer(fact)
    override fun publishMetricsReadiness(fact: SessionMetricsReadinessFact) = offer(fact)
    override fun publishControlWakeSchedule(fact: SessionControlWakeScheduleFact) = offer(fact)
    override fun publishControlWakeCancellation(fact: SessionControlWakeCancellationFact) = offer(fact)
    override fun publishProjectionCallbackRegistration(fact: SessionProjectionCallbackRegistrationFact) = offer(fact)
    override fun publishGlConstruction(fact: SessionGlConstructionFact) = offer(fact)
    override fun publishTargetConstructionClaim(fact: SessionTargetConstructionClaimFact) = offer(fact)
    override fun publishTargetConstructionResult(fact: SessionTargetConstructionResultFact) = offer(fact)
    override fun publishTargetListenerClaim(fact: SessionTargetListenerClaimFact) = offer(fact)
    override fun publishTargetListenerApplied(fact: SessionTargetListenerAppliedFact) = offer(fact)
    override fun publishVirtualDisplayClaim(fact: SessionVirtualDisplayClaimFact) = offer(fact)
    override fun publishVirtualDisplayApplied(fact: SessionVirtualDisplayAppliedFact) = offer(fact)
    override fun publishInitialResize(fact: SessionInitialResizeFact) = offer(fact)
    override fun publishStartupTopologyReady(fact: SessionStartupTopologyReadyFact) = offer(fact)
    override fun publishControlException(fact: SessionControlExceptionFact) = offer(fact)
    override fun publishControlDirectFatal(fact: SessionControlDirectFatalFact) = offer(fact)
    override fun publishMetricsCleanupSettled(fact: MetricsCleanupSettledFact) = offer(fact)
    override fun publishAndroidCleanupSettled(fact: AndroidCleanupSettledFact) = offer(fact)
    override fun publishTargetCleanupSettled(fact: TargetCleanupSettledFact) = offer(fact)
    override fun publishGlCleanupSettled(fact: GlCleanupSettledFact) = offer(fact)
    override fun publishJpegCleanupSettled(fact: JpegCleanupSettledFact) = offer(fact)
    override fun publishStorageCleanupSettled(fact: StorageCleanupSettledFact) = offer(fact)
    override fun publishDeliveryCleanupSettled(fact: DeliveryCleanupSettledFact) = offer(fact)
    override fun publishExternalFactsSettled(fact: SessionExternalFactsSettledFact) = offer(fact)
    override fun publishControlResidueSettled(fact: SessionControlResidueSettledFact) {
        inbox.offer(fact)
    }

    override fun publish(fact: AndroidCaptureFact) {
        inbox.offer(fact)
        runtime.signal()
    }

    override fun publishMetricsSummary(fact: CaptureMetricsIngressSummaryFact): CaptureMetricsIngressResult {
        while (true) {
            val observed = sessionGate.withLock { state }
            val plan = planMetricsSummary(observed, fact)
            val installed = sessionGate.withLock {
                if (state !== observed || state.metricsIngress !== observed.metricsIngress) {
                    false
                } else if (plan.candidate != null &&
                    (state.terminalCutoffApplied || state.metricsIngress.observationIdentity != fact.observationIdentity)
                ) {
                    false
                } else {
                    plan.candidate?.let { state = it }
                    true
                }
            }
            if (!installed) continue
            if (plan.result == CaptureMetricsIngressResult.Published) runtime.signal()
            return plan.result
        }
    }

    private fun planMetricsSummary(
        observed: SessionState,
        fact: CaptureMetricsIngressSummaryFact,
    ): MetricsIngressPlan {
        if (observed.terminalCutoffApplied) return MetricsIngressPlan(null, CaptureMetricsIngressResult.Closed)
        val ingress = observed.metricsIngress
        if (ingress.observationIdentity == 0L) {
            return MetricsIngressPlan(null, CaptureMetricsIngressResult.RejectedAdmission)
        }
        if (fact.observationIdentity != ingress.observationIdentity) {
            return MetricsIngressPlan(null, CaptureMetricsIngressResult.RejectedCurrentness)
        }
        if (!isWellFormedMetricsSummary(fact)) {
            return MetricsIngressPlan(null, CaptureMetricsIngressResult.RejectedCurrentness)
        }
        if (fact.lastSequence <= ingress.lastSequence) {
            return MetricsIngressPlan(
                candidate = null,
                result = if (summaryIsAlreadyMerged(ingress, fact)) {
                    CaptureMetricsIngressResult.Duplicate
                } else {
                    CaptureMetricsIngressResult.RejectedCurrentness
                },
            )
        }
        if (!cumulativeMetricsHistoryMatches(ingress, fact)) {
            return MetricsIngressPlan(null, CaptureMetricsIngressResult.RejectedCurrentness)
        }
        val nextIngress = SessionMetricsIngressState(
            observationIdentity = ingress.observationIdentity,
            lastSequence = fact.lastSequence,
            earliestPositive = fact.earliestPositive ?: ingress.earliestPositive,
            firstPostPositiveUnavailable =
                fact.firstPostPositiveUnavailable ?: ingress.firstPostPositiveUnavailable,
            latestSample = newerSample(ingress.latestSample, fact.latestSample),
            firstTerminal = fact.firstTerminal ?: ingress.firstTerminal,
        )
        return MetricsIngressPlan(
            observed.copy(metricsIngress = nextIngress),
            CaptureMetricsIngressResult.Published,
        )
    }

    private fun isWellFormedMetricsSummary(fact: CaptureMetricsIngressSummaryFact): Boolean {
        val earliest = fact.earliestPositive
        val firstLoss = fact.firstPostPositiveUnavailable
        val latest = fact.latestSample
        val terminal = fact.firstTerminal
        if (earliest != null && (!earliest.isAvailable || earliest.sequence > fact.lastSequence)) return false
        if (firstLoss != null && (firstLoss.isAvailable || earliest == null ||
                    firstLoss.sequence <= earliest.sequence || firstLoss.sequence > fact.lastSequence)
        ) return false
        if (latest != null && latest.sequence > fact.lastSequence) return false
        if (terminal != null && terminal.sequence != fact.lastSequence) return false
        return true
    }

    private fun summaryIsAlreadyMerged(
        ingress: SessionMetricsIngressState,
        fact: CaptureMetricsIngressSummaryFact,
    ): Boolean = summarySampleIsRetained(ingress.earliestPositive, fact.earliestPositive) &&
            summarySampleIsRetained(
                ingress.firstPostPositiveUnavailable,
                fact.firstPostPositiveUnavailable,
            ) &&
            summarySampleIsRetained(ingress.latestSample, fact.latestSample) &&
            summaryTerminalIsRetained(ingress.firstTerminal, fact.firstTerminal)

    private fun cumulativeMetricsHistoryMatches(
        ingress: SessionMetricsIngressState,
        fact: CaptureMetricsIngressSummaryFact,
    ): Boolean {
        val latest = fact.latestSample
        return retainsExistingSample(ingress.earliestPositive, fact.earliestPositive) &&
                retainsExistingSample(
                    ingress.firstPostPositiveUnavailable,
                    fact.firstPostPositiveUnavailable,
                ) &&
                retainsExistingTerminal(ingress.firstTerminal, fact.firstTerminal) &&
                (latest == null || sameOptionalMetricsSample(ingress.latestSample, latest) ||
                        latest.sequence > ingress.lastSequence)
    }

    private fun summarySampleIsRetained(
        retained: CaptureMetricsSampleIngressFact?,
        offered: CaptureMetricsSampleIngressFact?,
    ): Boolean = offered == null || sameOptionalMetricsSample(retained, offered)

    private fun summaryTerminalIsRetained(
        retained: CaptureMetricsTerminalIngressFact?,
        offered: CaptureMetricsTerminalIngressFact?,
    ): Boolean = offered == null || sameOptionalMetricsTerminal(retained, offered)

    private fun retainsExistingSample(
        existing: CaptureMetricsSampleIngressFact?,
        cumulative: CaptureMetricsSampleIngressFact?,
    ): Boolean = existing == null || sameOptionalMetricsSample(existing, cumulative)

    private fun retainsExistingTerminal(
        existing: CaptureMetricsTerminalIngressFact?,
        cumulative: CaptureMetricsTerminalIngressFact?,
    ): Boolean = existing == null || sameOptionalMetricsTerminal(existing, cumulative)

    private fun newerSample(
        existing: CaptureMetricsSampleIngressFact?,
        candidate: CaptureMetricsSampleIngressFact?,
    ): CaptureMetricsSampleIngressFact? = when {
        candidate == null -> existing
        existing == null || candidate.sequence > existing.sequence -> candidate
        else -> existing
    }

    private fun sameOptionalMetricsSample(
        previous: CaptureMetricsSampleIngressFact?,
        current: CaptureMetricsSampleIngressFact?,
    ): Boolean = previous == null && current == null ||
            previous != null && current != null && sameMetricsSample(previous, current)

    private fun sameMetricsSample(
        previous: CaptureMetricsSampleIngressFact?,
        current: CaptureMetricsSampleIngressFact,
    ): Boolean {
        if (previous == null || previous.observationIdentity != current.observationIdentity ||
            previous.sequence != current.sequence || previous.sampledAtNanos != current.sampledAtNanos ||
            previous.sourceProvenance != current.sourceProvenance
        ) return false
        val previousAssociation = previous.displayAssociation
        val currentAssociation = current.displayAssociation
        return previous.isAvailable == current.isAvailable &&
                previous.metrics == current.metrics &&
                previousAssociation?.displayId == currentAssociation?.displayId &&
                previousAssociation?.associationIdentity == currentAssociation?.associationIdentity &&
                previousAssociation?.validityEpoch == currentAssociation?.validityEpoch
    }

    private fun sameMetricsTerminal(
        previous: CaptureMetricsTerminalIngressFact,
        current: CaptureMetricsTerminalIngressFact,
    ): Boolean = previous.observationIdentity == current.observationIdentity &&
            previous.sequence == current.sequence &&
            previous.observedAtNanos == current.observedAtNanos &&
            previous.kind == current.kind &&
            previous.phase == current.phase &&
            previous.cause === current.cause

    private fun sameOptionalMetricsTerminal(
        previous: CaptureMetricsTerminalIngressFact?,
        current: CaptureMetricsTerminalIngressFact?,
    ): Boolean = previous == null && current == null ||
            previous != null && current != null && sameMetricsTerminal(previous, current)

    override fun validateAcceptedEntry(request: DeliveryEntryRequest) {
        sessionGate.withLock {
            request.commit(DeliveryTransitions.acceptedHandoffStillAdmitted(state, request))
        }
    }

    override fun failClosed(notice: DeliveryFailureNotice) {
        inbox.offer(notice)
        runtime.signal()
    }

    private fun drainFacts() {
        val batch = inbox.drain()
        val stopRequested = ownerStopRequested.getAndSet(false)
        val terminalWallClockMillis = wallClockMillis()
        while (true) {
            val observed = sessionGate.withLock { state }
            val observedDiagnosticSequence = sessionGate.withLock { diagnosticSequence }
            val reduction = reduce(observed, batch, stopRequested, terminalWallClockMillis, observedDiagnosticSequence)
            val installed = sessionGate.withLock {
                if (state !== observed || diagnosticSequence != observedDiagnosticSequence) false
                else true.also {
                    state = reduction.state
                    diagnosticSequence = reduction.nextDiagnosticSequence
                }
            }
            if (!installed) continue
            reduction.turns.forEach(::execute)
            return
        }
    }

    private fun reduce(
        observed: SessionState,
        batch: SessionFactBatch,
        stopRequested: Boolean,
        terminalWallClockMillis: Long,
        firstDiagnosticSequence: Long,
    ): SessionReduction {
        var nextState = observed
        var nextDiagnosticSequence = firstDiagnosticSequence
        val turns = ArrayList<CommittedTurn>(4)
        batch.capturedContentVisibility?.takeIf {
            !nextState.terminalCutoffApplied && isExactNextAndroidCallback(nextState, it)
        }?.let { fact ->
            val startup = checkNotNull(nextState.startup)
            nextState = nextState.copy(
                startup = copyStartup(
                    startup,
                    startup.stage,
                    capturedContentVisible = fact.isVisible,
                    lastAndroidCallbackSequence = fact.callbackSequence,
                ),
            )
        }
        var contenders = nextState.terminalContenders
            if (nextState.lifecycle is SessionLifecycle.Starting) {
                nextState.metricsIngress.firstPostPositiveUnavailable?.let {
                    contenders = TerminalTransitions.record(
                        contenders,
                        TerminalTransitions.failure(ScreenCaptureProblem.CaptureUnavailable, null),
                    )
                }
                nextState.metricsIngress.firstTerminal?.let { terminal ->
                    when {
                        terminal.kind == CaptureMetricsTerminalKind.Failed ->
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(ScreenCaptureProblem.InternalFailure, terminal.cause),
                            )
                        terminal.phase == CaptureMetricsTerminalPhase.BeforeJointReadiness ->
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(ScreenCaptureProblem.CaptureUnavailable, null),
                            )
                        else -> Unit
                    }
                }
            }
            if (nextState.terminalWinner == null && batch.captureEnded != null &&
                isExactNextAndroidCallback(nextState, batch.captureEnded)
            ) {
                contenders = TerminalTransitions.record(contenders, TerminalTransitions.captureEnded())
            }
            if (nextState.terminalWinner == null && stopRequested) {
                contenders = TerminalTransitions.record(contenders, TerminalTransitions.ownerStop())
            }
            batch.controlFatal?.let {
                if (nextState.terminalWinner == null &&
                    (it.ownership == null && nextState.runtimeOwnership == null ||
                    it.ownership != null && it.ownership === nextState.runtimeOwnership
                    )
                ) {
                    contenders = TerminalTransitions.record(contenders, TerminalTransitions.failure(ScreenCaptureProblem.InternalFailure, it.cause))
                }
            }
            batch.controlException?.let {
                if (nextState.terminalWinner == null &&
                    (it.ownership == null && nextState.runtimeOwnership == null ||
                    it.ownership != null && it.ownership === nextState.runtimeOwnership
                    )
                ) {
                    contenders = TerminalTransitions.record(contenders, TerminalTransitions.failure(ScreenCaptureProblem.InternalFailure, it.cause))
                }
            }
            batch.deliveryFailure?.let {
                if (nextState.terminalWinner == null) {
                    contenders = TerminalTransitions.record(
                        contenders,
                        TerminalTransitions.failure(ScreenCaptureProblem.InternalFailure, it.exactThrowable),
                    )
                }
            }
            nextState = nextState.copy(terminalContenders = contenders)
            batch.runtimeStartupFailed?.let { fact ->
                val adoption = if (nextState.terminalWinner != null) {
                    adoptLateResidue(nextState, fact.residue).also { nextState = it.state }
                } else {
                    contenders = TerminalTransitions.record(
                        contenders,
                        TerminalTransitions.failure(
                            if (fact.raw is OutOfMemoryError) ScreenCaptureProblem.ResourceExhausted
                            else ScreenCaptureProblem.InternalFailure,
                            fact.raw,
                        ),
                    )
                    nextState = nextState.copy(terminalContenders = contenders)
                    SessionStateTurn(nextState, CommittedTurn.None)
                }
                turns += adoption.turn
            }
            if (nextState.terminalWinner == null &&
                (contenders.captureEnded != null || contenders.ownerStop != null || contenders.failure != null)
            ) {
                val terminal = commitSelectedTerminal(
                    nextState,
                    batch.runtimeStartupFailed?.residue,
                    terminalWallClockMillis,
                    nextDiagnosticSequence,
                )
                nextState = terminal.state
                nextDiagnosticSequence = terminal.nextDiagnosticSequence
                turns += terminal.turn
            }
            batch.runtimeStarted?.let { fact ->
                if (nextState.terminalWinner != null) {
                    val adoption = adoptLateResidue(nextState, fact.ownership)
                    nextState = adoption.state
                    turns += adoption.turn
                } else {
                    when (val decision = LifecycleTransitions.acceptRuntimeStarted(nextState.lifecycle, nextState.runtimeOwnership, fact)) {
                        is RuntimeStartedDecision.Accepted -> {
                            val startup = nextState.startup
                            if (startup?.stage == SessionStartupStage.AwaitingRuntime) {
                                nextState = nextState.copy(
                                    runtimeOwnership = decision.ownership,
                                    startup = SessionStartupState(
                                        identities = startup.identities,
                                        stage = SessionStartupStage.AwaitingMetrics,
                                        laneReadiness = decision.laneReadiness,
                                    ),
                                )
                            }
                        }
                        RuntimeStartedDecision.Rejected -> Unit
                    }
                }
            }
            batch.targetListenerClaim?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                val installed = startup?.installedTarget
                if (nextState.terminalWinner == null && startup?.stage == SessionStartupStage.AwaitingTargetListener &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.android === fact.androidOwner &&
                    ownership.target === fact.targetOwner && installed != null &&
                    fact.platformResult.targetFact.targetIdentity === installed.targetIdentity &&
                    fact.occurrence.identity == startup.identities.targetListenerInstallationOperation
                ) {
                    if (fact.arbitration == OperationArbitration.TimelyNormal) {
                        nextState = nextState.copy(
                            startup = copyStartup(startup, SessionStartupStage.AwaitingTargetListenerApplication),
                        )
                        turns += CommittedTurn(
                            runtimeAction = SessionRuntimeAction.ApplyTargetListener(
                                fact.startupIdentity,
                                fact.androidOwner,
                                fact.targetOwner,
                                fact.platformResult,
                            ),
                        )
                    } else {
                        contenders = TerminalTransitions.record(
                            contenders,
                            TerminalTransitions.failure(
                                ScreenCaptureProblem.InternalFailure,
                                fact.occurrence.returnCell.throwable,
                            ),
                        )
                        nextState = nextState.copy(terminalContenders = contenders)
                    }
                }
            }
            batch.targetListenerApplied?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                val installed = startup?.installedTarget
                val applied = fact.result.fact
                if (nextState.terminalWinner == null &&
                    startup?.stage == SessionStartupStage.AwaitingTargetListenerApplication && installed != null &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.android === fact.androidOwner &&
                    ownership.target === fact.targetOwner && applied.targetIdentity === installed.targetIdentity &&
                    applied.operationIdentity == startup.identities.targetListenerInstallationOperation
                ) {
                    nextState = nextState.copy(
                        startup = copyStartup(startup, SessionStartupStage.AwaitingVirtualDisplay),
                    )
                    turns += CommittedTurn(
                        runtimeAction = SessionRuntimeAction.CreateVirtualDisplay(
                            fact.startupIdentity,
                            fact.androidOwner,
                            fact.targetOwner,
                            installed,
                            checkNotNull(startup.captureGeometry),
                            checkNotNull(startup.apiBand),
                        ),
                    )
                }
            }
            batch.virtualDisplayClaim?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                val installed = startup?.installedTarget
                if (nextState.terminalWinner == null && startup?.stage == SessionStartupStage.AwaitingVirtualDisplay &&
                    installed != null && ownership?.startupIdentity == fact.startupIdentity &&
                    ownership.android === fact.androidOwner && ownership.target === fact.targetOwner &&
                    fact.platformResult.targetFact.targetIdentity === installed.targetIdentity &&
                    fact.occurrence.identity == startup.identities.virtualDisplayCreationOperation
                ) {
                    if (fact.arbitration == OperationArbitration.TimelyNormal &&
                        fact.platformResult is AndroidTargetPlatformResult.ProducerAttached
                    ) {
                        nextState = nextState.copy(
                            startup = copyStartup(startup, SessionStartupStage.AwaitingVirtualDisplayApplication),
                        )
                        turns += CommittedTurn(
                            runtimeAction = SessionRuntimeAction.ApplyVirtualDisplay(
                                fact.startupIdentity,
                                fact.androidOwner,
                                fact.targetOwner,
                                fact.platformResult,
                            ),
                        )
                    } else {
                        val raw = fact.occurrence.returnCell.throwable
                        val problem = when {
                            raw is OutOfMemoryError -> ScreenCaptureProblem.ResourceExhausted
                            raw is SecurityException -> ScreenCaptureProblem.CaptureUnavailable
                            fact.arbitration == OperationArbitration.TimelyNormal &&
                                    fact.platformResult is AndroidTargetPlatformResult.ProducerUnavailable ->
                                ScreenCaptureProblem.CaptureUnavailable
                            else -> ScreenCaptureProblem.InternalFailure
                        }
                        contenders = TerminalTransitions.record(
                            contenders,
                            TerminalTransitions.failure(problem, raw),
                        )
                        nextState = nextState.copy(terminalContenders = contenders)
                    }
                }
            }
            batch.virtualDisplayApplied?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                val installed = startup?.installedTarget
                val captureGeometry = startup?.captureGeometry
                val producer = (fact.result as? TargetAndroidPlatformApplicationResult.Producer)?.fact
                    as? TargetProducerEvidence
                if (nextState.terminalWinner == null &&
                    startup?.stage == SessionStartupStage.AwaitingVirtualDisplayApplication && installed != null &&
                    captureGeometry != null &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.android === fact.androidOwner &&
                    ownership.target === fact.targetOwner && producer != null &&
                    producer.targetGeneration == installed.targetIdentity.generation &&
                    producer.operationIdentity == startup.identities.virtualDisplayCreationOperation &&
                    fact.actualLogicalTuple.widthPx == captureGeometry.widthPx &&
                    fact.actualLogicalTuple.heightPx == captureGeometry.heightPx &&
                    fact.actualLogicalTuple.densityDpi == captureGeometry.densityDpi
                ) {
                    nextState = nextState.copy(
                        startup = copyStartup(
                            startup,
                            if (startup.apiBand == AndroidCaptureApiBand.Api34To37) {
                                SessionStartupStage.AwaitingInitialResize
                            } else {
                                SessionStartupStage.AwaitingRenderTarget
                            },
                        ),
                    )
                }
            }
            batch.initialResize?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                val resize = fact.resize
                val exactIdentity = nextState.terminalWinner == null &&
                        startup?.stage == SessionStartupStage.AwaitingInitialResize &&
                        startup.apiBand == AndroidCaptureApiBand.Api34To37 &&
                        ownership?.startupIdentity == fact.startupIdentity && ownership.android === fact.androidOwner
                val exactCallback = resize == null || startup != null && ownership != null &&
                        resize.callbackSequence > startup.lastAndroidCallbackSequence &&
                        exactAndroidCallbackProvenance(startup, ownership.android, resize.provenance)
                val exact = exactIdentity && exactCallback && resize != null && fact.timely &&
                        fact.deadlineStartNanos >= 0L &&
                        resize.sampleNanos in fact.deadlineStartNanos..<fact.deadlineNanos &&
                        fact.arbitrationNanos >= resize.sampleNanos
                if (exact) {
                    val geometry = CaptureGeometry.create(
                        checkNotNull(resize).widthPx,
                        resize.heightPx,
                        checkNotNull(startup.captureGeometry).densityDpi,
                    )
                    val installed = checkNotNull(startup.installedTarget)
                    val currentness = nextState.currentness
                    val calculation = ReconciliationOwner.calculate(
                        AuthoritativeInput(
                            TopologyStamp(
                                currentness.desiredRevision,
                                currentness.geometryGeneration,
                                currentness.lifecycleEpoch,
                            ),
                            startup.identities.reconciliationOccurrence,
                            AndroidCaptureApiBand.Api34To37,
                            geometry,
                            checkNotNull(nextState.desired).parameters,
                            ReconciliationCurrentTopology(
                                ReconciliationTargetTopologyFacts(
                                    installed.plan,
                                    installed.plan.targetWidthPx,
                                    installed.plan.targetHeightPx,
                                    true,
                                ),
                                null,
                                null,
                                null,
                            ),
                            checkNotNull(startup.glCapabilities),
                        ),
                    )
                    when (calculation) {
                        is Resolved -> {
                            val sameTarget = calculation.targetPlan.mode == installed.plan.mode &&
                                    calculation.targetPlan.targetWidthPx == installed.plan.targetWidthPx &&
                                    calculation.targetPlan.targetHeightPx == installed.plan.targetHeightPx
                            nextState = nextState.copy(
                                startup = copyStartup(
                                    startup,
                                    if (sameTarget) SessionStartupStage.AwaitingRenderTarget
                                    else SessionStartupStage.AwaitingTargetReconfiguration,
                                    captureGeometry = geometry,
                                    resolvedTopology = calculation,
                                    lastAndroidCallbackSequence = resize.callbackSequence,
                                ),
                            )
                        }
                        is io.screenstream.engine.internal.session.reconciliation.CapacityDenied -> {
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(ScreenCaptureProblem.ResourceExhausted, null),
                            )
                            nextState = nextState.copy(terminalContenders = contenders)
                        }
                        is io.screenstream.engine.internal.session.reconciliation.InvalidRequest -> {
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(ScreenCaptureProblem.InvalidRequest, null),
                            )
                            nextState = nextState.copy(terminalContenders = contenders)
                        }
                        else -> {
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(ScreenCaptureProblem.InternalFailure, fact.cause),
                            )
                            nextState = nextState.copy(terminalContenders = contenders)
                        }
                    }
                } else if (exactIdentity && exactCallback) {
                    contenders = TerminalTransitions.record(
                        contenders,
                        TerminalTransitions.failure(ScreenCaptureProblem.CaptureUnavailable, fact.cause),
                    )
                    nextState = nextState.copy(terminalContenders = contenders)
                }
            }
            batch.metricsReadiness?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (nextState.terminalWinner == null && startup?.stage == SessionStartupStage.AwaitingMetrics &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.metrics === fact.owner
                ) {
                    val mechanical = fact.mechanical
                    val earliest = nextState.metricsIngress.earliestPositive
                    val validTimely = mechanical.outcome == CaptureMetricsReadinessOutcome.Timely &&
                            fact.timelyReceipt != null && earliest != null && earliest.isAvailable &&
                            mechanical.ingressSequence == earliest.sequence &&
                            mechanical.sampleNanos == earliest.sampledAtNanos &&
                            mechanical.metrics == earliest.metrics
                    if (validTimely) {
                        val metrics = checkNotNull(mechanical.metrics)
                        val geometry = CaptureGeometry.create(metrics.widthPx, metrics.heightPx, metrics.densityDpi)
                        nextState = nextState.copy(
                            startup = SessionStartupState(
                                identities = startup.identities,
                                stage = SessionStartupStage.AwaitingProjectionCallbackRegistration,
                                laneReadiness = startup.laneReadiness,
                                metricsReadiness = fact.timelyReceipt,
                                captureGeometry = geometry,
                            ),
                        )
                        turns += CommittedTurn(
                            runtimeAction = SessionRuntimeAction.RegisterProjectionCallback(fact.startupIdentity),
                        )
                    } else {
                        val problem = when (mechanical.outcome) {
                            CaptureMetricsReadinessOutcome.Expired,
                            CaptureMetricsReadinessOutcome.ProviderCompletedBeforeReadiness,
                            CaptureMetricsReadinessOutcome.AvailabilityLostBeforeActive,
                                -> ScreenCaptureProblem.CaptureUnavailable

                            else -> ScreenCaptureProblem.InternalFailure
                        }
                        contenders = TerminalTransitions.record(
                            contenders,
                            TerminalTransitions.failure(problem, mechanical.cause),
                        )
                        nextState = nextState.copy(terminalContenders = contenders)
                    }
                }
            }
            batch.controlWakeSchedule?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (startup != null &&
                    (nextState.terminalWinner == null || nextState.cleanupTransfer != null) &&
                    ownership === fact.ownership && ownership.startupIdentity == fact.startupIdentity
                ) {
                    turns += CommittedTurn(runtimeAction = SessionRuntimeAction.ScheduleControlWake(fact))
                }
            }
            batch.controlWakeCancellation?.let { fact ->
                val ownership = nextState.runtimeOwnership
                if ((nextState.terminalWinner == null || nextState.cleanupTransfer != null) &&
                    ownership === fact.ownership && ownership.startupIdentity == fact.startupIdentity
                ) {
                    turns += CommittedTurn(runtimeAction = SessionRuntimeAction.CancelControlWake(fact))
                }
            }
            batch.projectionCallbackRegistration?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (nextState.terminalWinner == null &&
                    startup?.stage == SessionStartupStage.AwaitingProjectionCallbackRegistration &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.android === fact.owner
                ) {
                    val success = fact.arbitration == OperationArbitration.TimelyNormal &&
                            fact.occurrence.identity == startup.identities.androidCallbackRegistrationOperation &&
                            ownership.android.apiBand != AndroidCaptureApiBand.Unsupported &&
                            fact.occurrence.returnCell.evidence.receipt === AndroidProjectionCallbackRegistrationReceipt
                    if (success) {
                        nextState = nextState.copy(
                            startup = SessionStartupState(
                                identities = startup.identities,
                                stage = SessionStartupStage.AwaitingGlSession,
                                laneReadiness = startup.laneReadiness,
                                metricsReadiness = startup.metricsReadiness,
                                captureGeometry = startup.captureGeometry,
                                capturedContentVisible = startup.capturedContentVisible,
                            ),
                        )
                        turns += CommittedTurn(
                            runtimeAction = SessionRuntimeAction.ConstructGlSession(fact.startupIdentity),
                        )
                    } else {
                        contenders = TerminalTransitions.record(
                            contenders,
                            TerminalTransitions.failure(
                                ScreenCaptureProblem.InternalFailure,
                                fact.occurrence.returnCell.throwable,
                            ),
                        )
                        nextState = nextState.copy(terminalContenders = contenders)
                    }
                }
            }
            batch.glConstruction?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (nextState.terminalWinner == null && startup?.stage == SessionStartupStage.AwaitingGlSession &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.gl === fact.owner
                ) {
                    val facts = fact.facts
                    val success = facts.operationIdentity == startup.identities.glSessionConstructionOperation &&
                            facts.operationKind == GlOperationKind.SessionConstruction && facts.timely &&
                            facts.result == GlOperationResult.Success && facts.receipt != null &&
                            facts.contextIntegrity == ContextIntegrity.Intact && fact.capabilities != null
                    if (success) {
                        val geometry = checkNotNull(startup.captureGeometry)
                        val currentness = nextState.currentness
                        val stamp = TopologyStamp(
                            currentness.desiredRevision,
                            currentness.geometryGeneration,
                            currentness.lifecycleEpoch,
                        )
                        val calculation = if (fact.apiBand == AndroidCaptureApiBand.Api34To37) {
                            ReconciliationOwner.calculate(
                                ProvisionalBootstrapInput(
                                    stamp,
                                    startup.identities.reconciliationOccurrence,
                                    fact.apiBand,
                                    geometry.widthPx,
                                    geometry.heightPx,
                                    geometry.densityDpi,
                                    checkNotNull(fact.capabilities),
                                ),
                            )
                        } else {
                            ReconciliationOwner.calculate(
                                AuthoritativeInput(
                                    stamp,
                                    startup.identities.reconciliationOccurrence,
                                    fact.apiBand,
                                    geometry,
                                    checkNotNull(nextState.desired).parameters,
                                    ReconciliationCurrentTopology(null, null, null, null),
                                    checkNotNull(fact.capabilities),
                                ),
                            )
                        }
                        val targetPlan = when (calculation) {
                            is ProvisionalFull -> calculation.targetPlan
                            is Resolved -> calculation.targetPlan
                            else -> null
                        }
                        if (targetPlan != null) {
                            val requestedIdentity = TargetRequestedIdentity(
                                startupIdentity = fact.startupIdentity,
                                desiredRevision = currentness.desiredRevision,
                                geometryGeneration = currentness.geometryGeneration,
                                lifecycleEpoch = currentness.lifecycleEpoch,
                                reconciliationIdentity = startup.identities.reconciliationOccurrence,
                            )
                            nextState = nextState.copy(
                                startup = SessionStartupState(
                                    identities = startup.identities,
                                    stage = SessionStartupStage.AwaitingTargetConstruction,
                                    laneReadiness = startup.laneReadiness,
                                    metricsReadiness = startup.metricsReadiness,
                                    captureGeometry = geometry,
                                    capturedContentVisible = startup.capturedContentVisible,
                                    apiBand = fact.apiBand,
                                    glCapabilities = fact.capabilities,
                                    targetPlan = targetPlan,
                                    targetRequestedIdentity = requestedIdentity,
                                    resolvedTopology = calculation as? Resolved,
                                ),
                            )
                            turns += CommittedTurn(
                                runtimeAction = SessionRuntimeAction.PrepareTarget(
                                    fact.startupIdentity,
                                    requestedIdentity,
                                    targetPlan,
                                ),
                            )
                        } else {
                            val problem = if (calculation is io.screenstream.engine.internal.session.reconciliation.CapacityDenied) {
                                ScreenCaptureProblem.ResourceExhausted
                            } else if (calculation is io.screenstream.engine.internal.session.reconciliation.InvalidRequest) {
                                ScreenCaptureProblem.InvalidRequest
                            } else {
                                ScreenCaptureProblem.InternalFailure
                            }
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(problem, null),
                            )
                            nextState = nextState.copy(terminalContenders = contenders)
                        }
                    } else {
                        val problem = if (facts.result == GlOperationResult.ResourceExhausted) {
                            ScreenCaptureProblem.ResourceExhausted
                        } else {
                            ScreenCaptureProblem.InternalFailure
                        }
                        contenders = TerminalTransitions.record(
                            contenders,
                            TerminalTransitions.failure(problem, facts.throwable),
                        )
                        nextState = nextState.copy(terminalContenders = contenders)
                    }
                }
            }
            batch.targetConstructionClaim?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (nextState.terminalWinner == null &&
                    startup?.stage == SessionStartupStage.AwaitingTargetConstruction &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.target === fact.owner &&
                    startup.targetRequestedIdentity === fact.requestedIdentity && startup.targetPlan === fact.plan &&
                    fact.token.constructionOperationIdentity == startup.identities.targetConstructionOperation
                ) {
                    nextState = nextState.copy(
                        startup = copyStartup(startup, SessionStartupStage.AwaitingTargetInstallation),
                    )
                    turns += CommittedTurn(
                        runtimeAction = SessionRuntimeAction.ApplyTargetConstructionFold(
                            fact.startupIdentity,
                            fact.owner,
                            fact.requestedIdentity,
                            fact.plan,
                            fact.token,
                            if (fact.glResult == GlOperationResult.Success &&
                                fact.contextIntegrity == ContextIntegrity.Intact
                            ) {
                                TargetConstructionFoldDisposition.Install
                            } else {
                                TargetConstructionFoldDisposition.CleanupFailure
                            },
                        ),
                    )
                }
            }
            batch.targetConstructionResult?.let { fact ->
                val startup = nextState.startup
                val ownership = nextState.runtimeOwnership
                if (nextState.terminalWinner == null &&
                    startup?.stage == SessionStartupStage.AwaitingTargetInstallation &&
                    ownership?.startupIdentity == fact.startupIdentity && ownership.target === fact.owner
                ) {
                    when (val result = fact.result) {
                        is TargetConstructionInstalledFact -> {
                            if (result.requestedIdentity === startup.targetRequestedIdentity &&
                                result.plan === startup.targetPlan &&
                                result.constructionOperationIdentity == startup.identities.targetConstructionOperation
                            ) {
                                nextState = nextState.copy(
                                    startup = copyStartup(
                                        startup,
                                        SessionStartupStage.AwaitingTargetListener,
                                        installedTarget = result,
                                    ),
                                )
                                turns += CommittedTurn(
                                    runtimeAction = SessionRuntimeAction.InstallTargetListener(
                                        fact.startupIdentity,
                                        fact.owner,
                                        result,
                                    ),
                                )
                            }
                        }
                        is TargetConstructionFailureFact -> {
                            contenders = TerminalTransitions.record(
                                contenders,
                                TerminalTransitions.failure(
                                    if (fact.glResult == GlOperationResult.ResourceExhausted) {
                                        ScreenCaptureProblem.ResourceExhausted
                                    } else {
                                        ScreenCaptureProblem.InternalFailure
                                    },
                                    result.failure,
                                ),
                            )
                            nextState = nextState.copy(terminalContenders = contenders)
                        }
                    }
                }
            }
            if (nextState.terminalWinner == null &&
                (contenders.captureEnded != null || contenders.ownerStop != null || contenders.failure != null)
            ) {
                val terminal = commitSelectedTerminal(
                    nextState,
                    terminalWallClockMillis = terminalWallClockMillis,
                    diagnosticSequence = nextDiagnosticSequence,
                )
                nextState = terminal.state
                nextDiagnosticSequence = terminal.nextDiagnosticSequence
                turns += terminal.turn
            }
            batch.topologyReady?.let { fact ->
                val startup = nextState.startup
                if (nextState.terminalCutoffApplied || startup?.stage != SessionStartupStage.Ready ||
                    startup.committedTopologyReady !== fact ||
                    nextState.runtimeOwnership !== fact.ownership || nextState.currentness != fact.currentness
                ) {
                    return@let
                }
                when (val decision = LifecycleTransitions.acceptStartupTopology(nextState.lifecycle, nextState.runtimeOwnership, fact)) {
                    is StartupTopologyDecision.Accepted -> {
                        nextState = nextState.copy(
                            lifecycle = decision.lifecycle,
                            currentness = decision.currentness,
                            topology = SessionTopology(
                                fact.topologyIdentity,
                                fact.startupIdentity,
                                fact.ownership,
                                fact.effectiveParameters,
                                fact.captureGeometry,
                                fact.topologyEvidence,
                            ),
                            admissions = SessionAdmissions.Open,
                        )
                        turns += CommittedTurn(
                            publication = SessionPublicationAction(
                                active = ObservationStateSnapshot.Active(
                                    fact.effectiveParameters,
                                    fact.capturedContentVisible,
                                ),
                            ),
                        )
                    }
                    StartupTopologyDecision.Rejected -> Unit
                }
            }
            val cleanup = consumeCleanupFacts(nextState, batch)
            nextState = cleanup.state
            cleanup.turn?.let(turns::add)
        return SessionReduction(nextState, nextDiagnosticSequence, turns)
    }

    private fun consumeCleanupFacts(current: SessionState, batch: SessionFactBatch): SessionStateTurn {
        val transfer = current.cleanupTransfer ?: return SessionStateTurn(current, CommittedTurn.None)
        var receipts = current.cleanupReceipts ?: SessionCleanupReceipts(null, null, null, null, null, null, null, null, null, null)
        batch.metricsCleanup?.takeIf { it.receipt.owner === transfer.metrics?.owner }?.let {
            receipts = receipts.copy(metrics = it.receipt)
        }
        batch.androidCleanup?.takeIf { it.receipt.owner === transfer.android?.owner }?.let {
            receipts = receipts.copy(android = it.receipt)
        }
        batch.targetCleanup?.takeIf { it.receipt.owner === transfer.target?.owner }?.let {
            receipts = receipts.copy(target = it.receipt)
        }
        batch.glCleanup?.takeIf { it.receipt.owner === transfer.gl?.owner }?.let {
            receipts = receipts.copy(gl = it.receipt)
        }
        batch.jpegCleanup?.takeIf { it.receipt.owner === transfer.jpeg?.owner }?.let {
            receipts = receipts.copy(jpeg = it.receipt)
        }
        batch.storageCleanup?.takeIf { it.receipt.owner === transfer.storage?.owner }?.let {
            receipts = receipts.copy(storage = it.receipt)
        }
        batch.deliveryCleanup?.takeIf { it.receipt.owner === transfer.delivery?.owner }?.let {
            receipts = receipts.copy(delivery = it.receipt)
        }
        batch.externalFacts?.takeIf { it.receipt.dependencyToken === transfer.dependencyToken }?.let {
            receipts = receipts.copy(externalFacts = it.receipt)
        }
        batch.controlResidue?.takeIf {
            it.proof.transfer === transfer && it.proof.controlOwner === transfer.control.owner &&
                    it.proof.cutoff === transfer.dependencyToken
        }?.let {
            receipts = receipts.copy(controlResidue = it.proof)
        }
        var nextState = current.copy(cleanupReceipts = receipts)
        return if (!nextState.controlShutdownRequested &&
            ControlLastShutdownPolicy.mayRequestControlShutdown(transfer, receipts)
        ) {
            nextState = nextState.copy(
                cleanupReceipts = receipts,
                controlShutdownRequested = true,
            )
            SessionStateTurn(
                nextState,
                CommittedTurn(runtimeAction = SessionRuntimeAction.ShutdownControl(checkNotNull(receipts.controlResidue))),
            )
        } else {
            SessionStateTurn(nextState, CommittedTurn.None)
        }
    }

    private fun commitSelectedTerminal(
        current: SessionState,
        startupResidue: io.screenstream.engine.internal.session.runtime.SessionRuntimeResidue? = null,
        terminalWallClockMillis: Long,
        diagnosticSequence: Long,
    ): TerminalReduction {
        if (current.terminalWinner != null) return TerminalReduction(current, diagnosticSequence, CommittedTurn.None)
        val contenders = current.terminalContenders
        val winner = TerminalTransitions.chooseWinner(contenders, current.lifecycle, current.desired?.parameters)
            ?: return TerminalReduction(current, diagnosticSequence, CommittedTurn.None)
        val transfer = when {
            current.runtimeOwnership != null -> TerminalTransitions.transferRuntime(checkNotNull(current.runtimeOwnership))
            startupResidue?.control != null -> TerminalTransitions.transferResidue(startupResidue)
            else -> null
        }
        val nextState = current.copy(
            lifecycle = SessionLifecycle.Terminal(winner),
            admissions = SessionAdmissions.Closed,
            terminalContenders = contenders,
            terminalWinner = winner,
            cleanupTransfer = transfer,
            cleanupReceipts = transfer?.let { SessionCleanupReceipts(null, null, null, null, null, null, null, null, null, null) },
            terminalCutoffApplied = true,
        )
        val terminalState = when (winner.kind) {
            SessionTerminalKind.CaptureEnded,
            SessionTerminalKind.OwnerStop,
                -> SessionPublicationAction(
                finalStats = ObservationStatsSnapshot.Zero,
                terminalDiagnostic = terminalDiagnostic(winner, terminalWallClockMillis, diagnosticSequence),
                stopped = ObservationStateSnapshot.Stopped(
                    checkNotNull(winner.stopReason), winner.requestedParameters, winner.lastEffectiveParameters,
                ),
            )
            SessionTerminalKind.Failed -> SessionPublicationAction(
                finalStats = ObservationStatsSnapshot.Zero,
                terminalDiagnostic = terminalDiagnostic(winner, terminalWallClockMillis, diagnosticSequence),
                failed = ObservationStateSnapshot.Failed(
                    checkNotNull(winner.problem), winner.requestedParameters, winner.lastEffectiveParameters,
                ),
            )
        }
        return TerminalReduction(nextState, diagnosticSequence + 1L, CommittedTurn(
            publication = terminalState,
            cleanupTransfer = transfer,
            runtimeAction = transfer?.let { SessionRuntimeAction.BeginCleanup(it) },
        ))
    }

    private fun adoptLateResidue(
        current: SessionState,
        residue: io.screenstream.engine.internal.session.runtime.SessionRuntimeResidue?,
    ): SessionStateTurn {
        if (residue?.control == null || current.cleanupTransfer != null) return SessionStateTurn(current, CommittedTurn.None)
        val transfer = TerminalTransitions.transferResidue(residue)
        val nextState = current.copy(
            cleanupTransfer = transfer,
            cleanupReceipts = SessionCleanupReceipts(null, null, null, null, null, null, null, null, null, null),
        )
        return SessionStateTurn(nextState, CommittedTurn(
            cleanupTransfer = transfer,
            runtimeAction = SessionRuntimeAction.BeginCleanup(transfer),
        ))
    }

    private fun terminalDiagnostic(
        winner: SessionTerminalWinner,
        timestampEpochMillis: Long,
        sequence: Long,
    ): ObservationDiagnosticRequest {
        return ObservationDiagnosticRequest(
            sequence = sequence,
            timestampEpochMillis = timestampEpochMillis,
            source = "Session",
            label = "SessionTerminal",
            site = ObservationDiagnosticSite.TerminalWinner,
            payload = ObservationDiagnosticPayload.Terminal(
                outcome = winner.stopReason?.name ?: winner.problem?.name ?: winner.kind.name,
                targetMode = null,
                jpegMode = null,
            ),
            cause = winner.cause,
        )
    }

    private fun execute(turn: CommittedTurn) {
        turn.publication?.let { publication ->
            publication.starting.takeIf { it }?.let { runtime.observationOwner.assignState(ObservationStateSnapshot.Starting) }
            publication.active?.let(runtime.observationOwner::assignState)
            publication.reconfiguring?.let(runtime.observationOwner::assignState)
            publication.suspended?.let(runtime.observationOwner::assignState)
            publication.finalStats?.let(runtime.observationOwner::assignStats)
            publication.terminalDiagnostic?.let(runtime.observationOwner::tryEmitDiagnostic)
            publication.stopped?.let(runtime.observationOwner::assignState)
            publication.failed?.let(runtime.observationOwner::assignState)
        }
        when (val action = turn.runtimeAction) {
            is SessionRuntimeAction.Start -> runtime.start(action.request, action.startupIdentity, action.identities)
            is SessionRuntimeAction.BeginCleanup -> runtime.beginCleanup(action.transfer)
            is SessionRuntimeAction.RegisterProjectionCallback -> runtime.registerProjectionCallback(action.startupIdentity)
            is SessionRuntimeAction.ScheduleControlWake -> runtime.scheduleControlWake(action.fact)
            is SessionRuntimeAction.CancelControlWake -> runtime.cancelControlWake(action.fact)
            is SessionRuntimeAction.ConstructGlSession -> runtime.constructGlSession(action.startupIdentity)
            is SessionRuntimeAction.PrepareTarget -> runtime.prepareTarget(
                action.startupIdentity,
                action.requestedIdentity,
                action.plan,
            )
            is SessionRuntimeAction.ApplyTargetConstructionFold -> runtime.applyTargetConstructionFold(action)
            is SessionRuntimeAction.InstallTargetListener -> runtime.installTargetListener(action)
            is SessionRuntimeAction.ApplyTargetListener -> runtime.applyTargetListener(action)
            is SessionRuntimeAction.CreateVirtualDisplay -> runtime.createVirtualDisplay(action)
            is SessionRuntimeAction.ApplyVirtualDisplay -> runtime.applyVirtualDisplay(action)
            is SessionRuntimeAction.ShutdownControl -> runtime.requestControlShutdown(action.proof)
            null -> Unit
        }
    }

    private fun reserveIdentities(observed: SessionState, count: Int): Long? {
        val first = observed.nextIdentity
        if (count <= 0 || first <= 0L || first > Long.MAX_VALUE - count) return null
        return first
    }

    private fun runtimeIdentityPlan(first: Long): SessionRuntimeIdentityPlan = SessionRuntimeIdentityPlan(
        metricsAttachment = first + 4L,
        metricsReadinessDeadline = first + 5L,
        metricsReadinessWake = first + 6L,
        metricsClose = first + 7L,
        androidProjectionEpoch = first + 8L,
        androidCallback = first + 9L,
        androidStop = first + 10L,
        androidCallbackRegistrationOperation = first + 11L,
        androidCallbackRegistrationDeadline = first + 12L,
        androidCallbackRegistrationWake = first + 13L,
        glSessionConstructionOperation = first + 14L,
        glSessionConstructionDeadline = first + 15L,
        glSessionConstructionWake = first + 16L,
        glPartialCleanupOperation = first + 17L,
        glPartialCleanupDeadline = first + 18L,
        glPartialCleanupWake = first + 19L,
        reconciliationOccurrence = first + 20L,
        targetConstructionOperation = first + 21L,
        targetConstructionDeadline = first + 22L,
        targetConstructionWake = first + 23L,
        targetListenerInstallationOperation = first + 24L,
        targetListenerInstallationDeadline = first + 25L,
        targetListenerInstallationWake = first + 26L,
        targetSurfaceReleaseOperation = first + 27L,
        targetSurfaceReleaseDeadline = first + 28L,
        targetSurfaceReleaseWake = first + 29L,
        targetDestructionOperation = first + 30L,
        targetDestructionDeadline = first + 31L,
        targetDestructionWake = first + 32L,
        targetNamespaceDestructionOperation = first + 33L,
        targetNamespaceDestructionDeadline = first + 34L,
        targetNamespaceDestructionWake = first + 35L,
        virtualDisplayCreationOperation = first + 36L,
        virtualDisplayCreationDeadline = first + 37L,
        virtualDisplayCreationWake = first + 38L,
        initialResizeDeadline = first + 39L,
        initialResizeWake = first + 40L,
        androidCallbackUnregistration = first + 41L,
        targetListenerRemoval = first + 42L,
        virtualDisplayRelease = first + 43L,
        glProgramDestructionOperation = first + 44L,
        glProgramDestructionDeadline = first + 45L,
        glProgramDestructionWake = first + 46L,
        glSessionDestructionOperation = first + 47L,
        glSessionDestructionDeadline = first + 48L,
        glSessionDestructionWake = first + 49L,
    )

    private fun copyStartup(
        startup: SessionStartupState,
        stage: SessionStartupStage,
        installedTarget: TargetConstructionInstalledFact? = startup.installedTarget,
        captureGeometry: CaptureGeometry? = startup.captureGeometry,
        capturedContentVisible: Boolean? = startup.capturedContentVisible,
        resolvedTopology: Resolved? = startup.resolvedTopology,
        lastAndroidCallbackSequence: Long = startup.lastAndroidCallbackSequence,
        committedTopologyReady: SessionStartupTopologyReadyFact? = startup.committedTopologyReady,
    ): SessionStartupState = SessionStartupState(
        identities = startup.identities,
        stage = stage,
        laneReadiness = startup.laneReadiness,
        metricsReadiness = startup.metricsReadiness,
        captureGeometry = captureGeometry,
        capturedContentVisible = capturedContentVisible,
        apiBand = startup.apiBand,
        glCapabilities = startup.glCapabilities,
        targetPlan = startup.targetPlan,
        targetRequestedIdentity = startup.targetRequestedIdentity,
        installedTarget = installedTarget,
        resolvedTopology = resolvedTopology,
        lastAndroidCallbackSequence = lastAndroidCallbackSequence,
        committedTopologyReady = committedTopologyReady,
    )

    private fun exactAndroidCallbackProvenance(
        startup: SessionStartupState,
        owner: io.screenstream.engine.internal.session.runtime.AndroidRuntimeOwnership,
        provenance: io.screenstream.engine.internal.android.AndroidCallbackProvenance,
    ): Boolean = owner.apiBand != AndroidCaptureApiBand.Unsupported &&
            (startup.apiBand == null || startup.apiBand == owner.apiBand) &&
            owner.matchesCallbackProvenance(provenance) &&
            provenance.projectionOwnerEpoch == startup.identities.androidProjectionEpoch &&
            provenance.callbackRegistrationIdentity == startup.identities.androidCallbackRegistrationOperation &&
            provenance.callbackIdentity == startup.identities.androidCallback

    private fun isExactNextAndroidCallback(
        state: SessionState,
        fact: AndroidCaptureFact,
    ): Boolean {
        val startup = state.startup ?: return false
        val owner = state.runtimeOwnership?.android ?: return false
        return fact.callbackSequence > startup.lastAndroidCallbackSequence &&
                exactAndroidCallbackProvenance(startup, owner, fact.provenance)
    }

    private fun offer(fact: SessionRuntimeStartedFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionRuntimeStartupFailedFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionMetricsReadinessFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionControlWakeScheduleFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionControlWakeCancellationFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionProjectionCallbackRegistrationFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionGlConstructionFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionTargetConstructionClaimFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionTargetConstructionResultFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionTargetListenerClaimFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionTargetListenerAppliedFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionVirtualDisplayClaimFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionVirtualDisplayAppliedFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionInitialResizeFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionStartupTopologyReadyFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionControlExceptionFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionControlDirectFatalFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: MetricsCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: AndroidCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: TargetCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: GlCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: JpegCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: StorageCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: DeliveryCleanupSettledFact) { inbox.offer(fact); runtime.signal() }
    private fun offer(fact: SessionExternalFactsSettledFact) { inbox.offer(fact); runtime.signal() }
}

internal sealed interface SessionStartAdmission {
    internal class Accepted internal constructor(internal val turn: CommittedTurn) : SessionStartAdmission
    internal object Rejected : SessionStartAdmission
}

private class SessionReduction(
    val state: SessionState,
    val nextDiagnosticSequence: Long,
    val turns: List<CommittedTurn>,
)

private class MetricsIngressPlan(
    val candidate: SessionState?,
    val result: CaptureMetricsIngressResult,
)

private class SessionStateTurn(val state: SessionState, val turn: CommittedTurn)

private class TerminalReduction(
    val state: SessionState,
    val nextDiagnosticSequence: Long,
    val turn: CommittedTurn,
)

private fun SessionCleanupReceipts.copy(
    metrics: io.screenstream.engine.internal.session.runtime.MetricsTerminationReceipt? = this.metrics,
    android: io.screenstream.engine.internal.session.runtime.AndroidTerminationReceipt? = this.android,
    target: io.screenstream.engine.internal.session.runtime.TargetRetirementReceipt? = this.target,
    gl: io.screenstream.engine.internal.session.runtime.GlTerminationReceipt? = this.gl,
    jpeg: io.screenstream.engine.internal.session.runtime.JpegTerminationReceipt? = this.jpeg,
    storage: io.screenstream.engine.internal.session.runtime.StorageRetirementReceipt? = this.storage,
    delivery: io.screenstream.engine.internal.session.runtime.DeliveryTerminationReceipt? = this.delivery,
    externalFacts: io.screenstream.engine.internal.session.cleanup.ExternalFactsSettledReceipt? = this.externalFacts,
    controlResidue: io.screenstream.engine.internal.session.cleanup.SessionControlResidueSettledProof? = this.controlResidue,
    control: io.screenstream.engine.internal.session.runtime.ControlTerminationReceipt? = this.control,
): SessionCleanupReceipts = SessionCleanupReceipts(
    metrics, android, target, gl, jpeg, storage, delivery, externalFacts, controlResidue, control,
)
