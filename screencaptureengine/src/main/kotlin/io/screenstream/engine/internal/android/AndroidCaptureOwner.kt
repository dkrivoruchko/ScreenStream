package io.screenstream.engine.internal.android

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import io.screenstream.engine.internal.AndroidLaneQuitAction
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationTerminalArbitration
import io.screenstream.engine.internal.settlement.SettlementSignal
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetNoProducerEvidence
import io.screenstream.engine.internal.target.TargetListenerInstallationBindingCommittedFact
import io.screenstream.engine.internal.target.TargetListenerInstallationRequestClaim
import io.screenstream.engine.internal.target.TargetPortUseOutcome
import io.screenstream.engine.internal.target.TargetPorts
import io.screenstream.engine.internal.target.TargetProducerApplicationFact
import io.screenstream.engine.internal.target.TargetProducerDetachReceipt
import io.screenstream.engine.internal.target.TargetProducerEvidence
import io.screenstream.engine.internal.target.TargetProducerOperationKind
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private data class AndroidCapturedContentSize(
    val widthPx: Int,
    val heightPx: Int,
)

private class AndroidProjectionCallbackCurrentness(
    val owner: AndroidCaptureOwner,
    val projection: MediaProjection,
    val registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    val callback: MediaProjection.Callback,
    val provenance: AndroidCallbackProvenance,
)

private class AndroidProjectionCallbackOccurrence(
    val currentness: AndroidProjectionCallbackCurrentness,
    val sequence: Long,
)

