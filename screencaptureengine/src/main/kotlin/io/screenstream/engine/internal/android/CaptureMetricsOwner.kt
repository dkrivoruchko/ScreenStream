package io.screenstream.engine.internal.android

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.MetricsEndpointShutdownAction
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.ControlWakeSubmissionDisposition
import io.screenstream.engine.internal.settlement.ControlWakeThrowableDisposition
import io.screenstream.engine.internal.settlement.DeadlineArmResult
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.PrivateExecutorTerminationReceipt
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock

private const val firstMetricsReadinessNanos: Long = 5_000_000_000L

internal fun interface BuiltInCaptureMetricsSink {
    fun publish(metrics: CaptureMetrics?, displayAssociation: CaptureMetricsDisplayAssociation?)
}

internal class CaptureMetricsOwner(
    applicationContext: Context,
    configuredSource: CaptureMetricsSource?,
    private val ingressPort: CaptureMetricsIngressPort,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    attachmentIdentity: Long,
    readinessDeadlineIdentity: Long,
    readinessWakeIdentity: Long,
    readinessTimeoutCause: Throwable,
    closeOperationIdentity: Long,
) {
    internal val cleanupShutdownAction: MetricsEndpointShutdownAction = MetricsEndpointShutdownAction(this)
    init {
        require(attachmentIdentity > 0L)
        require(readinessDeadlineIdentity > 0L)
        require(readinessWakeIdentity > 0L)
        require(closeOperationIdentity > 0L)
    }

    private val source: CaptureMetricsSource =
        configuredSource ?: BuiltInCaptureMetricsDefinition(applicationContext)
    private val sourceProvenance: CaptureMetricsSourceProvenance = when (val fixedSource = source) {
        is BuiltInCaptureMetricsDefinition -> if (fixedSource.fixedDisplay == null) {
            CaptureMetricsSourceProvenance.BuiltInDefaultDisplay
        } else {
            CaptureMetricsSourceProvenance.BuiltInFixedDisplay
        }

        else -> CaptureMetricsSourceProvenance.Custom
    }
    private val sequenceExhaustionCause = IllegalStateException("Capture metrics ingress sequence exhausted")
    private val summary = CaptureMetricsIngressSummary(
        source = source,
        observationIdentity = attachmentIdentity,
        sequenceExhaustionCause = sequenceExhaustionCause,
    )
    private val readinessFact = CaptureMetricsReadinessMechanicalFact(
        source = source,
        observationIdentity = attachmentIdentity,
        deadlineIdentity = readinessDeadlineIdentity,
    )
    private val readinessDeadlineGuardCause =
        IllegalStateException("Capture metrics readiness deadline could not be armed")
    private val observer: CaptureMetricsObserver = MetricsObserver()
    private val attachmentEvidence = CaptureMetricsAttachmentEvidence()
    private val closeEvidence = CaptureMetricsCloseEvidence()
    private val closeOwnerBag = CaptureMetricsCloseOwnerBag(attachmentEvidence.subscriptionOwner)
    private val builtInSink = BuiltInCaptureMetricsSink { metrics, displayAssociation ->
        publishMetricsIngress(metrics, displayAssociation)
    }
    private val builtInAttachment: BuiltInCaptureMetricsAttachment? =
        (source as? BuiltInCaptureMetricsDefinition)?.let { definition ->
            BuiltInCaptureMetricsAttachment(
                definition = definition,
                observer = observer,
                sink = builtInSink,
                settlementSignal = settlementSignal,
                subscriptionOwner = attachmentEvidence.subscriptionOwner,
            )
        }
    private val endpoint = PrivateExecutorRuntime(
        threadName = "ScreenCaptureEngine-Metrics",
        settlementSignal = settlementSignal,
    )
    private val attachmentOperation: OperationOccurrence<CaptureMetricsAttachmentEvidence>
    private val readinessDeadline: DeadlineOccurrence
    private val closeOperation: OperationOccurrence<CaptureMetricsCloseEvidence>
    private val attachmentEndpointOperation: PrivateExecutorOperation<CaptureMetricsAttachmentEvidence>

    private var terminalClaimed = false
    private var attachmentEndpointOperationReleased = false
    private var refreshEndpointOperation: PrivateExecutorOperation<CaptureMetricsRefreshEvidence>? = null
    private var refreshEndpointOperationReleased = false
    private var closeEndpointOperation: PrivateExecutorOperation<CaptureMetricsCloseEvidence>? = null
    private var closeEndpointOperationReleased = false
    private var closeRequested = false
    private var closeSubmitted = false
    private var closeSettled = false
    private var readinessGuardFailed = false
    private var attachmentSubmissionResult: PrivateExecutorSubmissionResult? = null
    private var ingressEarliestPositive: CaptureMetricsSampleIngressFact? = null
    private var ingressFirstPostPositiveUnavailable: CaptureMetricsSampleIngressFact? = null
    private var ingressLatestSample: CaptureMetricsSampleIngressFact? = null
    private var ingressFirstTerminal: CaptureMetricsTerminalIngressFact? = null

    init {
        attachmentOperation = OperationOccurrence(
            identity = attachmentIdentity,
            clock = clock,
            returnCell = OperationReturnCell(attachmentEvidence),
            ownerBag = CaptureMetricsAttachmentOwnerBag(source, observer),
        )
        readinessDeadline = DeadlineOccurrence(
            identity = readinessDeadlineIdentity,
            boundOccurrenceIdentity = attachmentIdentity,
            durationNanos = firstMetricsReadinessNanos,
            initialWakeGeneration = readinessWakeIdentity,
            timeoutCause = readinessTimeoutCause,
            settlementGate = attachmentOperation.settlementGate,
            clock = clock,
            signal = settlementSignal,
        )
        closeOperation = OperationOccurrence(
            identity = closeOperationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(closeEvidence),
            ownerBag = closeOwnerBag,
        )
        attachmentEndpointOperation = endpoint.operation(
            occurrence = attachmentOperation,
            enteredWork = Runnable { performAttachment() },
        )
    }

    internal fun providerCauseLocked(): Throwable? = summary.terminalCause

    internal val observedFatal: Throwable?
        get() = endpoint.observedFatal

    internal val isEndpointPoisoned: Boolean
        get() = endpoint.isPoisoned

    internal val isEndpointTerminated: Boolean
        get() = endpointTerminationReceipt != null

    internal val endpointTerminationReceipt: PrivateExecutorTerminationReceipt?
        get() = endpoint.terminationReceipt

    internal val endpointStartupFailure: Throwable?
        get() = endpoint.observedStartupFailure

    internal fun prestartEndpoint(): PrivateExecutorStartupDisposition = endpoint.prestart()

    internal val readinessWakeLink: ControlWakeLink
        get() = readinessDeadline.controlWakeLink

    internal val refreshFailure: Throwable?
        get() {
            val occurrence = refreshEndpointOperation?.occurrence ?: return null
            return occurrence.settlementGate.withLock {
                occurrence.returnCell.throwable
            }
        }

    internal val closeFailure: Throwable?
        get() = closeOperation.settlementGate.withLock {
            closeOperation.returnCell.throwable
        }

    internal fun attach(): PrivateExecutorSubmissionResult {
        val result = endpoint.submit(attachmentEndpointOperation)
        attachmentOperation.settlementGate.withLock {
            if (attachmentSubmissionResult == null) attachmentSubmissionResult = result
        }
        signalBestEffort()
        return result
    }

    internal fun claimReadinessFoldLocked(): CaptureMetricsReadinessMechanicalFact? {
        return attachmentOperation.settlementGate.withLock attachmentLock@{
            if (readinessFact.isClaimed) return@attachmentLock null

            var arbitrationNanos = Long.MIN_VALUE
            val outcome = when {
                attachmentOperation.submissionAmbiguousFatal != null ->
                    CaptureMetricsReadinessOutcome.AttachmentSubmissionDirectFatal

                attachmentSubmissionResult == null -> null

                attachmentSubmissionResult == PrivateExecutorSubmissionResult.NotSubmitted ->
                    CaptureMetricsReadinessOutcome.AttachmentSubmissionRejected

                attachmentSubmissionResult == PrivateExecutorSubmissionResult.SubmissionFailed ||
                        attachmentSubmissionResult == PrivateExecutorSubmissionResult.EntryWon ->
                    CaptureMetricsReadinessOutcome.AttachmentSubmissionException

                readinessGuardFailed ->
                    CaptureMetricsReadinessOutcome.DeadlineGuardFailed

                readinessWakeLink.submissionDisposition == ControlWakeSubmissionDisposition.Rejected &&
                        readinessWakeLink.schedulingThrowableDisposition ==
                        ControlWakeThrowableDisposition.NonfatalException ->
                    CaptureMetricsReadinessOutcome.DeadlineWakeRejected

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Thrown -> {
                    if (attachmentOperation.returnCell.throwable is Exception) {
                        CaptureMetricsReadinessOutcome.AttachmentException
                    } else {
                        CaptureMetricsReadinessOutcome.AttachmentDirectFatal
                    }
                }

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        attachmentOperation.returnCell.evidence.handleDisposition == CaptureMetricsHandleDisposition.NullReturned ->
                    CaptureMetricsReadinessOutcome.NullHandle

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        summary.sequenceExhausted ->
                    CaptureMetricsReadinessOutcome.SequenceExhausted

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        summary.terminalKind == CaptureMetricsTerminalKind.Failed ->
                    CaptureMetricsReadinessOutcome.ProviderFailedBeforeReadiness

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        summary.terminalKind == CaptureMetricsTerminalKind.Completed ->
                    CaptureMetricsReadinessOutcome.ProviderCompletedBeforeReadiness

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        summary.postValidLossBeforeActive ->
                    CaptureMetricsReadinessOutcome.AvailabilityLostBeforeActive

                attachmentOperation.returnCell.disposition == OperationReturnDisposition.Normal &&
                        hasTimelyJointReadinessEvidenceLocked() ->
                    CaptureMetricsReadinessOutcome.Timely

                readinessDeadline.disposition == DeadlineDisposition.Armed -> {
                    arbitrationNanos = clock.nowNanos()
                    if (arbitrationNanos >= readinessDeadline.deadlineNanos) {
                        CaptureMetricsReadinessOutcome.Expired
                    } else {
                        null
                    }
                }

                readinessDeadline.disposition == DeadlineDisposition.Expired ->
                    CaptureMetricsReadinessOutcome.Expired

                else -> null
            } ?: return@attachmentLock null

            val deadlineRelation = when (outcome) {
                CaptureMetricsReadinessOutcome.Timely -> {
                    readinessDeadline.retireLocked()
                    check(summary.commitJointReadinessLocked())
                    CaptureMetricsReadinessDeadlineRelation.JointEvidenceTimely
                }

                CaptureMetricsReadinessOutcome.Expired -> {
                    readinessDeadline.expireLocked()
                    CaptureMetricsReadinessDeadlineRelation.Expired
                }

                CaptureMetricsReadinessOutcome.DeadlineGuardFailed ->
                    CaptureMetricsReadinessDeadlineRelation.Unarmed

                else -> {
                    val relation = if (readinessDeadline.disposition == DeadlineDisposition.Unarmed) {
                        CaptureMetricsReadinessDeadlineRelation.Unarmed
                    } else {
                        CaptureMetricsReadinessDeadlineRelation.RetiredWithoutJointReadiness
                    }
                    readinessDeadline.retireLocked()
                    relation
                }
            }

            val evidence = attachmentOperation.returnCell.evidence
            val adoptedSubscriptionOwner = if (evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted) {
                evidence.subscriptionOwner
            } else {
                null
            }
            val cause = when (outcome) {
                CaptureMetricsReadinessOutcome.Expired -> readinessDeadline.timeoutCause
                CaptureMetricsReadinessOutcome.DeadlineGuardFailed -> readinessDeadlineGuardCause
                CaptureMetricsReadinessOutcome.DeadlineWakeRejected -> readinessWakeLink.schedulingFailure
                CaptureMetricsReadinessOutcome.AttachmentSubmissionException ->
                    attachmentOperation.submissionFailure

                CaptureMetricsReadinessOutcome.AttachmentSubmissionDirectFatal ->
                    attachmentOperation.submissionAmbiguousFatal

                CaptureMetricsReadinessOutcome.AttachmentException,
                CaptureMetricsReadinessOutcome.AttachmentDirectFatal,
                    -> attachmentOperation.returnCell.throwable

                CaptureMetricsReadinessOutcome.SequenceExhausted,
                CaptureMetricsReadinessOutcome.ProviderFailedBeforeReadiness,
                    -> summary.terminalCause

                CaptureMetricsReadinessOutcome.Timely,
                CaptureMetricsReadinessOutcome.ProviderCompletedBeforeReadiness,
                CaptureMetricsReadinessOutcome.AvailabilityLostBeforeActive,
                CaptureMetricsReadinessOutcome.AttachmentSubmissionRejected,
                CaptureMetricsReadinessOutcome.NullHandle,
                    -> null
            }
            check(
                readinessFact.commitLocked(
                    outcome = outcome,
                    cause = cause,
                    ingressSequence = summary.earliestPositiveSequence,
                    sampleNanos = summary.earliestPositiveSampleNanos,
                    metrics = summary.earliestPositiveMetrics,
                    display = summary.earliestPositiveDisplay,
                    displayEpoch = summary.earliestPositiveDisplayEpoch,
                    deadlineStartNanos = readinessDeadline.startNanos,
                    deadlineNanos = readinessDeadline.deadlineNanos,
                    deadlineRelation = deadlineRelation,
                    arbitrationNanos = arbitrationNanos,
                    attachmentSubmissionResult = attachmentSubmissionResult,
                    attachmentSubmissionDisposition = attachmentOperation.submissionDisposition,
                    attachmentSubmissionFailure = attachmentOperation.submissionFailure,
                    attachmentSubmissionAmbiguousFatal = attachmentOperation.submissionAmbiguousFatal,
                    attachmentEntryDisposition = attachmentOperation.entryDisposition,
                    attachmentReturnDisposition = attachmentOperation.returnCell.disposition,
                    handleDisposition = evidence.handleDisposition,
                    handleSettlementNanos = evidence.handleSettlementNanos,
                    subscriptionOwner = adoptedSubscriptionOwner,
                    terminalKind = summary.terminalKind,
                    terminalCause = summary.terminalCause,
                    terminalSequence = summary.terminalSequence,
                    terminalPhase = summary.terminalPhase,
                ),
            )
            readinessFact
        }
    }

    internal fun commitFirstActiveLocked(): Boolean {
        return summary.commitFirstActiveLocked()
    }

    internal fun claimTerminalLocked(): CaptureMetricsTerminalArbitration {
        if (terminalClaimed) return CaptureMetricsTerminalArbitration.None
        val phase = summary.terminalPhase ?: return CaptureMetricsTerminalArbitration.None
        val kind = summary.terminalKind ?: return CaptureMetricsTerminalArbitration.None
        terminalClaimed = true
        return when (kind) {
            CaptureMetricsTerminalKind.Completed -> when (phase) {
                CaptureMetricsTerminalPhase.BeforeJointReadiness ->
                    CaptureMetricsTerminalArbitration.CompletedBeforeReadiness

                CaptureMetricsTerminalPhase.AfterJointReadiness ->
                    CaptureMetricsTerminalArbitration.CompletedAfterReadiness
            }

            CaptureMetricsTerminalKind.Failed -> when (phase) {
                CaptureMetricsTerminalPhase.BeforeJointReadiness ->
                    CaptureMetricsTerminalArbitration.FailedBeforeReadiness

                CaptureMetricsTerminalPhase.AfterJointReadiness ->
                    CaptureMetricsTerminalArbitration.FailedAfterReadiness
            }
        }
    }

    internal fun submitPendingRefresh(operationIdentity: Long): PrivateExecutorSubmissionResult {
        require(operationIdentity > 0L)
        val attachment = builtInAttachment ?: return PrivateExecutorSubmissionResult.NotSubmitted
        releaseReturnedOperation()
        if (endpoint.hasUnsettledOperation || refreshEndpointOperation != null || !attachment.hasPendingRefresh || closeRequested) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        val operation = OperationOccurrence(
            identity = operationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(CaptureMetricsRefreshEvidence()),
            ownerBag = CaptureMetricsRefreshOwnerBag(attachment),
        )
        val endpointOperation = endpoint.operation(
            occurrence = operation,
            enteredWork = Runnable {
                attachment.performPendingRefresh()
                operation.publishNormalReturn()
            },
        )
        refreshEndpointOperation = endpointOperation
        refreshEndpointOperationReleased = false
        return endpoint.submit(endpointOperation)
    }

    internal fun requestCloseLocked(): Boolean {
        if (closeRequested) return false
        closeRequested = true
        summary.closeIngressLocked()
        return true
    }

    internal fun applyCloseRequestEffectsUnlocked(requested: Boolean) {
        if (requested) {
            builtInAttachment?.fenceCallbacks()
            signalBestEffort()
        }
    }

    internal fun submitPendingClose(): PrivateExecutorSubmissionResult {
        releaseReturnedOperation()
        if (!closeRequested || closeSubmitted || endpoint.hasUnsettledOperation) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val subscriptionOwner = attachmentOperation.settlementGate.withLock {
            val evidence = attachmentOperation.returnCell.evidence
            when {
                evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted -> evidence.subscriptionOwner
                builtInAttachment?.hasCloseObligation == true && evidence.subscriptionOwner.isBound ->
                    evidence.subscriptionOwner

                else -> null
            }
        } ?: return PrivateExecutorSubmissionResult.NotSubmitted

        val endpointOperation = endpoint.operation(
            occurrence = closeOperation,
            enteredWork = Runnable {
                subscriptionOwner.subscription.close()
                closeOperation.publishNormalReturn()
            },
        )
        closeEndpointOperation = endpointOperation
        closeEndpointOperationReleased = false
        val result = endpoint.submit(endpointOperation)
        if (result != PrivateExecutorSubmissionResult.NotSubmitted) closeSubmitted = true
        return result
    }

    internal fun requestEndpointShutdown(): Boolean {
        if (!prepareEndpointShutdown()) return false
        return endpoint.requestShutdown()
    }

    internal fun prepareEndpointShutdown(): Boolean {
        submitPendingClose()
        releaseReturnedOperation()
        if (endpoint.isPoisoned) return true
        if (endpoint.hasUnsettledOperation && !endpoint.isPoisoned) return false
        val adoptedHandle = attachmentOperation.settlementGate.withLock {
            val evidence = attachmentOperation.returnCell.evidence
            evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted ||
                    builtInAttachment?.hasCloseObligation == true
        }
        if (adoptedHandle && !closeSettled) return false
        return true
    }

    internal val isEndpointShutdownReady: Boolean
        get() {
            if (endpoint.isPoisoned) return true
            if (endpoint.hasUnsettledOperation) return false
            val adoptedHandle = attachmentOperation.settlementGate.withLock {
                val evidence = attachmentOperation.returnCell.evidence
                evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted ||
                        builtInAttachment?.hasCloseObligation == true
            }
            return !adoptedHandle || closeSettled
        }

    private fun performAttachment() {
        val armed = attachmentOperation.settlementGate.withLock {
            when (readinessDeadline.armLocked(clock.nowNanos())) {
                DeadlineArmResult.Armed -> true
                DeadlineArmResult.InvalidClockOrOverflow -> {
                    readinessGuardFailed = true
                    false
                }

                DeadlineArmResult.AlreadySettled -> false
            }
        }
        if (!armed) {
            attachmentOperation.publishThrownReturn(readinessDeadlineGuardCause)
            signalBestEffort()
            return
        }
        signalBestEffort()

        val returnedSubscription: CaptureMetricsSubscription? = when (val selectedSource = source) {
            is BuiltInCaptureMetricsDefinition -> checkNotNull(builtInAttachment).attachOnMetricsLane()
            else -> selectedSource.subscribe(observer)
        }
        attachmentOperation.settlementGate.withLock {
            val handleSettlementNanos = clock.nowNanos()
            check(
                attachmentOperation.returnCell.evidence.recordReturnedHandleLocked(
                    subscription = returnedSubscription,
                    settlementNanos = handleSettlementNanos,
                ),
            )
            check(attachmentOperation.returnCell.publishNormalLocked(handleSettlementNanos))
        }
    }

    private fun hasTimelyJointReadinessEvidenceLocked(): Boolean {
        val evidence = attachmentOperation.returnCell.evidence
        return readinessDeadline.disposition == DeadlineDisposition.Armed &&
                evidence.handleDisposition == CaptureMetricsHandleDisposition.Adopted &&
                summary.earliestPositiveMetrics != null &&
                summary.earliestPositiveSequence > 0L &&
                summary.earliestPositiveSampleNanos >= 0L &&
                summary.earliestPositiveSampleNanos < readinessDeadline.deadlineNanos &&
                evidence.handleSettlementNanos >= 0L &&
                evidence.handleSettlementNanos < readinessDeadline.deadlineNanos
    }

    private fun releaseReturnedOperation() {
        if (!attachmentEndpointOperationReleased && endpoint.releaseSettledOperation(attachmentEndpointOperation)) {
            attachmentEndpointOperationReleased = true
        }
        val currentRefresh = refreshEndpointOperation
        if (currentRefresh != null && !refreshEndpointOperationReleased && endpoint.releaseSettledOperation(currentRefresh)) {
            refreshEndpointOperationReleased = true
            if (currentRefresh.occurrence.returnCell.disposition != OperationReturnDisposition.Thrown) {
                refreshEndpointOperation = null
            }
        }
        val currentClose = closeEndpointOperation
        if (currentClose != null && !closeEndpointOperationReleased && endpoint.releaseSettledOperation(currentClose)) {
            closeEndpointOperationReleased = true
            closeSettled = true
        }
    }

    private inner class MetricsObserver : CaptureMetricsObserver {
        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            publishMetricsIngress(metrics, displayAssociation = null)
        }

        override fun onComplete() {
            val result = publishTerminalIngress(CaptureMetricsTerminalKind.Completed, null)
            signalIngressResult(result)
        }

        override fun onFailure(cause: Throwable) {
            val result = publishTerminalIngress(CaptureMetricsTerminalKind.Failed, cause)
            signalIngressResult(result)
        }
    }

    private fun publishMetricsIngress(
        metrics: CaptureMetrics?,
        displayAssociation: CaptureMetricsDisplayAssociation?,
    ) {
        val sampleFact = CaptureMetricsSampleIngressFact(
            observationIdentity = summary.observationIdentity,
            sourceProvenance = sourceProvenance,
            metrics = metrics,
            displayAssociation = displayAssociation,
        )
        val exhaustionFact = CaptureMetricsTerminalIngressFact(
            observationIdentity = summary.observationIdentity,
            kind = CaptureMetricsTerminalKind.Failed,
            cause = sequenceExhaustionCause,
        )
        val cumulativeFact = CaptureMetricsIngressSummaryFact(summary.observationIdentity)
        val localResult = attachmentOperation.settlementGate.withLock {
            val sequence = summary.nextSequence
            val sampledAtNanos = clock.nowNanos()
            val result = summary.publishMetricsLocked(
                metrics = metrics,
                sampleNanos = sampledAtNanos,
                display = null,
                displayEpoch = displayAssociation?.validityEpoch ?: 0L,
            )
            when (result) {
                CaptureMetricsIngressPublishResult.Published,
                CaptureMetricsIngressPublishResult.Duplicate,
                    -> {
                        sampleFact.fix(sequence, sampledAtNanos)
                        if (sampleFact.isAvailable && ingressEarliestPositive == null) {
                            ingressEarliestPositive = sampleFact
                        } else if (!sampleFact.isAvailable &&
                            ingressEarliestPositive != null &&
                            ingressFirstPostPositiveUnavailable == null
                        ) {
                            ingressFirstPostPositiveUnavailable = sampleFact
                        }
                        ingressLatestSample = sampleFact
                        cumulativeFact.fix(
                            lastSequence = sequence,
                            earliestPositive = ingressEarliestPositive,
                            firstPostPositiveUnavailable = ingressFirstPostPositiveUnavailable,
                            latestSample = ingressLatestSample,
                            firstTerminal = ingressFirstTerminal,
                        )
                    }

                CaptureMetricsIngressPublishResult.SequenceExhausted -> {
                    exhaustionFact.fix(
                        sequence = Long.MAX_VALUE,
                        observedAtNanos = sampledAtNanos,
                        phase = checkNotNull(summary.terminalPhase),
                    )
                    ingressFirstTerminal = exhaustionFact
                    cumulativeFact.fix(
                        lastSequence = Long.MAX_VALUE,
                        earliestPositive = ingressEarliestPositive,
                        firstPostPositiveUnavailable = ingressFirstPostPositiveUnavailable,
                        latestSample = ingressLatestSample,
                        firstTerminal = exhaustionFact,
                    )
                }

                CaptureMetricsIngressPublishResult.Closed -> Unit
            }
            result
        }
        val result = when (localResult) {
            CaptureMetricsIngressPublishResult.Published,
            CaptureMetricsIngressPublishResult.Duplicate,
                -> ingressPort.publishMetricsSummary(cumulativeFact)

            CaptureMetricsIngressPublishResult.Closed -> CaptureMetricsIngressResult.Closed
            CaptureMetricsIngressPublishResult.SequenceExhausted -> {
                ingressPort.publishMetricsSummary(cumulativeFact)
                CaptureMetricsIngressResult.SequenceExhausted
            }
        }
        signalIngressResult(result)
    }

    private fun publishTerminalIngress(
        kind: CaptureMetricsTerminalKind,
        cause: Throwable?,
    ): CaptureMetricsIngressResult {
        val terminalFact = CaptureMetricsTerminalIngressFact(
            observationIdentity = summary.observationIdentity,
            kind = kind,
            cause = cause,
        )
        val exhaustionFact = CaptureMetricsTerminalIngressFact(
            observationIdentity = summary.observationIdentity,
            kind = CaptureMetricsTerminalKind.Failed,
            cause = sequenceExhaustionCause,
        )
        val cumulativeFact = CaptureMetricsIngressSummaryFact(summary.observationIdentity)
        val localResult = attachmentOperation.settlementGate.withLock {
            val sequence = summary.nextSequence
            val observedAtNanos = clock.nowNanos()
            val result = summary.publishTerminalLocked(kind, cause)
            if (result == CaptureMetricsIngressPublishResult.Published) {
                terminalFact.fix(
                    sequence = sequence,
                    observedAtNanos = observedAtNanos,
                    phase = checkNotNull(summary.terminalPhase),
                )
                ingressFirstTerminal = terminalFact
                cumulativeFact.fix(
                    lastSequence = sequence,
                    earliestPositive = ingressEarliestPositive,
                    firstPostPositiveUnavailable = ingressFirstPostPositiveUnavailable,
                    latestSample = ingressLatestSample,
                    firstTerminal = terminalFact,
                )
            } else if (result == CaptureMetricsIngressPublishResult.SequenceExhausted) {
                exhaustionFact.fix(
                    sequence = Long.MAX_VALUE,
                    observedAtNanos = observedAtNanos,
                    phase = checkNotNull(summary.terminalPhase),
                )
                ingressFirstTerminal = exhaustionFact
                cumulativeFact.fix(
                    lastSequence = Long.MAX_VALUE,
                    earliestPositive = ingressEarliestPositive,
                    firstPostPositiveUnavailable = ingressFirstPostPositiveUnavailable,
                    latestSample = ingressLatestSample,
                    firstTerminal = exhaustionFact,
                )
            }
            result
        }
        return when (localResult) {
            CaptureMetricsIngressPublishResult.Published -> ingressPort.publishMetricsSummary(cumulativeFact)

            CaptureMetricsIngressPublishResult.Duplicate -> CaptureMetricsIngressResult.Duplicate
            CaptureMetricsIngressPublishResult.Closed -> CaptureMetricsIngressResult.Closed
            CaptureMetricsIngressPublishResult.SequenceExhausted -> {
                ingressPort.publishMetricsSummary(cumulativeFact)
                CaptureMetricsIngressResult.SequenceExhausted
            }
        }
    }

    private fun signalIngressResult(result: CaptureMetricsIngressResult) {
        if (result == CaptureMetricsIngressResult.Published ||
            result == CaptureMetricsIngressResult.SequenceExhausted
        ) {
            signalBestEffort()
        }
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable state is consumed by the future Control owner.
        }
    }
}

