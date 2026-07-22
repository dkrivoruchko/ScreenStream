package io.screenstream.engine.internal.android

import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import io.screenstream.engine.internal.settlement.PrivateExecutorRuntime
import io.screenstream.engine.internal.settlement.PrivateExecutorStartupDisposition
import io.screenstream.engine.internal.settlement.PrivateExecutorSubmissionResult
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

private const val firstMetricsReadinessNanos: Long = 5_000_000_000L

/** Single physical Metrics root: occurrence ownership, attachment, refresh, close, and endpoint retirement. */
internal class CaptureMetricsMechanicsOwner(
    private val source: CaptureMetricsSource,
    sourceProvenance: CaptureMetricsSourceProvenance,
    private val ingress: CaptureMetricsIngressPort,
    private val refreshIdentities: CaptureMetricsOperationIdentityAllocator,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
    attachmentIdentity: Long,
    readinessDeadlineIdentity: Long,
    readinessWakeIdentity: Long,
    readinessTimeoutCause: Throwable,
    closeOperationIdentity: Long,
) {
    private val refreshIdentityExhaustionCause =
        IllegalStateException("Capture metrics refresh operation identity exhausted")
    private val observer: CaptureMetricsObserver = MetricsObserver()
    private val attachmentEvidence = CaptureMetricsAttachmentEvidence()
    private val closeReceipt = CaptureMetricsCloseReceipt()
    private val closeEvidence = CaptureMetricsCloseEvidence(closeReceipt)
    private val closeOwnerBag = CaptureMetricsCloseOwnerBag(attachmentEvidence.subscriptionOwner)
    private val builtInSink = BuiltInCaptureMetricsSink { metrics, displayAssociation ->
        publishMetricsIngress(metrics, displayAssociation)
    }
    private val builtInDefinition = source as? BuiltInCaptureMetricsDefinition
    private val endpointTerminationOwner = CaptureMetricsEndpointTerminationOwner(
        PrivateExecutorRuntime(
            threadName = "ScreenCaptureEngine-Metrics",
            settlementSignal = settlementSignal,
            enableCoalescedSignalCarrier = builtInDefinition != null,
        ),
    )
    private val builtInAttachment: BuiltInCaptureMetricsAttachment? =
        builtInDefinition?.let { definition ->
            BuiltInCaptureMetricsAttachment(
                definition = definition,
                observer = observer,
                sink = builtInSink,
                endpoint = checkNotNull(endpointTerminationOwner.retainedEndpoint()),
                subscriptionOwner = attachmentEvidence.subscriptionOwner,
            )
        }

    private val attachmentOperation: OperationOccurrence<CaptureMetricsAttachmentEvidence>
    private val closeOperation: OperationOccurrence<CaptureMetricsCloseEvidence>
    internal val attachmentAccess: CaptureMetricsAttachmentAccess
    private var attachmentProgress: CaptureMetricsAttachmentProgress
    private var refreshProgress: CaptureMetricsRefreshProgress = CaptureMetricsRefreshProgress.ClosedBeforeAttach
    private var closeProgress: CaptureMetricsCloseProgress = CaptureMetricsCloseProgress.Open
    private var closedObservationSettlement: CaptureMetricsObservationSettlement? = null

    /** OPEN in bit zero; every admitted callback owns one count unit in the remaining bits. */
    private val callbackGate = AtomicInteger(CALLBACK_OPEN)

    init {
        require(attachmentIdentity > 0L)
        require(readinessDeadlineIdentity > 0L)
        require(readinessWakeIdentity > 0L)
        require(closeOperationIdentity > 0L)

        attachmentOperation = OperationOccurrence(
            identity = attachmentIdentity,
            clock = clock,
            returnCell = OperationReturnCell(attachmentEvidence),
            ownerBag = CaptureMetricsAttachmentOwnerBag(source, observer),
            deadlineIdentity = readinessDeadlineIdentity,
            deadlineDurationNanos = firstMetricsReadinessNanos,
            initialWakeGeneration = readinessWakeIdentity,
            timeoutCause = readinessTimeoutCause,
            wakeSignal = settlementSignal,
            deferDeadlineArmUntilOutwardCall = true,
        )
        closeOperation = OperationOccurrence(
            identity = closeOperationIdentity,
            clock = clock,
            returnCell = OperationReturnCell(closeEvidence),
            ownerBag = closeOwnerBag,
        )
        val endpoint = checkNotNull(endpointTerminationOwner.retainedEndpoint())
        attachmentProgress = CaptureMetricsAttachmentProgress.Prepared(
            endpoint.operation(
                occurrence = attachmentOperation,
                enteredWork = Runnable { performAttachment() },
            ),
        )
        attachmentAccess = CaptureMetricsAttachmentAccess(
            observationIdentity = attachmentIdentity,
            deadlineIdentity = readinessDeadlineIdentity,
            sourceProvenance = sourceProvenance,
            occurrence = attachmentOperation,
            deadline = checkNotNull(attachmentOperation.deadlineOccurrence),
            wakeLink = checkNotNull(attachmentOperation.controlWakeLink),
        )
    }

    internal val endpointTerminationReceipt: CaptureMetricsEndpointTerminationReceipt?
        get() {
            advancePhysicalProgress()
            val termination = endpointTerminationOwner.terminationOutcome()
                as? CaptureMetricsEndpointTerminationOutcome.Terminated ?: return null
            val endpoint = endpointTerminationOwner.endpointForRelease(termination.receipt)
            if (endpoint != null && !releaseEndpointOperationRoots(endpoint)) return null
            val release = endpointTerminationOwner.releaseEndpointRoot(termination.receipt)
            return if (release is CaptureMetricsEndpointRootSettlement.Released) {
                termination.receipt
            } else {
                null
            }
        }

    internal val observationSettlement: CaptureMetricsObservationSettlement?
        get() {
            closedObservationSettlement?.let { return it }
            advancePhysicalProgress()
            val attachment = (attachmentProgress as? CaptureMetricsAttachmentProgress.Settled)?.outcome
                ?: return null
            val obligation = closeObligation(attachment)
            if (obligation == null) {
                val settled = when (attachment) {
                    CaptureMetricsObservationOutcome.StructurallyNoHandle ->
                        CaptureMetricsObservationSettlement.StructurallyNoHandle

                    is CaptureMetricsObservationOutcome.AttachmentResidue ->
                        CaptureMetricsObservationSettlement.ExactAttachmentResidue(attachment.residue)

                    is CaptureMetricsObservationOutcome.Observing -> null
                }
                closedObservationSettlement = settled
                return settled
            }
            val settled = when (val close = closeProgress) {
                is CaptureMetricsCloseProgress.Normal ->
                    CaptureMetricsObservationSettlement.ExactCloseReceipt(obligation, close.receipt)

                is CaptureMetricsCloseProgress.ReturnedFailure ->
                    CaptureMetricsObservationSettlement.ReturnedCloseFailureResidue(
                        obligation,
                        close.exactCause,
                    )

                is CaptureMetricsCloseProgress.PoisonSubmissionFailure ->
                    CaptureMetricsObservationSettlement.PoisonSubmissionResidue(
                        obligation,
                        close.exactCause,
                    )

                is CaptureMetricsCloseProgress.InFlight,
                CaptureMetricsCloseProgress.Open,
                CaptureMetricsCloseProgress.Requested,
                    -> null
            }
            closedObservationSettlement = settled
            return settled
        }

    internal fun prestartEndpoint(): PrivateExecutorStartupDisposition =
        checkNotNull(endpointTerminationOwner.retainedEndpoint()).prestart()

    internal fun attach(): PrivateExecutorSubmissionResult {
        val prepared = attachmentProgress as? CaptureMetricsAttachmentProgress.Prepared
            ?: return PrivateExecutorSubmissionResult.NotSubmitted
        if (endpointTerminationOwner.shutdownAction.state != CaptureMetricsEndpointShutdownActionState.Prepared) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val endpoint = endpointTerminationOwner.retainedEndpoint()
            ?: return PrivateExecutorSubmissionResult.NotSubmitted
        val active = CaptureMetricsAttachmentProgress.Active(prepared.endpointOperation)
        attachmentProgress = active
        val result = endpoint.submit(active.endpointOperation)
        if (result == PrivateExecutorSubmissionResult.NotSubmitted && attachmentProgress === active) {
            attachmentProgress = prepared
        }
        signalBestEffort()
        return result
    }

    internal fun pollPhysical(): CaptureMetricsIngressResult {
        advancePhysicalProgress()
        val endpoint = endpointTerminationOwner.retainedEndpoint()
        return ingress.pollMetricsPhysical(
            attachment = attachmentAccess,
            clock = clock,
            endpointFailure = endpoint?.observedStartupFailure
                ?: endpoint?.observedFatal
                ?: endpoint?.observedCoalescedSignalFailure,
            refreshFailure = refreshFailure(),
            closeFailure = closeFailure(),
        )
    }

    internal fun drivePendingWork(): PrivateExecutorSubmissionResult {
        advancePhysicalProgress()
        return if (closeProgress !is CaptureMetricsCloseProgress.Open) {
            submitPendingClose()
        } else {
            submitPendingRefresh()
        }
    }

    internal fun requestClose(): Boolean {
        if (closeProgress !is CaptureMetricsCloseProgress.Open) return false
        closeProgress = CaptureMetricsCloseProgress.Requested
        if (refreshProgress !is CaptureMetricsRefreshProgress.InFlight &&
            refreshProgress !is CaptureMetricsRefreshProgress.Sealed
        ) {
            refreshProgress = CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.CloseRequested)
        }
        closeCallbackAdmission()
        builtInAttachment?.fenceCallbacks()
        signalBestEffort()
        return true
    }

    internal fun submitPendingClose(): PrivateExecutorSubmissionResult {
        advancePhysicalProgress()
        if (closeProgress !is CaptureMetricsCloseProgress.Requested || callbackCount() != 0) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val endpoint = endpointTerminationOwner.retainedEndpoint()
            ?: return PrivateExecutorSubmissionResult.NotSubmitted
        if (endpointTerminationOwner.shutdownAction.state != CaptureMetricsEndpointShutdownActionState.Prepared ||
            endpoint.hasUnsettledOperation
        ) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        val subscriptionOwner = closeObligation()?.owner
            ?: return PrivateExecutorSubmissionResult.NotSubmitted
        exactEndpointPoisonResidue(endpoint)?.let { poison ->
            closeProgress = CaptureMetricsCloseProgress.PoisonSubmissionFailure(poison)
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        val endpointOperation = endpoint.operation(
            occurrence = closeOperation,
            enteredWork = Runnable {
                subscriptionOwner.subscription.close()
                closeOperation.publishNormalReturn()
            },
        )
        val inFlight = CaptureMetricsCloseProgress.InFlight(closeOperation, endpointOperation)
        closeProgress = inFlight
        val result = endpoint.submit(endpointOperation)
        if (result == PrivateExecutorSubmissionResult.NotSubmitted && closeProgress === inFlight) {
            closeProgress = CaptureMetricsCloseProgress.Requested
        }
        return result
    }

    internal fun requestEndpointShutdown(): CaptureMetricsEndpointShutdownActionOutcome? {
        advancePhysicalProgress()
        if (callbackCount() != 0) return null
        submitPendingClose()
        advancePhysicalProgress()
        val action = endpointTerminationOwner.shutdownAction
        if (action.state != CaptureMetricsEndpointShutdownActionState.Prepared) {
            return action.outcome ?: action.enteredOutcome
        }
        val endpoint = endpointTerminationOwner.retainedEndpoint() ?: return action.outcome
        if (!endpoint.isCoalescedSignalCarrierQuiescent) return null
        if (endpoint.hasUnsettledOperation) {
            if (!activeRootCanSettleThroughShutdown(endpoint)) return null
            return action.enter()
        }
        if (!closeObligationSettled(endpoint)) return null
        return action.enter()
    }

    private fun submitPendingRefresh(): PrivateExecutorSubmissionResult {
        val attachment = builtInAttachment ?: return PrivateExecutorSubmissionResult.NotSubmitted
        synchronizeRefreshDemand(attachment)
        if (refreshProgress !is CaptureMetricsRefreshProgress.Dirty) {
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val endpoint = endpointTerminationOwner.retainedEndpoint()
            ?: return PrivateExecutorSubmissionResult.NotSubmitted
        if (endpointTerminationOwner.shutdownAction.state != CaptureMetricsEndpointShutdownActionState.Prepared ||
            endpoint.hasUnsettledOperation || endpoint.isPoisoned
        ) {
            endpointPoison(endpoint)?.let {
                refreshProgress = CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.ExactFailure(it))
            }
            return PrivateExecutorSubmissionResult.NotSubmitted
        }

        val operationIdentity = refreshIdentities.nextRefreshIdentity() ?: run {
            refreshProgress = CaptureMetricsRefreshProgress.Sealed(
                CaptureMetricsRefreshSealReason.ExactFailure(refreshIdentityExhaustionCause),
            )
            attachment.fenceCallbacks()
            signalBestEffort()
            return PrivateExecutorSubmissionResult.NotSubmitted
        }
        val occurrence = OperationOccurrence(
            identity = operationIdentity.localOrdinal,
            clock = clock,
            returnCell = OperationReturnCell(CaptureMetricsRefreshEvidence()),
            ownerBag = CaptureMetricsRefreshOwnerBag(attachment),
        )
        val endpointOperation = endpoint.operation(
            occurrence = occurrence,
            enteredWork = Runnable {
                attachment.performPendingRefresh()
                occurrence.publishNormalReturn()
            },
        )
        val inFlight = CaptureMetricsRefreshProgress.InFlight(occurrence, endpointOperation)
        refreshProgress = inFlight
        val result = endpoint.submit(endpointOperation)
        if (result == PrivateExecutorSubmissionResult.NotSubmitted && refreshProgress === inFlight) {
            refreshProgress = CaptureMetricsRefreshProgress.Dirty
        }
        return result
    }

    private fun performAttachment() {
        val authorization = attachmentOperation.settlementGate.withLock {
            attachmentOperation.authorizeOutwardCallLocked()
        }
        signalBestEffort()
        when (authorization) {
            OperationEntryResult.Entered -> Unit
            OperationEntryResult.InvalidDeadline,
            OperationEntryResult.NotCurrent,
                -> return
        }

        val returnedSubscription: CaptureMetricsSubscription? = when (val selectedSource = source) {
            is BuiltInCaptureMetricsDefinition -> checkNotNull(builtInAttachment).attachOnMetricsLane()
            else -> selectedSource.subscribe(observer)
        }
        attachmentOperation.settlementGate.withLock {
            check(attachmentOperation.publishNormalReturnLocked())
            check(
                attachmentOperation.returnCell.evidence.recordReturnedHandleLocked(
                    subscription = returnedSubscription,
                    settlementNanos = attachmentOperation.returnCell.settlementNanos,
                ),
            )
        }
    }

    private fun advancePhysicalProgress() {
        val endpoint = endpointTerminationOwner.retainedEndpoint() ?: return
        if (endpoint.isTerminated) resolveAmbiguousOperationsAfterTermination()
        advanceAttachment(endpoint)
        advanceRefresh(endpoint)
        advanceClose(endpoint)
    }

    private fun advanceAttachment(endpoint: PrivateExecutorRuntime) {
        val progress = attachmentProgress
        if (progress is CaptureMetricsAttachmentProgress.Prepared) {
            val cancelledBeforeSubmission = attachmentOperation.settlementGate.withLock {
                attachmentOperation.entryDisposition == OperationEntryDisposition.Cancelled &&
                        attachmentOperation.submissionDisposition == OperationSubmissionDisposition.Cancelled
            }
            if (cancelledBeforeSubmission) {
                settleAttachment(CaptureMetricsObservationOutcome.StructurallyNoHandle)
            }
            return
        }
        val active = progress as? CaptureMetricsAttachmentProgress.Active ?: return
        if (!endpoint.releaseSettledOperation(active.endpointOperation)) return
        val outcome = attachmentOperation.settlementGate.withLock {
            when (attachmentOperation.returnCell.disposition) {
                OperationReturnDisposition.Normal -> when (val handle = attachmentEvidence.handleResult) {
                    is CaptureMetricsHandleResult.Adopted -> CaptureMetricsObservationOutcome.Observing(handle.owner)
                    CaptureMetricsHandleResult.StructurallyAbsent -> CaptureMetricsObservationOutcome.StructurallyNoHandle
                    CaptureMetricsHandleResult.Pending -> error("normal attachment return requires an exact handle result")
                }

                OperationReturnDisposition.Thrown -> CaptureMetricsObservationOutcome.AttachmentResidue(
                    CaptureMetricsAttachmentResidue.ReturnedFailure(
                        checkNotNull(attachmentOperation.returnCell.throwable),
                    ),
                )

                OperationReturnDisposition.Empty -> CaptureMetricsObservationOutcome.AttachmentResidue(
                    attachmentSubmissionResidue(endpoint),
                )
            }
        }
        settleAttachment(outcome)
    }

    private fun settleAttachment(outcome: CaptureMetricsObservationOutcome) {
        attachmentProgress = CaptureMetricsAttachmentProgress.Settled(outcome)
        refreshProgress = when {
            closeProgress !is CaptureMetricsCloseProgress.Open ->
                CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.CloseRequested)

            builtInAttachment == null ->
                CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.NoBuiltInAttachment)

            outcome !is CaptureMetricsObservationOutcome.Observing ->
                CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.AttachmentFailed)

            builtInAttachment.hasPendingRefresh -> CaptureMetricsRefreshProgress.Dirty
            else -> CaptureMetricsRefreshProgress.Idle
        }
    }

    private fun advanceRefresh(endpoint: PrivateExecutorRuntime) {
        val inFlight = refreshProgress as? CaptureMetricsRefreshProgress.InFlight ?: return
        if (!endpoint.releaseSettledOperation(inFlight.endpointOperation)) return
        val failure = operationFailure(inFlight.occurrence)
        refreshProgress = when {
            failure != null ->
                CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.ExactFailure(failure))

            closeProgress !is CaptureMetricsCloseProgress.Open ->
                CaptureMetricsRefreshProgress.Sealed(CaptureMetricsRefreshSealReason.CloseRequested)

            builtInAttachment?.hasPendingRefresh == true -> CaptureMetricsRefreshProgress.Dirty
            else -> CaptureMetricsRefreshProgress.Idle
        }
    }

    private fun advanceClose(endpoint: PrivateExecutorRuntime) {
        val inFlight = closeProgress as? CaptureMetricsCloseProgress.InFlight ?: return
        if (!endpoint.releaseSettledOperation(inFlight.endpointOperation)) return
        closeProgress = inFlight.occurrence.settlementGate.withLock {
            when (inFlight.occurrence.returnCell.disposition) {
                OperationReturnDisposition.Normal -> CaptureMetricsCloseProgress.Normal(closeReceipt)
                OperationReturnDisposition.Thrown -> CaptureMetricsCloseProgress.ReturnedFailure(
                    checkNotNull(inFlight.occurrence.returnCell.throwable),
                )

                OperationReturnDisposition.Empty -> CaptureMetricsCloseProgress.PoisonSubmissionFailure(
                    checkNotNull(operationFailureLocked(inFlight.occurrence)),
                )
            }
        }
    }

    private fun synchronizeRefreshDemand(attachment: BuiltInCaptureMetricsAttachment) {
        if (refreshProgress is CaptureMetricsRefreshProgress.Idle && attachment.hasPendingRefresh) {
            refreshProgress = CaptureMetricsRefreshProgress.Dirty
        }
    }

    private fun closeObligation(): CaptureMetricsCloseObligation? {
        val outcome = (attachmentProgress as? CaptureMetricsAttachmentProgress.Settled)?.outcome ?: return null
        return closeObligation(outcome)
    }

    private fun closeObligation(outcome: CaptureMetricsObservationOutcome): CaptureMetricsCloseObligation? {
        return when (outcome) {
            is CaptureMetricsObservationOutcome.Observing ->
                CaptureMetricsCloseObligation.AdoptedHandle(outcome.owner)

            is CaptureMetricsObservationOutcome.AttachmentResidue ->
                attachmentEvidence.subscriptionOwner.takeIf {
                    builtInAttachment?.hasCloseObligation == true && it.isBound
                }?.let {
                    CaptureMetricsCloseObligation.RetainedAfterAttachmentFailure(it, outcome.residue)
                }

            CaptureMetricsObservationOutcome.StructurallyNoHandle -> null
        }
    }

    private fun closeObligationSettled(endpoint: PrivateExecutorRuntime): Boolean {
        val attachment = attachmentProgress
        if (attachment is CaptureMetricsAttachmentProgress.Prepared) {
            endpointPoison(endpoint)?.let {
                attachmentProgress = CaptureMetricsAttachmentProgress.Settled(
                    CaptureMetricsObservationOutcome.AttachmentResidue(
                        CaptureMetricsAttachmentResidue.EndpointPoison(it),
                    ),
                )
            }
        }
        if (attachmentProgress !is CaptureMetricsAttachmentProgress.Settled) return false
        val obligation = closeObligation() ?: return true
        check(obligation.owner.isBound)
        return when (closeProgress) {
            is CaptureMetricsCloseProgress.Normal,
            is CaptureMetricsCloseProgress.ReturnedFailure,
            is CaptureMetricsCloseProgress.PoisonSubmissionFailure,
                -> true

            CaptureMetricsCloseProgress.Requested -> {
                endpointPoison(endpoint)?.let {
                    closeProgress = CaptureMetricsCloseProgress.PoisonSubmissionFailure(it)
                }
                closeProgress is CaptureMetricsCloseProgress.PoisonSubmissionFailure
            }

            is CaptureMetricsCloseProgress.InFlight,
            CaptureMetricsCloseProgress.Open,
                -> false
        }
    }

    private fun activeRootCanSettleThroughShutdown(endpoint: PrivateExecutorRuntime): Boolean {
        val operation = when (val attachment = attachmentProgress) {
            is CaptureMetricsAttachmentProgress.Active -> attachment.endpointOperation
            else -> when (val refresh = refreshProgress) {
                is CaptureMetricsRefreshProgress.InFlight -> refresh.endpointOperation
                else -> (closeProgress as? CaptureMetricsCloseProgress.InFlight)?.endpointOperation
            }
        } ?: return false
        if (operation.endpoint !== endpoint) return false
        return operation.occurrence.settlementGate.withLock {
            operation.occurrence.entryDisposition == OperationEntryDisposition.Unentered &&
                    operation.occurrence.returnCell.disposition == OperationReturnDisposition.Empty &&
                    endpoint.isPoisoned &&
                    operation.occurrence.submissionDisposition != OperationSubmissionDisposition.None
        }
    }

    private fun resolveAmbiguousOperationsAfterTermination() {
        attachmentOperation.resolveAmbiguousSubmissionAfterTermination()
        (refreshProgress as? CaptureMetricsRefreshProgress.InFlight)
            ?.occurrence
            ?.resolveAmbiguousSubmissionAfterTermination()
        (closeProgress as? CaptureMetricsCloseProgress.InFlight)
            ?.occurrence
            ?.resolveAmbiguousSubmissionAfterTermination()
    }

    private fun attachmentSubmissionResidue(endpoint: PrivateExecutorRuntime): CaptureMetricsAttachmentResidue {
        val exact = operationFailure(attachmentOperation)
        return if (exact != null) {
            CaptureMetricsAttachmentResidue.SubmissionFailure(exact)
        } else {
            CaptureMetricsAttachmentResidue.EndpointPoison(
                checkNotNull(endpointPoison(endpoint)) { "empty attachment return requires exact poison evidence" },
            )
        }
    }

    private fun operationFailure(operation: OperationOccurrence<*>): Throwable? =
        operation.settlementGate.withLock { operationFailureLocked(operation) }

    private fun operationFailureLocked(operation: OperationOccurrence<*>): Throwable? {
        check(operation.settlementGate.isHeldByCurrentThread)
        return operation.returnCell.throwable ?: operation.submissionFailure ?: operation.submissionAmbiguousFatal
    }

    private fun endpointPoison(endpoint: PrivateExecutorRuntime): Throwable? =
        endpoint.observedStartupFailure ?: endpoint.observedFatal ?: endpoint.observedCoalescedSignalFailure

    private fun exactEndpointPoisonResidue(endpoint: PrivateExecutorRuntime): Throwable? =
        endpointPoison(endpoint)
            ?: operationSubmissionPoison(attachmentOperation)
            ?: (refreshProgress as? CaptureMetricsRefreshProgress.InFlight)
                ?.occurrence
                ?.let(::operationSubmissionPoison)
            ?: operationSubmissionPoison(closeOperation)

    private fun operationSubmissionPoison(operation: OperationOccurrence<*>): Throwable? =
        operation.settlementGate.withLock {
            operation.submissionFailure ?: operation.submissionAmbiguousFatal
        }

    private fun refreshFailure(): Throwable? =
        ((refreshProgress as? CaptureMetricsRefreshProgress.Sealed)?.reason
            as? CaptureMetricsRefreshSealReason.ExactFailure)?.cause

    private fun closeFailure(): Throwable? = when (val close = closeProgress) {
        is CaptureMetricsCloseProgress.ReturnedFailure -> close.exactCause
        is CaptureMetricsCloseProgress.PoisonSubmissionFailure -> close.exactCause
        else -> null
    }

    private fun releaseEndpointOperationRoots(endpoint: PrivateExecutorRuntime): Boolean {
        advancePhysicalProgress()
        if (endpoint.hasUnsettledOperation) return false
        if (attachmentProgress !is CaptureMetricsAttachmentProgress.Settled) return false
        if (refreshProgress is CaptureMetricsRefreshProgress.InFlight) return false
        if (closeProgress is CaptureMetricsCloseProgress.InFlight) return false
        return true
    }

    private inner class MetricsObserver : CaptureMetricsObserver {
        override fun onMetricsChanged(metrics: CaptureMetrics?) {
            publishMetricsIngress(metrics, displayAssociation = null)
        }

        override fun onComplete() {
            publishTerminalIngress(completed = true, cause = null)
        }

        override fun onFailure(cause: Throwable) {
            publishTerminalIngress(completed = false, cause = cause)
        }
    }

    private fun publishMetricsIngress(
        metrics: CaptureMetrics?,
        displayAssociation: CaptureMetricsDisplayAssociation?,
    ) {
        if (!enterCallback()) return
        val result = try {
            ingress.publishMetricsSample(attachmentAccess, metrics, displayAssociation, clock)
        } finally {
            leaveCallback()
        }
        signalForPublishedCallback(result)
    }

    private fun publishTerminalIngress(
        completed: Boolean,
        cause: Throwable?,
    ): CaptureMetricsIngressResult {
        if (!enterCallback()) return CaptureMetricsIngressResult.Closed
        val result = try {
            if (completed) {
                ingress.publishMetricsCompleted(attachmentAccess, clock)
            } else {
                ingress.publishMetricsFailed(attachmentAccess, checkNotNull(cause), clock)
            }
        } finally {
            leaveCallback()
        }
        signalForPublishedCallback(result)
        return result
    }

    private fun enterCallback(): Boolean {
        while (true) {
            val current = callbackGate.get()
            if (current and CALLBACK_OPEN == 0) return false
            if (current > Int.MAX_VALUE - CALLBACK_COUNT_ONE) return false
            if (callbackGate.compareAndSet(current, current + CALLBACK_COUNT_ONE)) return true
        }
    }

    private fun leaveCallback() {
        while (true) {
            val current = callbackGate.get()
            check(current and CALLBACK_COUNT_MASK != 0)
            val next = current - CALLBACK_COUNT_ONE
            if (!callbackGate.compareAndSet(current, next)) continue
            if (next and CALLBACK_OPEN == 0 && next and CALLBACK_COUNT_MASK == 0) signalBestEffort()
            return
        }
    }

    private fun closeCallbackAdmission() {
        while (true) {
            val current = callbackGate.get()
            if (current and CALLBACK_OPEN == 0) return
            if (callbackGate.compareAndSet(current, current and CALLBACK_OPEN.inv())) return
        }
    }

    private fun callbackCount(): Int = callbackGate.get() ushr CALLBACK_COUNT_SHIFT

    private fun signalForPublishedCallback(result: CaptureMetricsIngressResult) {
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

    private companion object {
        private const val CALLBACK_OPEN = 1
        private const val CALLBACK_COUNT_SHIFT = 1
        private const val CALLBACK_COUNT_ONE = 1 shl CALLBACK_COUNT_SHIFT
        private const val CALLBACK_COUNT_MASK = CALLBACK_OPEN.inv()
    }
}
