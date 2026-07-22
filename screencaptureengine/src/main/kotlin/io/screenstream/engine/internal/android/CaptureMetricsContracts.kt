package io.screenstream.engine.internal.android

import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSource
import io.screenstream.engine.CaptureMetricsSubscription
import io.screenstream.engine.internal.settlement.ControlWakeLink
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.PrivateExecutorOperation
import java.util.concurrent.locks.ReentrantLock

/** Precreated exact attachment authority inspected only while its settlement gate is nested under Session. */
internal class CaptureMetricsAttachmentAccess internal constructor(
    internal val observationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val sourceProvenance: CaptureMetricsSourceProvenance,
    internal val occurrence: OperationOccurrence<CaptureMetricsAttachmentEvidence>,
    internal val deadline: DeadlineOccurrence,
    internal val wakeLink: ControlWakeLink,
) {
    internal var readinessGuardFailure: Throwable? = null

    internal val settlementGate: ReentrantLock
        get() = occurrence.settlementGate

    init {
        require(observationIdentity > 0L)
        require(deadlineIdentity > 0L)
        check(deadline.identity == deadlineIdentity)
    }
}

internal object CaptureMetricsAttachmentReceipt : OperationReceipt

internal class CaptureMetricsSubscriptionOwner : OperationReturnedOwner {
    private var fixedSubscription: CaptureMetricsSubscription? = null

    internal val subscription: CaptureMetricsSubscription
        get() = checkNotNull(fixedSubscription)
    internal val isBound: Boolean
        get() = fixedSubscription != null

    internal fun bind(subscription: CaptureMetricsSubscription): Boolean {
        val existing = fixedSubscription
        if (existing != null) return existing === subscription
        fixedSubscription = subscription
        return true
    }
}

/** The handle result only; generic submission, entry, and return remain owned by OperationOccurrence. */
internal sealed interface CaptureMetricsHandleResult {
    internal object Pending : CaptureMetricsHandleResult

    internal class Adopted internal constructor(
        internal val owner: CaptureMetricsSubscriptionOwner,
    ) : CaptureMetricsHandleResult

    internal object StructurallyAbsent : CaptureMetricsHandleResult
}

internal class CaptureMetricsAttachmentEvidence : OperationEvidence {
    internal val subscriptionOwner = CaptureMetricsSubscriptionOwner()
    internal var handleResult: CaptureMetricsHandleResult = CaptureMetricsHandleResult.Pending
        private set
    internal var handleSettlementNanos: Long = Long.MIN_VALUE
        private set

    override val receipt: OperationReceipt?
        get() = if (handleResult is CaptureMetricsHandleResult.Adopted) CaptureMetricsAttachmentReceipt else null
    override val returnedOwner: OperationReturnedOwner?
        get() = (handleResult as? CaptureMetricsHandleResult.Adopted)?.owner

    internal fun recordReturnedHandleLocked(
        subscription: CaptureMetricsSubscription?,
        settlementNanos: Long,
    ): Boolean {
        if (handleResult !== CaptureMetricsHandleResult.Pending) return false
        handleSettlementNanos = settlementNanos
        handleResult = if (subscription == null) {
            CaptureMetricsHandleResult.StructurallyAbsent
        } else {
            check(subscriptionOwner.bind(subscription))
            CaptureMetricsHandleResult.Adopted(subscriptionOwner)
        }
        return true
    }
}

internal class CaptureMetricsAttachmentOwnerBag(
    internal val source: CaptureMetricsSource,
    internal val observer: CaptureMetricsObserver,
) : OperationOwnerBag

internal sealed interface CaptureMetricsAttachmentResidue {
    val exactCause: Throwable

    internal class ReturnedFailure internal constructor(
        override val exactCause: Throwable,
    ) : CaptureMetricsAttachmentResidue

    internal class SubmissionFailure internal constructor(
        override val exactCause: Throwable,
    ) : CaptureMetricsAttachmentResidue

    internal class EndpointPoison internal constructor(
        override val exactCause: Throwable,
    ) : CaptureMetricsAttachmentResidue
}

internal sealed interface CaptureMetricsObservationOutcome {
    internal class Observing internal constructor(
        internal val owner: CaptureMetricsSubscriptionOwner,
    ) : CaptureMetricsObservationOutcome

    internal object StructurallyNoHandle : CaptureMetricsObservationOutcome

    internal class AttachmentResidue internal constructor(
        internal val residue: CaptureMetricsAttachmentResidue,
    ) : CaptureMetricsObservationOutcome
}

/** Coordinator ownership progression; it deliberately does not restate operation entry/return. */
internal sealed interface CaptureMetricsAttachmentProgress {
    internal class Prepared internal constructor(
        internal val endpointOperation: PrivateExecutorOperation<CaptureMetricsAttachmentEvidence>,
    ) : CaptureMetricsAttachmentProgress