internal class BuiltInCaptureMetricsAttachment(
    private val definition: BuiltInCaptureMetricsDefinition,
    private val observer: CaptureMetricsObserver,
    private val sink: BuiltInCaptureMetricsSink,
    private val settlementSignal: SettlementSignal,
    private val subscriptionOwner: CaptureMetricsSubscriptionOwner,
) {
    private val callbacksOpen = AtomicBoolean(true)
    private val epochInvalidated = AtomicBoolean(false)
    private val refreshDirty = AtomicBoolean(true)
    private val closeCalled = AtomicBoolean(false)
    private val registrationAttempted = AtomicBoolean(false)
    private val reusableRealSizePoint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epochExhaustionCause = IllegalStateException("Capture metrics display epoch exhausted")
    private val associationExhaustionCause =
        IllegalStateException("Capture metrics display association identity exhausted")

    private var currentEpochDisplay: Display? = null
    private var currentEpochAssociation: CaptureMetricsDisplayAssociation? = null
    private var lastEpochIdentity = 0L
    private var lastAssociatedDisplay: Display? = null
    private var lastAssociationIdentity = 0L
    private var currentEpochWindowContext: Context? = null
    private var currentEpochWindowManager: WindowManager? = null

    private val listener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (callbacksOpen.get() && displayId == definition.selectedDisplayId) requestRefresh()
        }
    }
    private val subscription = CaptureMetricsSubscription { closeOnMetricsLane() }

    init {
        check(subscriptionOwner.bind(subscription))
    }

    internal val hasPendingRefresh: Boolean
        get() = refreshDirty.get()

    internal val hasCloseObligation: Boolean
        get() = registrationAttempted.get()

    internal fun attachOnMetricsLane(): CaptureMetricsSubscription {
        registrationAttempted.set(true)
        definition.displayManager.registerDisplayListener(listener, mainHandler)
        performPendingRefresh()
        return subscription
    }

    internal fun performPendingRefresh() {
        if (!callbacksOpen.get() || !refreshDirty.getAndSet(false)) return
        if (epochInvalidated.getAndSet(false)) {
            val retiredAssociation = currentEpochAssociation
            retireCurrentEpoch()
            sink.publish(null, retiredAssociation)
            requestRefresh()
            return
        }

        val selectedDisplay = resolveSelectedDisplay()
        if (selectedDisplay == null || !selectedDisplay.isValid) {
            val retiredAssociation = currentEpochAssociation
            retireCurrentEpoch()
            val unavailableAssociation = retiredAssociation ?: selectedDisplay?.let(::unavailableAssociationFor)
            sink.publish(null, unavailableAssociation)
            return
        }
        if (currentEpochDisplay !== selectedDisplay) {
            retireCurrentEpoch()
            if (!installDisplayEpoch(selectedDisplay)) return
        }

        val epochDisplay = currentEpochDisplay ?: return
        val epochAssociation = checkNotNull(currentEpochAssociation)
        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid) {
            retireCurrentEpoch()
            sink.publish(null, epochAssociation)
            return
        }
        val candidate = readCompleteMetrics(epochDisplay)
        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid || epochInvalidated.get()) {
            retireCurrentEpoch()
            sink.publish(null, epochAssociation)
            requestRefresh()
            return
        }
        sink.publish(candidate, epochAssociation)
    }

    internal fun fenceCallbacks(): Boolean = callbacksOpen.compareAndSet(true, false)

    private fun closeOnMetricsLane() {
        if (!closeCalled.compareAndSet(false, true)) return
        fenceCallbacks()
        refreshDirty.set(false)
        retireCurrentEpoch()
        if (registrationAttempted.get()) {
            definition.displayManager.unregisterDisplayListener(listener)
            registrationAttempted.set(false)
        }
    }

    private fun signalBoundary(displayId: Int) {
        if (!callbacksOpen.get() || displayId != definition.selectedDisplayId) return
        epochInvalidated.set(true)
        requestRefresh()
    }

    private fun requestRefresh() {
        if (!callbacksOpen.get() || !refreshDirty.compareAndSet(false, true)) return
        signalBestEffort()
    }

    private fun resolveSelectedDisplay(): Display? =
        definition.fixedDisplay ?: definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionStillMatches(epochDisplay: Display): Boolean =
        if (definition.fixedDisplay != null) {
            epochDisplay === definition.fixedDisplay
        } else {
            definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY) === epochDisplay
        }

    private fun installDisplayEpoch(epochDisplay: Display): Boolean {
        if (lastEpochIdentity == Long.MAX_VALUE) {
            fenceCallbacks()
            observer.onFailure(epochExhaustionCause)
            return false
        }
        val associationIdentity = associationIdentityFor(epochDisplay) ?: return false
        val epochIdentity = lastEpochIdentity + 1L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                definition.applicationContext.createWindowContext(
                    epochDisplay,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    null,
                )
            } else {
                definition.applicationContext.createDisplayContext(epochDisplay)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            }
            currentEpochWindowContext = windowContext
            currentEpochWindowManager = requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
                "WindowManager must be available for the selected display"
            }
        }
        currentEpochDisplay = epochDisplay
        currentEpochAssociation = CaptureMetricsDisplayAssociation(
            displayId = epochDisplay.displayId,
            associationIdentity = associationIdentity,
            validityEpoch = epochIdentity,
        )
        lastEpochIdentity = epochIdentity
        return true
    }

    private fun unavailableAssociationFor(display: Display): CaptureMetricsDisplayAssociation? {
        val associationIdentity = associationIdentityFor(display) ?: return null
        return CaptureMetricsDisplayAssociation(
            displayId = display.displayId,
            associationIdentity = associationIdentity,
            validityEpoch = 0L,
        )
    }

    private fun associationIdentityFor(display: Display): Long? {
        if (lastAssociatedDisplay === display) return lastAssociationIdentity
        if (lastAssociationIdentity == Long.MAX_VALUE) {
            fenceCallbacks()
            observer.onFailure(associationExhaustionCause)
            return null
        }
        lastAssociatedDisplay = display
        lastAssociationIdentity += 1L
        return lastAssociationIdentity
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(epochDisplay: Display): CaptureMetrics? {
        val widthPx: Int
        val heightPx: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = checkNotNull(currentEpochWindowManager).maximumWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            val point = checkNotNull(reusableRealSizePoint)
            epochDisplay.getRealSize(point)
            widthPx = point.x
            heightPx = point.y
        }
        val densityDpi = definition.applicationContext
            .createDisplayContext(epochDisplay)
            .resources.configuration.densityDpi
        return if (widthPx > 0 && heightPx > 0 && densityDpi > 0) {
            CaptureMetrics(widthPx, heightPx, densityDpi)
        } else {
            null
        }
    }

    private fun retireCurrentEpoch() {
        currentEpochDisplay = null
        currentEpochAssociation = null
        currentEpochWindowContext = null
        currentEpochWindowManager = null
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Sticky refresh state survives a failed signal.
        }
    }
}