internal class AndroidCaptureOwner(
    private val projectionStopObligation: AndroidProjectionStopObligation,
    private val projectionOwnerEpoch: Long,
    private val callbackIdentity: Long,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    private val factSink: AndroidCaptureFactSink,
) {
    internal val cleanupQuitAction: AndroidLaneQuitAction = AndroidLaneQuitAction(this)
    internal val apiBand: AndroidCaptureApiBand = when (Build.VERSION.SDK_INT) {
        in Build.VERSION_CODES.N..Build.VERSION_CODES.S -> AndroidCaptureApiBand.Api24To31
        in Build.VERSION_CODES.S_V2..Build.VERSION_CODES.TIRAMISU -> AndroidCaptureApiBand.Api32To33
        in Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Build.VERSION_CODES.CINNAMON_BUN -> AndroidCaptureApiBand.Api34To37
        else -> AndroidCaptureApiBand.Unsupported
    }

    private val laneRuntime = AndroidLaneRuntime(settlementSignal)
    private val projection: MediaProjection = projectionStopObligation.bindAndroidOwner(this, laneRuntime)
    private val callbackRegistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    private val callbackProvenance = AtomicReference<AndroidCallbackProvenance?>(null)
    private val callbackSequence = AtomicLong(0L)
    private val lastCapturedContentSize = AtomicReference<AndroidCapturedContentSize?>(null)
    private val callbackRegistrationNoPlatformEntryProof =
        AtomicReference<AndroidNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>?>(null)
    private val callbackRegistrationReturned = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    private val callbackAuthorityOpen = AtomicBoolean(true)
    private val callbackCleanupOutcome =
        AtomicReference<AndroidProjectionCallbackCleanupOutcome?>(null)
    private val callbackUnregistrationOperation =
        AtomicReference<OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>?>(null)
    private val callbackUnregisterReturned = AtomicBoolean(false)
    private val virtualDisplayCreationOperation =
        AtomicReference<OperationOccurrence<AndroidVirtualDisplayCreationEvidence>?>(null)
    private val virtualDisplayCreationNoPlatformEntryProof =
        AtomicReference<AndroidNoPlatformEntryProof<AndroidVirtualDisplayCreationEvidence>?>(null)
    private val virtualDisplayCreationReturned = AtomicBoolean(false)
    private val targetListenerInstallationClaim =
        AtomicReference<TargetListenerInstallationRequestClaim?>(null)
    private val targetListenerInstallationBoundRoot =
        AtomicReference<AndroidTargetListenerInstallationBoundRoot?>(null)
    private val targetListenerInstallationUnboundClaimRetiredProof =
        AtomicReference<AndroidTargetListenerInstallationUnboundClaimRetiredProof?>(null)
    private val targetListenerInstallationFinalLaneNoEntryProof =
        AtomicReference<AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>?>(null)
    private val targetListenerInstallationAdmissionGate = ReentrantLock()
    private var targetListenerInstallationAdmissionOpen: Boolean = true
    private var targetListenerInstallationInFlight: Int = 0
    private val virtualDisplayOwner = AtomicReference<AndroidVirtualDisplayOwnership?>(null)
    private val virtualDisplayReleasePreparationClaimed = AtomicBoolean(false)
    private val virtualDisplayReleaseOperation =
        AtomicReference<OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>?>(null)
    private val virtualDisplayReleaseReturned = AtomicBoolean(false)
    internal val virtualDisplayMutations = AndroidVirtualDisplayMutator(
        ownership = virtualDisplayOwner,
        lane = laneRuntime,
        clock = clock,
        settlementSignal = settlementSignal,
    )
    private val projectionStopOperation = AtomicReference<OperationOccurrence<AndroidProjectionStopEvidence>?>(null)

    private val projectionCallback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val currentness = authoritativeApi34To37CallbackCurrentness() ?: return
            val size = AndroidCapturedContentSize(width, height)
            while (true) {
                val previous = lastCapturedContentSize.get()
                if (previous == size) return
                if (lastCapturedContentSize.compareAndSet(previous, size)) break
            }
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            val fact = AndroidCaptureFact.CapturedContentResized(
                provenance = callbackOccurrence.currentness.provenance,
                callbackSequence = callbackOccurrence.sequence,
                sampleNanos = clock.nowNanos(),
                widthPx = width,
                heightPx = height,
            )
            val operation = virtualDisplayCreationOperation.get()
            val recorded = operation?.settlementGate?.withLock {
                virtualDisplayCreationOperation.get() === operation && operation.returnCell.evidence.recordInitialResizeLocked(fact)
            } == true
            factSink.publish(fact)
            if (recorded) signalBestEffort()
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            val currentness = authoritativeApi34To37CallbackCurrentness() ?: return
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            factSink.publish(
                AndroidCaptureFact.CapturedContentVisibilityChanged(
                    provenance = callbackOccurrence.currentness.provenance,
                    callbackSequence = callbackOccurrence.sequence,
                    sampleNanos = clock.nowNanos(),
                    isVisible = isVisible,
                ),
            )
        }

        override fun onStop() {
            val currentness = authoritativeCallbackCurrentness() ?: return
            val callbackOccurrence = nextExactCallbackOccurrence(currentness) ?: return
            factSink.publish(
                AndroidCaptureFact.CaptureEnded(
                    provenance = callbackOccurrence.currentness.provenance,
                    callbackSequence = callbackOccurrence.sequence,
                    sampleNanos = clock.nowNanos(),
                ),
            )
        }
    }

    init {
        require(projectionOwnerEpoch > 0L)
        require(callbackIdentity > 0L)
    }

    internal val isProjectionCallbackRegistered: Boolean
        get() = callbackRegistered.get()

    internal val laneStartupResult: AndroidLaneStartupResult
        get() = laneRuntime.startupResult

    internal val observedLaneFatal: Throwable?
        get() = laneRuntime.observedFatal

    internal val observedOrdinaryLaneFailure: Exception?
        get() = laneRuntime.observedOrdinaryLaneFailure

    internal val hasLaneReturned: Boolean
        get() = laneTerminationReceipt != null

    internal val laneTerminationReceipt: AndroidLaneTerminationReceipt?
        get() = laneRuntime.terminationReceipt

    internal val laneReturnCause: Throwable?
        get() = laneRuntime.threadReturnCause

    internal val laneQuitOutcome: AndroidLaneQuitOutcome?
        get() = laneRuntime.observedQuitOutcome

    internal fun startLane(): Boolean = laneRuntime.start()

    internal fun createProjectionCallbackRegistrationOperation(
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>? {
        val ownerBag = AndroidProjectionCallbackRegistrationOwnerBag(projection, projectionCallback)
        val operation = finiteOccurrence(
            identity = identity,
            evidence = AndroidProjectionCallbackRegistrationEvidence(),
            ownerBag = ownerBag,
        )
        check(ownerBag.bindOperation(operation))
        val provenance = AndroidCallbackProvenance(
            owner = this,
            projectionOwnerEpoch = projectionOwnerEpoch,
            callbackRegistrationIdentity = operation.identity,
            callbackIdentity = callbackIdentity,
        )
        if (!callbackRegistrationOperation.compareAndSet(null, operation)) return null
        check(callbackProvenance.compareAndSet(null, provenance))
        return operation
    }

    internal fun submitProjectionCallbackRegistration(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidProjectionCallbackRegistrationOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android projection callback registration rejected",
            onReturnedThrow = { callbackRegistrationReturned.set(true) },
            onTicketCreated = { check(ownerBag.bindPostTicket(it)) },
            onPostOutcome = { outcome ->
                check(ownerBag.bindSchedulerOutcome(outcome))
                if (outcome == AndroidPostResult.NotSubmitted) {
                    operation.settleInertBeforeEntry()
                    signalBestEffort()
                }
            },
        ) {
            projection.registerCallback(projectionCallback, it)
            callbackRegistered.set(true)
            callbackRegistrationReturned.set(true)
        }
    }

    internal fun createTargetListenerInstallationOperation(
        target: CurrentTarget,
        identity: AndroidFiniteOperationIdentity,
    ): OperationOccurrence<AndroidTargetListenerInstallationEvidence>? {
        if (!reserveTargetListenerInstallationAdmission()) return null
        try {
            if (targetListenerInstallationClaim.get() != null ||
                targetListenerInstallationBoundRoot.get() != null
            ) {
                return null
            }
            val claim = target.claimListenerInstallationRequest(identity.operationIdentity) ?: return null
            targetListenerInstallationClaim.set(claim)
            val ownerBag = AndroidTargetListenerInstallationOwnerBag(target, claim)
            val operation = finiteOccurrence(
                identity = identity,
                evidence = AndroidTargetListenerInstallationEvidence(),
                ownerBag = ownerBag,
            )
            check(ownerBag.bindOperation(operation))
            val ticket = laneRuntime.ticket(
                occurrence = operation,
                postRejectionMessage = "Android target-listener installation rejected",
                enteredWork = AndroidEnteredWork { handler ->
                    try {
                        check(
                            ownerBag.port.withListener { surfaceTexture, listener ->
                                surfaceTexture.setOnFrameAvailableListener(listener, handler)
                            } == TargetPortUseOutcome.BodyReturned,
                        )
                        operation.publishNormalReturn()
                    } catch (failure: Exception) {
                        operation.publishThrownReturn(failure)
                    }
                    signalBestEffort()
                },
            )
            check(ownerBag.bindPostTicket(ticket))
            val boundRoot = AndroidTargetListenerInstallationBoundRoot.create(
                target,
                claim,
                ownerBag,
                operation,
                ticket,
            )
            check(targetListenerInstallationBoundRoot.compareAndSet(null, boundRoot))
            val committedFact = try {
                target.bindAndroidTargetOperation(claim, ownerBag.binding)
            } catch (raw: Throwable) {
                try {
                    if (boundRoot.exactCommittedCapability() == null) {
                        targetListenerInstallationBoundRoot.compareAndSet(boundRoot, null)
                    }
                } finally {
                    throw raw
                }
            }
            if (committedFact == null) {
                val recovered = boundRoot.exactCommittedCapability()
                if (recovered != null) return operation
                check(targetListenerInstallationBoundRoot.compareAndSet(boundRoot, null))
                return null
            }
            check(boundRoot.retainCommittedCapability(committedFact))
            return operation
        } finally {
            releaseTargetListenerInstallationAdmission()
        }
    }

    internal fun submitTargetListenerInstallation(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): Boolean {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return false
        if (boundRoot.exactCommittedCapability() == null) return false
        if (!boundRoot.claimSubmission()) return false
        if (!reserveTargetListenerInstallationAdmission()) {
            check(settleTargetListenerInstallationNoPlatformEntry(
                boundRoot,
                AndroidPostResult.NotSubmitted,
            ) != null)
            signalBestEffort()
            return false
        }
        return try {
            when (val schedulerOutcome = laneRuntime.post(boundRoot.ticket)) {
                AndroidPostResult.Accepted -> true
                AndroidPostResult.NotSubmitted,
                AndroidPostResult.Rejected,
                    -> {
                        check(settleTargetListenerInstallationNoPlatformEntry(
                            boundRoot,
                            schedulerOutcome,
                        ) != null)
                        signalBestEffort()
                        false
                    }
            }
        } finally {
            releaseTargetListenerInstallationAdmission()
        }
    }

    internal fun claimTargetListenerInstallationResult(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidTargetPlatformResult.ListenerInstalled? = operation.settlementGate.withLock {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return@withLock null
        val committedFact = boundRoot.exactCommittedCapability() ?: return@withLock null
        boundRoot.result.takeIf {
            committedFact.claim === boundRoot.claim && committedFact.binding === boundRoot.binding &&
                operation.returnCell.disposition == OperationReturnDisposition.Normal
        }
    }

    internal fun recordTargetListenerInstallationApplied(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidTargetListenerInstallationOwnerBag ?: return@withLock false
        val fact = result.fact
        if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            fact.targetIdentity !== ownerBag.port.targetIdentity || fact.operationIdentity != operation.identity ||
            fact.provenance !== ownerBag.port.provenance
        ) {
            return@withLock false
        }
        operation.returnCell.evidence.recordAppliedTargetResultLocked(result)
    }

    internal fun closeTargetListenerInstallationAdmission(): Boolean {
        val closed = targetListenerInstallationAdmissionGate.withLock {
            if (!targetListenerInstallationAdmissionOpen) return@withLock false
            targetListenerInstallationAdmissionOpen = false
            true
        }
        if (closed) signalBestEffort()
        return closed
    }

    internal fun acceptsTargetListenerInstallationUnboundClaimRetiredProofCreation(
        claim: TargetListenerInstallationRequestClaim,
    ): Boolean = targetListenerInstallationAdmissionGate.withLock {
        !targetListenerInstallationAdmissionOpen && targetListenerInstallationInFlight == 0 &&
            targetListenerInstallationClaim.get() === claim &&
            targetListenerInstallationBoundRoot.get() == null &&
            targetListenerInstallationFinalLaneNoEntryProof.get() == null
    }

    internal fun targetListenerInstallationUnboundClaimRetiredProof():
        AndroidTargetListenerInstallationUnboundClaimRetiredProof? =
        targetListenerInstallationAdmissionGate.withLock {
            if (targetListenerInstallationAdmissionOpen || targetListenerInstallationInFlight != 0) {
                return@withLock null
            }
            targetListenerInstallationUnboundClaimRetiredProof.get()?.let { return@withLock it }
            val claim = targetListenerInstallationClaim.get() ?: return@withLock null
            if (targetListenerInstallationBoundRoot.get() != null ||
                targetListenerInstallationFinalLaneNoEntryProof.get() != null
            ) {
                return@withLock null
            }
            val proof = AndroidTargetListenerInstallationUnboundClaimRetiredProof.create(this, claim)
            targetListenerInstallationUnboundClaimRetiredProof.compareAndSet(null, proof)
            targetListenerInstallationUnboundClaimRetiredProof.get()
        }

    internal fun targetListenerInstallationBindingCommittedFact(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): TargetListenerInstallationBindingCommittedFact? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        return boundRoot.exactCommittedCapability()
    }

    internal fun targetListenerInstallationNoPlatformEntryOutcome(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        if (boundRoot.exactCommittedCapability() == null) return null
        return recoverTargetListenerInstallationNoPlatformEntry(boundRoot)
    }

    internal fun createVirtualDisplayCreationOperation(
        target: CurrentTarget,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        identity: AndroidFiniteOperationIdentity,
        initialResizeDeadlineIdentity: AndroidInitialResizeDeadlineIdentity?,
    ): OperationOccurrence<AndroidVirtualDisplayCreationEvidence>? {
        require(widthPx > 0)
        require(heightPx > 0)
        require(densityDpi > 0)
        require(
            if (apiBand == AndroidCaptureApiBand.Api34To37) {
                initialResizeDeadlineIdentity != null
            } else {
                initialResizeDeadlineIdentity == null
            },
        )
        if (!callbackRegistered.get() || !callbackAuthorityOpen.get() || virtualDisplayCreationOperation.get() != null) {
            return null
        }
        val port = target.registerProducerPort(identity.operationIdentity, TargetProducerOperationKind.VirtualDisplayCreation) ?: return null
        val returnedOwnerCell = AndroidVirtualDisplayReturnedOwnerCell()
        val evidence = AndroidVirtualDisplayCreationEvidence(returnedOwnerCell)
        val initialLogicalTuple = AndroidVirtualDisplayLogicalTuple(widthPx, heightPx, densityDpi)
        val applicationCandidate = AndroidAttachedVirtualDisplay(
            returnedOwnerCell,
            target,
            port,
            evidence,
        )
        val ownerBag = AndroidVirtualDisplayCreationOwnerBag(
            projection = projection,
            target = target,
            port = port,
            initialLogicalTuple = initialLogicalTuple,
            applicationCandidate = applicationCandidate,
        )
        val operation = finiteOccurrence(
            identity = identity,
            evidence = evidence,
            ownerBag = ownerBag,
        )
        check(applicationCandidate.bindProducerOperation(operation))
        check(ownerBag.bindOperation(operation))
        check(target.bindAndroidTargetOperation(port, ownerBag.binding))
        if (initialResizeDeadlineIdentity != null) {
            val initialResizeDeadlineOccurrence = DeadlineOccurrence(
                identity = initialResizeDeadlineIdentity.deadlineIdentity,
                boundOccurrenceIdentity = operation.identity,
                durationNanos = initialCapturedResizeReadinessNanos,
                initialWakeGeneration = initialResizeDeadlineIdentity.deadlineWakeGeneration,
                timeoutCause = initialResizeDeadlineIdentity.timeoutCause,
                settlementGate = operation.settlementGate,
                clock = clock,
                signal = settlementSignal,
            )
            val deadlineBound = operation.settlementGate.withLock {
                evidence.bindInitialResizeDeadlineLocked(initialResizeDeadlineOccurrence)
            }
            check(deadlineBound)
        }
        return if (virtualDisplayCreationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitVirtualDisplayCreation(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayCreationOwnerBag
        val directCreateOutOfMemoryRecordFailure =
            IllegalStateException("Android VirtualDisplay direct OOME evidence was already recorded")
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay creation rejected",
            publishNormalReturn = false,
            onReturnedThrow = { virtualDisplayCreationReturned.set(true) },
            onThrownSettlement = { thrown -> publishVirtualDisplayCreationThrown(operation, thrown) },
            onTicketCreated = { check(ownerBag.bindPostTicket(it)) },
        ) {
            var returnedDisplay: VirtualDisplay? = null
            var directCreateOutOfMemoryError: OutOfMemoryError? = null
            val rawCallEntered = ownerBag.port.withSurface { surface ->
                val createdDisplay = try {
                    projection.createVirtualDisplay(
                        "ScreenCaptureEngine",
                        ownerBag.widthPx,
                        ownerBag.heightPx,
                        ownerBag.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface,
                        null,
                        null,
                    )
                } catch (error: OutOfMemoryError) {
                    val recorded = operation.settlementGate.withLock {
                        operation.returnCell.evidence.recordDirectCreateOutOfMemoryLocked(error)
                    }
                    if (!recorded) throw directCreateOutOfMemoryRecordFailure
                    directCreateOutOfMemoryError = error
                    return@withSurface
                }
                if (createdDisplay != null) {
                    val returnedOwnerRooted = operation.settlementGate.withLock {
                        operation.returnCell.evidence.rootReturnedVirtualDisplayLocked(createdDisplay) &&
                                ownerBag.applicationCandidate.mechanicalState.recordCreationReturnedLocked(
                                    ownerBag.initialLogicalTuple,
                                )
                    }
                    check(returnedOwnerRooted)
                }
                returnedDisplay = createdDisplay
            }
            check(rawCallEntered == TargetPortUseOutcome.BodyReturned)
            val directCreateFailure = directCreateOutOfMemoryError
            if (directCreateFailure != null) {
                publishVirtualDisplayCreationThrown(operation, directCreateFailure)
                virtualDisplayCreationReturned.set(true)
                return@submitToLane
            }
            val returnedDisplaySnapshot = returnedDisplay
            publishVirtualDisplayCreationReturn(operation, returnedDisplaySnapshot)
            virtualDisplayCreationReturned.set(true)
        }
    }

    internal fun claimVirtualDisplayCreationPlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
    ): AndroidTargetPlatformResult? = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return@withLock null
        val evidence = operation.returnCell.evidence
        evidence.selectedPlatformResult?.let { return@withLock it }
        val finalLaneNoEntryProof =
            virtualDisplayCreationNoPlatformEntryProof.get() as? AndroidFinalLaneNoEntryProof<*>
        val result = when {
            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    virtualDisplayCreationOperation.get() === operation &&
                    finalLaneNoEntryProof?.operation === operation ->
                ownerBag.unenteredResult

            operation.returnCell.disposition == OperationReturnDisposition.Normal && evidence.returnedVirtualDisplay != null ->
                ownerBag.producerResult

            operation.returnCell.disposition == OperationReturnDisposition.Normal ->
                ownerBag.returnedWithoutProducerResult

            operation.returnCell.disposition == OperationReturnDisposition.Thrown &&
                    evidence.returnedVirtualDisplay == null ->
                ownerBag.settledResult

            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    operation.entryDisposition != OperationEntryDisposition.Entered &&
                    (operation.submissionFailure != null ||
                            operation.disposition == OperationDisposition.SchedulerRejected ||
                            operation.disposition == OperationDisposition.DeadlineGuardFailed) ->
                ownerBag.unenteredResult

            operation.returnCell.disposition == OperationReturnDisposition.Empty &&
                    operation.domain == OperationDomain.Cleanup &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.Cancelled &&
                    operation.submissionFailure == null && operation.submissionAmbiguousFatal == null ->
                ownerBag.inapplicableResult

            else -> null
        } ?: return@withLock null
        check(evidence.recordSelectedPlatformResultLocked(result))
        result
    }

    internal fun applyVirtualDisplayCreationTargetResult(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult,
    ): Boolean {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayCreationOwnerBag ?: return false
        val evidence = operation.returnCell.evidence
        val candidate = ownerBag.applicationCandidate
        return operation.settlementGate.withLock {
            if (virtualDisplayCreationOperation.get() !== operation ||
                evidence.selectedPlatformResult == null ||
                evidence.appliedTargetFact != null || evidence.settledTargetResult != null
            ) {
                return@withLock false
            }
            when (result) {
                is io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.InitialProducerPortSettledOrAmbiguous -> {
                    val fact = result.fact
                    if (operation.returnCell.disposition != OperationReturnDisposition.Thrown ||
                        evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
                        evidence.returnedVirtualDisplay != null || evidence.selectedPlatformResult !== ownerBag.settledResult ||
                        fact.targetIdentity !== ownerBag.port.targetIdentity || fact.operationIdentity != operation.identity ||
                        fact.provenance !== ownerBag.port.provenance
                    ) {
                        return@withLock false
                    }
                    evidence.recordSettledTargetResultLocked(result)
                }

                is io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.Producer -> {
                    when (val fact = result.fact) {
                        is TargetNoProducerEvidence -> {
                            if (!matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)) {
                                return@withLock false
                            }
                            if (evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Empty ||
                                evidence.returnedVirtualDisplay != null ||
                                (evidence.selectedPlatformResult as? AndroidTargetPlatformResult.ProducerUnavailable)?.reason != fact.reason
                            ) {
                                return@withLock false
                            }
                            if (!evidence.recordAppliedTargetFactLocked(fact)) return@withLock false
                            val existingOwnership = virtualDisplayOwner.get()
                            if (existingOwnership == null) {
                                true
                            } else {
                                if (!evidence.recordCollisionLocked(existingOwnership)) return@withLock false
                                false
                            }
                        }

                        is TargetProducerEvidence -> {
                            if (!matchesProducerFact(fact, operation, ownerBag.target, ownerBag.port)) {
                                return@withLock false
                            }
                            val returnedDisplay = evidence.returnedVirtualDisplay ?: return@withLock false
                            if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
                                evidence.selectedPlatformResult !== ownerBag.producerResult ||
                                evidence.returnedOwnerDisposition != AndroidVirtualDisplayReturnedOwnerDisposition.Rooted
                            ) {
                                return@withLock false
                            }
                            if (candidate.virtualDisplay !== returnedDisplay ||
                                !evidence.recordAppliedTargetFactLocked(fact)
                            ) {
                                return@withLock false
                            }
                            if (virtualDisplayOwner.compareAndSet(null, candidate)) {
                                evidence.recordInstalledLocked()
                            } else {
                                if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
                                false
                            }
                        }
                    }
                }

                else -> false
            }
        }
    }

    internal fun createTargetListenerRemovalOperation(
        target: CurrentTarget,
        operationIdentity: Long,
        finiteIdentity: AndroidFiniteOperationIdentity?,
    ): OperationOccurrence<AndroidTargetListenerRemovalEvidence>? {
        require(operationIdentity > 0L)
        require(finiteIdentity == null || finiteIdentity.operationIdentity == operationIdentity)
        val port = target.registerListenerRemovalPort(operationIdentity) ?: return null
        val evidence = AndroidTargetListenerRemovalEvidence()
        val ownerBag = AndroidTargetListenerRemovalOwnerBag(target, port)
        val operation = if (finiteIdentity == null) {
            cleanupOccurrence(operationIdentity, evidence, ownerBag)
        } else {
            finiteOccurrence(finiteIdentity, evidence, ownerBag)
        }
        val sentinelOperation = cleanupOccurrence(
            operationIdentity,
            AndroidListenerSentinelEvidence(),
            ownerBag,
        )
        check(ownerBag.bindOperations(operation, sentinelOperation))
        check(target.bindAndroidListenerRemovalOperations(port, ownerBag.removalBinding, ownerBag.sentinelBinding))
        val sentinelTicket = laneRuntime.ticket(
            sentinelOperation,
            "Android target-listener sentinel rejected",
            AndroidEnteredWork {
                val targetResult = checkNotNull(
                    ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.sentinelObservedResult),
                ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved
                check(sentinelOperation.settlementGate.withLock {
                    sentinelOperation.returnCell.evidence.recordObservedTargetResultLocked(targetResult)
                })
                sentinelOperation.publishNormalReturn()
                signalBestEffort()
            },
        )
        check(ownerBag.bindSentinelTicket(sentinelTicket))
        return operation
    }

    internal fun submitTargetListenerRemoval(operation: OperationOccurrence<AndroidTargetListenerRemovalEvidence>): Boolean {
        val ownerBag = operation.ownerBag as AndroidTargetListenerRemovalOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android target-listener removal rejected",
            publishNormalReturn = false,
        ) { handler ->
            check(
                ownerBag.port.withSurfaceTexture { surfaceTexture ->
                    surfaceTexture.setOnFrameAvailableListener(null, handler)
                } == TargetPortUseOutcome.BodyReturned,
            )
            val removalReturnRecorded = operation.settlementGate.withLock {
                operation.returnCell.evidence.recordListenerRemovalReturnLocked()
            }
            check(removalReturnRecorded)
            val removalTargetResult = checkNotNull(
                ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.removalReturnedResult),
            ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalReturned
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordRemovalReturnedTargetResultLocked(removalTargetResult)
            })
            val settledTargetResult = checkNotNull(
                ownerBag.target.consumeAndroidTargetPlatformResult(ownerBag.removalSettledResult),
            ) as io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalSettled
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordSettledTargetResultLocked(settledTargetResult)
            })
            val sentinelPostResult = try {
                laneRuntime.post(ownerBag.sentinelTicket)
            } catch (raw: Throwable) {
                check(operation.settlementGate.withLock {
                    operation.returnCell.evidence.recordSentinelPostOutcomeLocked(ownerBag.sentinelPostExposed)
                })
                if (raw is Exception) {
                    operation.publishThrownReturn(raw)
                    return@submitToLane
                }
                throw raw
            }
            val sentinelOutcome = if (sentinelPostResult == AndroidPostResult.Accepted ||
                ownerBag.sentinelTicket.occurrence.entryDisposition == OperationEntryDisposition.Entered ||
                ownerBag.sentinelTicket.occurrence.submissionDisposition == OperationSubmissionDisposition.Accepted
            ) {
                ownerBag.sentinelPostExposed
            } else {
                ownerBag.sentinelDefinitelyUnentered
            }
            check(operation.settlementGate.withLock {
                operation.returnCell.evidence.recordSentinelPostOutcomeLocked(sentinelOutcome)
            })
            if (sentinelPostResult == AndroidPostResult.Accepted) operation.publishNormalReturn()
            else operation.publishThrownReturn(ownerBag.sentinelTicket.postRejectedCause)
        }
    }

    internal fun closeProjectionCallbackAuthority(): Boolean = callbackAuthorityOpen.compareAndSet(true, false)

    internal fun foldFinalLaneTerminationReceipt(receipt: AndroidLaneTerminationReceipt) {
        foldFinalLaneNoEntry(
            receipt = receipt,
            operation = callbackRegistrationOperation.get(),
            retainedOperation = callbackRegistrationOperation,
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            ticket = { ownerBag ->
                (ownerBag as? AndroidProjectionCallbackRegistrationOwnerBag)?.postTicket
            },
        )
        foldFinalLaneNoEntry(
            receipt = receipt,
            operation = virtualDisplayCreationOperation.get(),
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            ticket = { ownerBag ->
                (ownerBag as? AndroidVirtualDisplayCreationOwnerBag)?.postTicket
            },
        )
        foldFinalListenerInstallationNoEntry(
            receipt = receipt,
            operation = targetListenerInstallationBoundRoot.get()?.operation,
        )
    }

    internal fun finalLaneListenerInstallationNoEntryProof(
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): AndroidFinalLaneNoEntryProof<AndroidTargetListenerInstallationEvidence>? {
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return null
        val proof = targetListenerInstallationFinalLaneNoEntryProof.get() ?: return null
        return operation.settlementGate.withLock {
            proof.takeIf {
                targetListenerInstallationBoundRoot.get() === boundRoot &&
                    boundRoot.activatedNoPlatformEntryOutcomeLocked() == null &&
                    boundRoot.exactCommittedCapability() != null &&
                    boundRoot.ticket === it.ticket &&
                    it.operation === operation &&
                    it.operationIdentity == operation.identity &&
                    it.ticket.occurrence === operation &&
                    it.ticket.operationIdentity == operation.identity &&
                    it.lane === laneRuntime &&
                    laneRuntime.terminationReceipt === it.terminationReceipt &&
                    laneRuntime.acceptsTerminationReceipt(it.terminationReceipt) &&
                    it.ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    it.ticket.postFailureResidue == null &&
                    operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
                    operation.submissionFailure == null &&
                    operation.submissionAmbiguousFatal == null &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.Cancelled &&
                    operation.returnCell.disposition == OperationReturnDisposition.Empty
            }
        }
    }

    internal fun createProjectionCallbackUnregistrationOperation(
        operationIdentity: Long,
    ): OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>? {
        require(operationIdentity > 0L)
        if (callbackAuthorityOpen.get() || callbackUnregisterReturned.get()) return null
        val registrationOperation = callbackRegistrationOperation.get() ?: return null
        publishNoPlatformEntryIfProven(
            operation = registrationOperation,
            retainedOperation = callbackRegistrationOperation,
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            occurrenceProof = (registrationOperation.ownerBag as AndroidProjectionCallbackRegistrationOwnerBag)
                .occurrenceNoEntryProof,
        )
        if (callbackRegistrationNoPlatformEntryProof.get() != null || !callbackRegistrationReturned.get()) return null

        val operation = cleanupOccurrence(
            identity = operationIdentity,
            evidence = AndroidProjectionCallbackUnregistrationEvidence(),
            ownerBag = AndroidProjectionCallbackUnregistrationOwnerBag(projection, projectionCallback),
        )
        return if (callbackUnregistrationOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun projectionCallbackCleanupOutcome(
        registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?,
    ): AndroidProjectionCallbackCleanupOutcome? {
        callbackCleanupOutcome.get()?.let { outcome ->
            return outcome.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }
        if (callbackAuthorityOpen.get()) return null
        if (registrationOperation == null) {
            if (callbackRegistrationOperation.get() != null) return null
            val outcome = AndroidProjectionCallbackCleanupOutcome.StructurallyInapplicable(this)
            callbackCleanupOutcome.compareAndSet(null, outcome)
            return callbackCleanupOutcome.get()
                ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }
        if (callbackRegistrationOperation.get() !== registrationOperation) return null
        val ownerBag = registrationOperation.ownerBag as? AndroidProjectionCallbackRegistrationOwnerBag
            ?: return null
        if (ownerBag.projection !== projection || ownerBag.callback !== projectionCallback ||
            ownerBag.occurrenceNoEntryProof.operation !== registrationOperation ||
            registrationOperation.returnCell.evidence.receipt !== AndroidProjectionCallbackRegistrationReceipt
        ) {
            return null
        }

        publishNoPlatformEntryIfProven(
            operation = registrationOperation,
            retainedOperation = callbackRegistrationOperation,
            noPlatformEntryProof = callbackRegistrationNoPlatformEntryProof,
            occurrenceProof = ownerBag.occurrenceNoEntryProof,
        )?.let { proof ->
            val route = registrationOperation.settlementGate.withLock {
                exactProjectionCallbackNoEntryRouteLocked(registrationOperation, ownerBag, proof)
            } ?: return null
            val outcome = AndroidProjectionCallbackCleanupOutcome.RegistrationDidNotEnterPlatform(
                owner = this,
                operation = registrationOperation,
                ownerBag = ownerBag,
                proof = proof,
                route = route,
            )
            callbackCleanupOutcome.compareAndSet(null, outcome)
            return callbackCleanupOutcome.get()
                ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
        }

        val unregistration = callbackUnregistrationOperation.get() ?: return null
        val unregistrationOwnerBag = unregistration.ownerBag
            as? AndroidProjectionCallbackUnregistrationOwnerBag ?: return null
        val returnDisposition = registrationOperation.settlementGate.withLock {
            if (callbackRegistrationOperation.get() !== registrationOperation ||
                registrationOperation.entryDisposition != OperationEntryDisposition.Entered ||
                registrationOperation.returnCell.disposition == OperationReturnDisposition.Empty ||
                registrationOperation.returnCell.evidence.receipt !== AndroidProjectionCallbackRegistrationReceipt ||
                !callbackRegistrationReturned.get()
            ) {
                return@withLock null
            }
            unregistration.settlementGate.withLock {
                if (callbackUnregistrationOperation.get() !== unregistration ||
                    unregistrationOwnerBag.projection !== projection ||
                    unregistrationOwnerBag.callback !== projectionCallback ||
                    unregistration.entryDisposition != OperationEntryDisposition.Entered ||
                    unregistration.returnCell.disposition == OperationReturnDisposition.Empty ||
                    unregistration.returnCell.evidence.receipt !== AndroidProjectionCallbackUnregistrationReceipt ||
                    !callbackUnregisterReturned.get()
                ) {
                    null
                } else {
                    unregistration.returnCell.disposition
                }
            }
        } ?: return null
        val outcome = AndroidProjectionCallbackCleanupOutcome.UnregistrationReturned(
            owner = this,
            registrationOperation = registrationOperation,
            registrationOwnerBag = ownerBag,
            unregistrationOperation = unregistration,
            unregistrationOwnerBag = unregistrationOwnerBag,
            returnDisposition = returnDisposition,
        )
        callbackCleanupOutcome.compareAndSet(null, outcome)
        return callbackCleanupOutcome.get()
            ?.takeIf { acceptsProjectionCallbackCleanupOutcome(it, registrationOperation) }
    }

    internal fun submitProjectionCallbackUnregistration(
        operation: OperationOccurrence<AndroidProjectionCallbackUnregistrationEvidence>,
    ): Boolean = submitToLane(
        operation = operation,
        postRejectionMessage = "Android projection callback unregistration rejected",
        onReturnedThrow = { callbackUnregisterReturned.set(true) },
    ) {
        projection.unregisterCallback(projectionCallback)
        callbackRegistered.set(false)
        callbackUnregisterReturned.set(true)
    }

    internal fun createVirtualDisplayReleaseOperation(
        operationIdentity: Long,
    ): OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>? {
        require(operationIdentity > 0L)
        if (!isProjectionCallbackCleanupComplete() || virtualDisplayMutations.hasUnsettledOperation ||
            virtualDisplayReleaseReturned.get()
        ) {
            return null
        }
        val creationOperation = virtualDisplayCreationOperation.get() ?: return null
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            occurrenceProof = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag)
                .occurrenceNoEntryProof,
        )
        if (virtualDisplayCreationNoPlatformEntryProof.get() != null || !virtualDisplayCreationReturned.get()) return null
        if (!virtualDisplayReleasePreparationClaimed.compareAndSet(false, true)) return null
        var retained = false
        try {
            val ownership = virtualDisplayOwner.get() ?: return null
            val mode = when (ownership) {
                is AndroidAttachedVirtualDisplay -> AndroidVirtualDisplayReleaseMode.Attached(
                    ownership,
                    ownership.target.registerVirtualDisplayReleasePort(operationIdentity) ?: return null,
                )

                is AndroidAttachmentUncertainVirtualDisplay -> AndroidVirtualDisplayReleaseMode.AttachmentUncertain(
                    ownership,
                    ownership.target.registerVirtualDisplayReleasePort(operationIdentity) ?: return null,
                )

                is AndroidMechanicallyDetachedVirtualDisplay ->
                    AndroidVirtualDisplayReleaseMode.MechanicallyDetached(ownership)
            }
            val ownerBag = AndroidVirtualDisplayReleaseOwnerBag(mode)
            val operation = cleanupOccurrence(
                identity = operationIdentity,
                evidence = AndroidVirtualDisplayReleaseEvidence(),
                ownerBag = ownerBag,
            )
            when (mode) {
                is AndroidVirtualDisplayReleaseMode.Attached -> {
                    check(ownerBag.bindOperation(operation))
                    check(mode.ownership.target.bindAndroidTargetOperation(mode.targetPort, ownerBag.binding))
                }

                is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> {
                    check(ownerBag.bindOperation(operation))
                    check(mode.ownership.target.bindAndroidTargetOperation(mode.targetPort, ownerBag.binding))
                }

                is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> Unit
            }
            retained = virtualDisplayReleaseOperation.compareAndSet(null, operation)
            return if (retained) operation else null
        } finally {
            if (!retained) virtualDisplayReleasePreparationClaimed.set(false)
        }
    }

    internal fun submitVirtualDisplayRelease(operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>): Boolean {
        val ownerBag = operation.ownerBag as AndroidVirtualDisplayReleaseOwnerBag
        return submitToLane(
            operation = operation,
            postRejectionMessage = "Android VirtualDisplay release rejected",
            onReturnedThrow = { virtualDisplayReleaseReturned.set(true) },
        ) {
            check(virtualDisplayOwner.get() === ownerBag.mode.ownership)
            ownerBag.virtualDisplay.release()
            virtualDisplayReleaseReturned.set(true)
        }
    }

    internal fun claimVirtualDisplayReleasePlatformResult(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
    ): AndroidTargetPlatformResult.ProducerDetached? = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock null
        if (operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            ownerBag.mode is AndroidVirtualDisplayReleaseMode.MechanicallyDetached
        ) {
            return@withLock null
        }
        operation.returnCell.evidence.selectedPlatformResult?.let { return@withLock it }
        ownerBag.result.also { check(operation.returnCell.evidence.recordSelectedPlatformResultLocked(it)) }
    }

    internal fun applyAttachedVirtualDisplayReleaseTargetResult(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val targetResult = result as? io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.Detach
            ?: return@withLock false
        val fact = targetResult.receipt
        val targetPort = when (val mode = ownerBag.mode) {
            is AndroidVirtualDisplayReleaseMode.Attached -> mode.targetPort
            is AndroidVirtualDisplayReleaseMode.AttachmentUncertain -> mode.targetPort
            is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> return@withLock false
        }
        val evidence = operation.returnCell.evidence
        if (virtualDisplayReleaseOperation.get() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.selectedPlatformResult !== ownerBag.result ||
            evidence.appliedTargetFact != null ||
            fact.operationIdentity != operation.identity || fact.provenance !== targetPort.provenance
        ) {
            return@withLock false
        }
        if (virtualDisplayOwner.get() !== ownerBag.mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(ownerBag.mode.ownership, null)) {
            check(evidence.recordAppliedTargetFactLocked(fact))
            evidence.recordClearedLocked(ownerBag.mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun completeMechanicallyDetachedVirtualDisplayRelease(
        operation: OperationOccurrence<AndroidVirtualDisplayReleaseEvidence>,
    ): Boolean = operation.settlementGate.withLock {
        val ownerBag = operation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag ?: return@withLock false
        val mode = ownerBag.mode as? AndroidVirtualDisplayReleaseMode.MechanicallyDetached ?: return@withLock false
        val evidence = operation.returnCell.evidence
        if (virtualDisplayReleaseOperation.get() !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Normal ||
            evidence.clearedOwnership != null ||
            evidence.collisionObserved
        ) {
            return@withLock false
        }
        if (virtualDisplayOwner.get() !== mode.ownership) {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            return@withLock false
        }
        if (virtualDisplayOwner.compareAndSet(mode.ownership, null)) {
            evidence.recordClearedLocked(mode.ownership)
        } else {
            if (!evidence.recordCollisionLocked(virtualDisplayOwner.get())) return@withLock false
            false
        }
    }

    internal fun createProjectionStopOperation(operationIdentity: Long): OperationOccurrence<AndroidProjectionStopEvidence>? {
        require(operationIdentity > 0L)
        if (!projectionStopObligation.hasSealedTerminalContext ||
            !isProjectionCallbackCleanupComplete() || !isVirtualDisplayCleanupComplete() ||
            projectionStopObligation.closureReceipt() != null
        ) {
            return null
        }
        val operation = projectionStopObligation.createOperation()
        check(operation.identity == operationIdentity)
        return if (projectionStopOperation.compareAndSet(null, operation)) operation else null
    }

    internal fun submitProjectionStop(
        operation: OperationOccurrence<AndroidProjectionStopEvidence>,
    ): Boolean {
        if (projectionStopOperation.get() !== operation) return false
        val ticket = laneRuntime.ticket(
            occurrence = operation,
            postRejectionMessage = "Android projection stop rejected",
            enteredWork = AndroidEnteredWork { projectionStopObligation.invokeNormal(operation, checkNotNull(
                (operation.ownerBag as AndroidProjectionStopOwnerBag).normalTicket,
            )) },
        )
        check(projectionStopObligation.bindNormalTicket(operation, ticket))
        return laneRuntime.post(ticket) == AndroidPostResult.Accepted
    }

    internal fun requestLaneQuitSafely(): Boolean {
        val operation = projectionStopOperation.get() ?: return false
        val stopReturned = operation.settlementGate.withLock {
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        }
        if (!stopReturned && projectionStopObligation.laneNeverStartedProof() == null &&
            laneTerminationReceipt == null
        ) return false
        return laneRuntime.requestQuitSafely()
    }

    internal val isLaneQuitReady: Boolean
        get() = projectionStopOperation.get() != null

    internal val projectionClosureReceipt: AndroidProjectionClosureReceipt?
        get() = projectionStopObligation.closureReceipt()

    internal fun sealProjectionStopTerminalContext(
        cutoffIdentity: Any,
        workManifestIdentity: Any,
    ) {
        val cutoff = projectionStopObligation.sealTerminalCutoff(cutoffIdentity)
        projectionStopObligation.sealWorkManifest(cutoff, workManifestIdentity)
    }

    internal fun prepareFinalProjectionStopAction(): AndroidFinalProjectionStopAction? {
        if (!isProjectionCallbackCleanupComplete() || !isVirtualDisplayCleanupComplete()) return null
        val exactOperation = projectionStopOperation.get() ?: projectionStopObligation.createOperation().also {
            projectionStopOperation.compareAndSet(null, it)
        }
        if (projectionStopOperation.get() !== exactOperation) return null
        val prerequisites = AndroidProjectionStopPrerequisitesProof(
            projectionStopObligation,
            this,
            currentCallbackCleanupEvidenceIdentity(),
            currentVirtualDisplayCleanupEvidenceIdentity(),
        )
        val neverStarted = projectionStopObligation.laneNeverStartedProof()
        val finalLaneNoEntry = if (neverStarted == null) {
            val receipt = laneTerminationReceipt ?: return null
            val ownerBag = exactOperation.ownerBag as? AndroidProjectionStopOwnerBag ?: return null
            val ticket = ownerBag.normalTicket ?: return null
            exactOperation.settlementGate.withLock {
                laneRuntime.observeFinalLaneNoEntryLocked(receipt, ticket, exactOperation)
            }
        } else {
            null
        }
        return projectionStopObligation.prepareFinalAction(prerequisites, neverStarted, finalLaneNoEntry)
    }

    internal fun acceptsProjectionStopPrerequisites(
        proof: AndroidProjectionStopPrerequisitesProof,
    ): Boolean = proof.obligation === projectionStopObligation && proof.androidOwner === this &&
            isProjectionCallbackCleanupComplete() && isVirtualDisplayCleanupComplete() &&
            proof.callbackEvidenceIdentity === currentCallbackCleanupEvidenceIdentity() &&
            proof.virtualDisplayEvidenceIdentity === currentVirtualDisplayCleanupEvidenceIdentity()

    private fun currentCallbackCleanupEvidenceIdentity(): Any = callbackCleanupOutcome.get()
        ?: callbackUnregistrationOperation.get()
        ?: callbackRegistrationNoPlatformEntryProof.get()
        ?: callbackRegistrationOperation.get()
        ?: this

    private fun currentVirtualDisplayCleanupEvidenceIdentity(): Any = virtualDisplayReleaseOperation.get()
        ?: virtualDisplayCreationNoPlatformEntryProof.get()
        ?: virtualDisplayCreationOperation.get()
        ?: this

    private fun authoritativeApi34To37CallbackCurrentness(): AndroidProjectionCallbackCurrentness? =
        when (Build.VERSION.SDK_INT) {
            in Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Build.VERSION_CODES.CINNAMON_BUN ->
                authoritativeCallbackCurrentness()

            else -> null
        }

    private fun authoritativeCallbackCurrentness(): AndroidProjectionCallbackCurrentness? {
        if (!callbackAuthorityOpen.get() || !callbackRegistered.get()) return null
        val registrationOperation = callbackRegistrationOperation.get() ?: return null
        val provenance = callbackProvenance.get() ?: return null
        return AndroidProjectionCallbackCurrentness(
            owner = this,
            projection = projection,
            registrationOperation = registrationOperation,
            callback = projectionCallback,
            provenance = provenance,
        )
    }

    private fun isExactCallbackCurrent(currentness: AndroidProjectionCallbackCurrentness): Boolean =
        currentness.owner === this &&
                currentness.projection === projection &&
                currentness.callback === projectionCallback &&
                callbackAuthorityOpen.get() &&
                callbackRegistered.get() &&
                callbackRegistrationOperation.get() === currentness.registrationOperation &&
                callbackProvenance.get() === currentness.provenance

    private fun nextExactCallbackOccurrence(
        currentness: AndroidProjectionCallbackCurrentness,
    ): AndroidProjectionCallbackOccurrence? {
        val sequence = nextCallbackSequence() ?: return null
        if (!isExactCallbackCurrent(currentness)) return null
        return AndroidProjectionCallbackOccurrence(currentness, sequence)
    }

    private fun nextCallbackSequence(): Long? {
        while (true) {
            val current = callbackSequence.get()
            if (current == Long.MAX_VALUE) {
                closeProjectionCallbackAuthority()
                signalBestEffort()
                return null
            }
            val next = current + 1L
            if (callbackSequence.compareAndSet(current, next)) return next
        }
    }

    private fun isProjectionCallbackCleanupComplete(): Boolean {
        return projectionCallbackCleanupOutcome(callbackRegistrationOperation.get()) != null
    }

    private fun exactProjectionCallbackNoEntryRouteLocked(
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
        ownerBag: AndroidProjectionCallbackRegistrationOwnerBag,
        proof: AndroidNoPlatformEntryProof<AndroidProjectionCallbackRegistrationEvidence>,
    ): AndroidProjectionCallbackNoPlatformEntryRoute? {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (callbackRegistrationOperation.get() !== operation || proof.operation !== operation ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty ||
            operation.submissionAmbiguousFatal != null
        ) {
            return null
        }
        val ticket = ownerBag.postTicket
        return when (ownerBag.schedulerOutcome) {
            null -> AndroidProjectionCallbackNoPlatformEntryRoute.PreparedButNeverSubmitted.takeIf {
                ticket == null && operation.submissionDisposition == OperationSubmissionDisposition.Cancelled &&
                    operation.submissionFailure == null &&
                    operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                    operation.disposition == OperationDisposition.Cancelled &&
                    proof === ownerBag.occurrenceNoEntryProof
            }

            AndroidPostResult.NotSubmitted -> AndroidProjectionCallbackNoPlatformEntryRoute.SchedulerNotSubmitted
                .takeIf {
                    exactProjectionCallbackTicket(ticket, operation) &&
                        ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                        ticket?.postFailureResidue == null &&
                        operation.submissionDisposition == OperationSubmissionDisposition.None &&
                        operation.submissionFailure == null &&
                        operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                        operation.disposition == OperationDisposition.Cancelled &&
                        proof === ownerBag.occurrenceNoEntryProof
                }

            AndroidPostResult.Rejected -> AndroidProjectionCallbackNoPlatformEntryRoute.SchedulerRejected.takeIf {
                exactProjectionCallbackTicket(ticket, operation) &&
                    ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                    ticket?.postFailureResidue != null &&
                    operation.submissionDisposition == OperationSubmissionDisposition.Rejected &&
                    operation.submissionFailure === ticket?.postFailureResidue &&
                    operation.entryDisposition == OperationEntryDisposition.Unentered &&
                    operation.disposition == OperationDisposition.SchedulerRejected &&
                    proof === ownerBag.occurrenceNoEntryProof
            }

            AndroidPostResult.Accepted -> AndroidProjectionCallbackNoPlatformEntryRoute.AcceptedDefinitelyUnentered
                .takeIf {
                    exactProjectionCallbackTicket(ticket, operation) && ticket?.postFailureResidue == null &&
                        operation.submissionFailure == null &&
                        operation.entryDisposition != OperationEntryDisposition.Entered &&
                        (operation.entryDisposition == OperationEntryDisposition.Cancelled &&
                            operation.disposition == OperationDisposition.Cancelled ||
                            operation.entryDisposition == OperationEntryDisposition.Unentered &&
                            operation.disposition == OperationDisposition.DeadlineGuardFailed) &&
                        when (proof) {
                            is AndroidFinalLaneNoEntryProof ->
                                ticket === proof.ticket && proof.operationIdentity == operation.identity &&
                                    proof.lane === laneRuntime &&
                                    proof.workerIdentity === ticket?.workerIdentity &&
                                    laneRuntime.terminationReceipt === proof.terminationReceipt &&
                                    laneRuntime.acceptsTerminationReceipt(proof.terminationReceipt) &&
                                    ticket?.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                                    operation.submissionDisposition == OperationSubmissionDisposition.Accepted

                            else -> proof === ownerBag.occurrenceNoEntryProof
                        }
                }
        }
    }

    private fun exactProjectionCallbackTicket(
        ticket: AndroidPostTicket<AndroidProjectionCallbackRegistrationEvidence>?,
        operation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>,
    ): Boolean = ticket != null && ticket.lane === laneRuntime && ticket.occurrence === operation &&
            ticket.operationIdentity == operation.identity && ticket.workerIdentity.lane === laneRuntime &&
            ticket.terminationReceipt.lane === laneRuntime &&
            ticket.terminationReceipt.matchesWorker(ticket.workerIdentity)

    private fun acceptsProjectionCallbackCleanupOutcome(
        outcome: AndroidProjectionCallbackCleanupOutcome,
        registrationOperation: OperationOccurrence<AndroidProjectionCallbackRegistrationEvidence>?,
    ): Boolean = when (outcome) {
        is AndroidProjectionCallbackCleanupOutcome.StructurallyInapplicable ->
            outcome.owner === this && registrationOperation == null && callbackRegistrationOperation.get() == null

        is AndroidProjectionCallbackCleanupOutcome.RegistrationDidNotEnterPlatform ->
            outcome.owner === this && outcome.operation === registrationOperation &&
                callbackRegistrationOperation.get() === outcome.operation &&
                outcome.ownerBag === outcome.operation.ownerBag && outcome.proof.operation === outcome.operation

        is AndroidProjectionCallbackCleanupOutcome.UnregistrationReturned ->
            outcome.owner === this && outcome.registrationOperation === registrationOperation &&
                callbackRegistrationOperation.get() === outcome.registrationOperation &&
                callbackUnregistrationOperation.get() === outcome.unregistrationOperation &&
                outcome.registrationOwnerBag === outcome.registrationOperation.ownerBag &&
                outcome.unregistrationOwnerBag === outcome.unregistrationOperation.ownerBag
    }

    private fun isVirtualDisplayCleanupComplete(): Boolean {
        if (virtualDisplayMutations.hasUnsettledOperation) return false
        val creationOperation = virtualDisplayCreationOperation.get() ?: return true
        publishNoPlatformEntryIfProven(
            operation = creationOperation,
            retainedOperation = virtualDisplayCreationOperation,
            noPlatformEntryProof = virtualDisplayCreationNoPlatformEntryProof,
            occurrenceProof = (creationOperation.ownerBag as AndroidVirtualDisplayCreationOwnerBag)
                .occurrenceNoEntryProof,
        )
        if (virtualDisplayCreationNoPlatformEntryProof.get() == null && !virtualDisplayCreationReturned.get()) return false
        if (virtualDisplayCreationNoPlatformEntryProof.get() != null) return true

        val releaseOperation = virtualDisplayReleaseOperation.get()
            ?: return creationOperation.settlementGate.withLock {
                val evidence = creationOperation.returnCell.evidence
                creationOperation.returnCell.disposition != OperationReturnDisposition.Empty &&
                        evidence.returnedOwnerDisposition == AndroidVirtualDisplayReturnedOwnerDisposition.Empty &&
                        evidence.returnedVirtualDisplay == null
            }

        return releaseOperation.settlementGate.withLock {
            if (virtualDisplayReleaseOperation.get() !== releaseOperation) return@withLock false
            val evidence = releaseOperation.returnCell.evidence
            when (releaseOperation.returnCell.disposition) {
                OperationReturnDisposition.Empty -> false
                OperationReturnDisposition.Thrown -> true
                OperationReturnDisposition.Normal -> {
                    val mode = (releaseOperation.ownerBag as? AndroidVirtualDisplayReleaseOwnerBag)?.mode
                        ?: return@withLock false
                    val ownershipSettled = evidence.clearedOwnership === mode.ownership || evidence.collisionObserved
                    when (mode) {
                        is AndroidVirtualDisplayReleaseMode.Attached ->
                            evidence.appliedTargetFact != null && ownershipSettled

                        is AndroidVirtualDisplayReleaseMode.AttachmentUncertain ->
                            evidence.appliedTargetFact != null && ownershipSettled

                        is AndroidVirtualDisplayReleaseMode.MechanicallyDetached -> ownershipSettled
                    }
                }
            }
        }
    }

    private fun <R : OperationEvidence> publishNoPlatformEntryIfProven(
        operation: OperationOccurrence<R>,
        retainedOperation: AtomicReference<OperationOccurrence<R>?>,
        noPlatformEntryProof: AtomicReference<AndroidNoPlatformEntryProof<R>?>,
        occurrenceProof: AndroidOccurrenceNoPlatformEntryProof<R>,
    ): AndroidNoPlatformEntryProof<R>? {
        noPlatformEntryProof.get()?.let { return it }
        if (retainedOperation.get() !== operation || occurrenceProof.operation !== operation) return null

        return operation.settlementGate.withLock {
            noPlatformEntryProof.get()?.let { return@withLock it }
            if (retainedOperation.get() !== operation ||
                occurrenceProof.operation !== operation ||
                operation.returnCell.disposition != OperationReturnDisposition.Empty
            ) {
                return@withLock null
            }

            val noPlatformEntryProven = when (operation.entryDisposition) {
                OperationEntryDisposition.Cancelled -> when (operation.disposition) {
                    OperationDisposition.Cancelled,
                    OperationDisposition.SchedulerRejected,
                    OperationDisposition.DeadlineGuardFailed,
                        -> true

                    else -> false
                }

                OperationEntryDisposition.Unentered -> when (operation.disposition) {
                    OperationDisposition.SchedulerRejected ->
                        operation.submissionDisposition == OperationSubmissionDisposition.Rejected

                    OperationDisposition.DeadlineGuardFailed -> true
                    else -> false
                }

                OperationEntryDisposition.Entered -> false
            }
            if (!noPlatformEntryProven) return@withLock null

            noPlatformEntryProof.compareAndSet(null, occurrenceProof)
            noPlatformEntryProof.get()
        }
    }

    private fun <R : OperationEvidence> foldFinalLaneNoEntry(
        receipt: AndroidLaneTerminationReceipt,
        operation: OperationOccurrence<R>?,
        retainedOperation: AtomicReference<OperationOccurrence<R>?>,
        noPlatformEntryProof: AtomicReference<AndroidNoPlatformEntryProof<R>?>,
        ticket: (OperationOwnerBag) -> AndroidPostTicket<R>?,
    ) {
        if (operation == null || retainedOperation.get() !== operation || noPlatformEntryProof.get() != null) return
        val retainedTicket = ticket(operation.ownerBag) ?: return
        operation.settlementGate.withLock {
            if (retainedOperation.get() !== operation || noPlatformEntryProof.get() != null) return@withLock
            val proof = laneRuntime.proveFinalLaneNoEntryLocked(receipt, retainedTicket, operation) ?: return@withLock
            noPlatformEntryProof.compareAndSet(null, proof)
        }
    }

    private fun foldFinalListenerInstallationNoEntry(
        receipt: AndroidLaneTerminationReceipt,
        operation: OperationOccurrence<AndroidTargetListenerInstallationEvidence>?,
    ) {
        if (operation == null || targetListenerInstallationFinalLaneNoEntryProof.get() != null) return
        val boundRoot = targetListenerInstallationBoundRoot.get()
            ?.takeIf { it.operation === operation } ?: return
        val retainedTicket = boundRoot.ticket
        operation.settlementGate.withLock {
            if (targetListenerInstallationBoundRoot.get() !== boundRoot ||
                boundRoot.activatedNoPlatformEntryOutcomeLocked() != null ||
                boundRoot.exactCommittedCapability() == null ||
                targetListenerInstallationFinalLaneNoEntryProof.get() != null
            ) {
                return@withLock
            }
            val proof = laneRuntime.proveFinalLaneNoEntryLocked(receipt, retainedTicket, operation) ?: return@withLock
            targetListenerInstallationFinalLaneNoEntryProof.compareAndSet(null, proof)
        }
    }

    private fun <R : OperationEvidence> finiteOccurrence(
        identity: AndroidFiniteOperationIdentity,
        evidence: R,
        ownerBag: OperationOwnerBag
    ): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity.operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
            deadlineIdentity = identity.deadlineIdentity,
            deadlineDurationNanos = androidEnteredOperationSafetyNanos,
            initialWakeGeneration = identity.deadlineWakeGeneration,
            timeoutCause = identity.timeoutCause,
            wakeSignal = settlementSignal,
        )

    private fun <R : OperationEvidence> cleanupOccurrence(identity: Long, evidence: R, ownerBag: OperationOwnerBag): OperationOccurrence<R> =
        OperationOccurrence(
            identity = identity,
            clock = clock,
            returnCell = OperationReturnCell(evidence),
            ownerBag = ownerBag,
        ).also { occurrence ->
            check(occurrence.arbitrateTerminal(mandatoryCleanup = true) == OperationTerminalArbitration.Transferred)
        }

    private fun publishVirtualDisplayCreationReturn(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        returnedDisplay: VirtualDisplay?,
    ): Boolean = operation.settlementGate.withLock {
        if (operation.entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        val sampleNanos = clock.nowNanos()
        operation.returnCell.evidence.armOrRetireInitialResizeLocked(
            sampleNanos = sampleNanos,
            returnedDisplayPresent = returnedDisplay != null,
        )
        operation.returnCell.publishNormalLocked(sampleNanos)
    }

    private fun publishVirtualDisplayCreationThrown(
        operation: OperationOccurrence<AndroidVirtualDisplayCreationEvidence>,
        thrown: Throwable,
    ): Boolean = operation.settlementGate.withLock {
        if (operation.entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        operation.returnCell.evidence.retireInitialResizeLocked()
        when (thrown) {
            is Exception -> operation.publishThrownReturn(thrown)
            is OutOfMemoryError -> {
                check(operation.returnCell.evidence.directCreateOutOfMemoryError === thrown)
                operation.returnCell.publishThrownLocked(clock.nowNanos(), thrown)
            }

            else -> operation.publishDirectFatalReturnLocked(thrown)
        }
    }

    private fun matchesProducerFact(
        fact: TargetProducerApplicationFact,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidSurfacePort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.operationKind == port.operationKind &&
            fact.provenance === port.provenance

    private fun matchesDetachFact(
        fact: TargetProducerDetachReceipt,
        operation: OperationOccurrence<*>,
        target: CurrentTarget,
        port: TargetPorts.AndroidDetachPort,
    ): Boolean = operation.identity == port.operationIdentity &&
            fact.targetGeneration == target.generation &&
            fact.operationIdentity == operation.identity &&
            fact.detachKind == port.detachKind &&
            fact.provenance === port.provenance

    private fun <R : OperationEvidence> submitToLane(
        operation: OperationOccurrence<R>,
        postRejectionMessage: String,
        publishNormalReturn: Boolean = true,
        onReturnedThrow: (Throwable) -> Unit = {},
        onThrownSettlement: (Exception) -> Boolean = operation::publishThrownReturn,
        onTicketCreated: (AndroidPostTicket<R>) -> Unit = {},
        onPostOutcome: (AndroidPostResult) -> Unit = {},
        call: (Handler) -> Unit,
    ): Boolean {
        val ticket = laneRuntime.ticket(
            occurrence = operation,
            postRejectionMessage = postRejectionMessage,
            enteredWork = AndroidEnteredWork { handler ->
                try {
                    call(handler)
                    if (publishNormalReturn) operation.publishNormalReturn()
                } catch (failure: Exception) {
                    onReturnedThrow(failure)
                    onThrownSettlement(failure)
                } catch (raw: Throwable) {
                    try {
                        onReturnedThrow(raw)
                    } catch (_: Throwable) {
                    }
                    throw raw
                }
                signalBestEffort()
            },
        )
        onTicketCreated(ticket)
        val outcome = laneRuntime.post(ticket)
        onPostOutcome(outcome)
        return outcome == AndroidPostResult.Accepted
    }

    private fun reserveTargetListenerInstallationAdmission(): Boolean =
        targetListenerInstallationAdmissionGate.withLock {
            if (!targetListenerInstallationAdmissionOpen || targetListenerInstallationInFlight == Int.MAX_VALUE) {
                return@withLock false
            }
            targetListenerInstallationInFlight += 1
            true
        }

    private fun releaseTargetListenerInstallationAdmission() {
        targetListenerInstallationAdmissionGate.withLock {
            check(targetListenerInstallationInFlight > 0)
            targetListenerInstallationInFlight -= 1
        }
        signalBestEffort()
    }

    private fun settleTargetListenerInstallationNoPlatformEntry(
        boundRoot: AndroidTargetListenerInstallationBoundRoot,
        schedulerOutcome: AndroidPostResult,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        if (targetListenerInstallationBoundRoot.get() !== boundRoot ||
            boundRoot.exactCommittedCapability() == null || !boundRoot.hasClaimedSubmission
        ) return null
        val operation = boundRoot.operation
        return operation.settlementGate.withLock {
            boundRoot.activatedNoPlatformEntryOutcomeLocked()?.let { return@withLock it }
            if (operation.entryDisposition == OperationEntryDisposition.Unentered) {
                if (!operation.settleInertBeforeEntryLocked()) return@withLock null
            }
            when (schedulerOutcome) {
                AndroidPostResult.NotSubmitted ->
                    boundRoot.notSubmittedOutcome.takeIf { it.activateLocked() }

                AndroidPostResult.Rejected -> {
                    val failure = boundRoot.ticket.postFailureResidue as? Exception ?: return@withLock null
                    boundRoot.rejectedOutcome.takeIf { it.activateLocked(failure) }
                }

                AndroidPostResult.Accepted -> null
            }
        }
    }

    private fun recoverTargetListenerInstallationNoPlatformEntry(
        boundRoot: AndroidTargetListenerInstallationBoundRoot,
    ): AndroidTargetListenerInstallationNoPlatformEntryOutcome? {
        val operation = boundRoot.operation
        return operation.settlementGate.withLock {
            boundRoot.activatedNoPlatformEntryOutcomeLocked()?.let { return@withLock it }
            when (operation.submissionDisposition) {
                OperationSubmissionDisposition.Rejected -> {
                    if (operation.entryDisposition == OperationEntryDisposition.Unentered &&
                        !operation.settleInertBeforeEntryLocked()
                    ) return@withLock null
                    val failure = boundRoot.ticket.postFailureResidue as? Exception ?: return@withLock null
                    boundRoot.rejectedOutcome.takeIf { it.activateLocked(failure) }
                }

                OperationSubmissionDisposition.None -> {
                    val admissionClosedAndDrained = targetListenerInstallationAdmissionGate.withLock {
                        !targetListenerInstallationAdmissionOpen && targetListenerInstallationInFlight == 0
                    }
                    if (!admissionClosedAndDrained) return@withLock null
                    if (!boundRoot.hasClaimedSubmission && !boundRoot.claimSubmission()) {
                        return@withLock null
                    }
                    if (operation.entryDisposition == OperationEntryDisposition.Unentered &&
                        !operation.settleInertBeforeEntryLocked()
                    ) return@withLock null
                    boundRoot.notSubmittedOutcome.takeIf { it.activateLocked() }
                }

                OperationSubmissionDisposition.Submitting,
                OperationSubmissionDisposition.Accepted,
                OperationSubmissionDisposition.Cancelled,
                    -> null
            }
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
        }
    }
}