    internal class Active internal constructor(
        internal val endpointOperation: PrivateExecutorOperation<CaptureMetricsAttachmentEvidence>,
    ) : CaptureMetricsAttachmentProgress

    internal class Settled internal constructor(
        internal val outcome: CaptureMetricsObservationOutcome,
    ) : CaptureMetricsAttachmentProgress
}

internal object CaptureMetricsRefreshReceipt : OperationReceipt

internal class CaptureMetricsRefreshEvidence : OperationEvidence {
    override val receipt: OperationReceipt = CaptureMetricsRefreshReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class CaptureMetricsRefreshOwnerBag(
    internal val attachment: BuiltInCaptureMetricsAttachment,
) : OperationOwnerBag

internal sealed interface CaptureMetricsRefreshSealReason {
    internal object CloseRequested : CaptureMetricsRefreshSealReason
    internal object NoBuiltInAttachment : CaptureMetricsRefreshSealReason
    internal object AttachmentFailed : CaptureMetricsRefreshSealReason

    internal class ExactFailure internal constructor(
        internal val cause: Throwable,
    ) : CaptureMetricsRefreshSealReason
}

internal sealed interface CaptureMetricsRefreshProgress {
    internal object ClosedBeforeAttach : CaptureMetricsRefreshProgress
    internal object Idle : CaptureMetricsRefreshProgress
    internal object Dirty : CaptureMetricsRefreshProgress

    internal class InFlight internal constructor(
        internal val occurrence: OperationOccurrence<CaptureMetricsRefreshEvidence>,
        internal val endpointOperation: PrivateExecutorOperation<CaptureMetricsRefreshEvidence>,
    ) : CaptureMetricsRefreshProgress

    internal class Sealed internal constructor(
        internal val reason: CaptureMetricsRefreshSealReason,
    ) : CaptureMetricsRefreshProgress
}

internal class CaptureMetricsCloseReceipt internal constructor() : OperationReceipt

internal class CaptureMetricsCloseEvidence(
    internal val exactReceipt: CaptureMetricsCloseReceipt,
) : OperationEvidence {
    override val receipt: OperationReceipt = exactReceipt
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class CaptureMetricsCloseOwnerBag(
    internal val subscriptionOwner: CaptureMetricsSubscriptionOwner,
) : OperationOwnerBag

internal sealed interface CaptureMetricsCloseProgress {
    internal object Open : CaptureMetricsCloseProgress
    internal object Requested : CaptureMetricsCloseProgress

    internal class InFlight internal constructor(
        internal val occurrence: OperationOccurrence<CaptureMetricsCloseEvidence>,
        internal val endpointOperation: PrivateExecutorOperation<CaptureMetricsCloseEvidence>,
    ) : CaptureMetricsCloseProgress

    internal class Normal internal constructor(
        internal val receipt: CaptureMetricsCloseReceipt,
    ) : CaptureMetricsCloseProgress

    internal class ReturnedFailure internal constructor(
        internal val exactCause: Throwable,
    ) : CaptureMetricsCloseProgress

    internal class PoisonSubmissionFailure internal constructor(
        internal val exactCause: Throwable,
    ) : CaptureMetricsCloseProgress
}

/** Exact owner retained until its one physical close outcome becomes part of whole Metrics termination. */
internal sealed interface CaptureMetricsCloseObligation {
    val owner: CaptureMetricsSubscriptionOwner

    internal class AdoptedHandle internal constructor(
        override val owner: CaptureMetricsSubscriptionOwner,
    ) : CaptureMetricsCloseObligation

    internal class RetainedAfterAttachmentFailure internal constructor(
        override val owner: CaptureMetricsSubscriptionOwner,
        internal val attachmentResidue: CaptureMetricsAttachmentResidue,
    ) : CaptureMetricsCloseObligation
}

/** Immutable closed observation result consumed by R3 beside, never instead of, endpoint termination. */
internal sealed interface CaptureMetricsObservationSettlement {
    internal class ExactCloseReceipt internal constructor(
        internal val obligation: CaptureMetricsCloseObligation,
        internal val receipt: CaptureMetricsCloseReceipt,
    ) : CaptureMetricsObservationSettlement

    internal object StructurallyNoHandle : CaptureMetricsObservationSettlement

    internal class ExactAttachmentResidue internal constructor(
        internal val residue: CaptureMetricsAttachmentResidue,
    ) : CaptureMetricsObservationSettlement

    internal class ReturnedCloseFailureResidue internal constructor(
        internal val obligation: CaptureMetricsCloseObligation,
        internal val exactCause: Throwable,
    ) : CaptureMetricsObservationSettlement

    internal class PoisonSubmissionResidue internal constructor(
        internal val obligation: CaptureMetricsCloseObligation,
        internal val exactCause: Throwable,
    ) : CaptureMetricsObservationSettlement
}
