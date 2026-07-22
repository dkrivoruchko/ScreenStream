package io.screenstream.engine.internal.android

import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.target.CurrentTarget
import io.screenstream.engine.internal.target.TargetListenerInstallationBindingCommittedFact
import io.screenstream.engine.internal.target.TargetListenerInstallationRequestClaim
import io.screenstream.engine.internal.target.TargetPorts
import java.util.concurrent.atomic.AtomicReference

internal const val androidEnteredOperationSafetyNanos: Long = 5_000_000_000L

internal enum class AndroidCaptureApiBand {
    Unsupported,
    Api24To31,
    Api32To33,
    Api34To37,
}

internal class AndroidCallbackProvenance(
    internal val owner: AndroidCaptureOwner,
    internal val projectionOwnerEpoch: Long,
    internal val callbackRegistrationIdentity: Long,
    internal val callbackIdentity: Long,
) {
    init {
        require(projectionOwnerEpoch > 0L)
        require(callbackRegistrationIdentity > 0L)
        require(callbackIdentity > 0L)
    }
}

internal sealed class AndroidCaptureFact(
    internal val provenance: AndroidCallbackProvenance,
    internal val callbackSequence: Long,
    internal val sampleNanos: Long,
) {
    init {
        require(callbackSequence > 0L)
    }

    internal class CapturedContentResized(
        provenance: AndroidCallbackProvenance,
        callbackSequence: Long,
        sampleNanos: Long,
        internal val widthPx: Int,
        internal val heightPx: Int,
    ) : AndroidCaptureFact(provenance, callbackSequence, sampleNanos)

    internal class CapturedContentVisibilityChanged(
        provenance: AndroidCallbackProvenance,
        callbackSequence: Long,
        sampleNanos: Long,
        internal val isVisible: Boolean,
    ) : AndroidCaptureFact(provenance, callbackSequence, sampleNanos)

    internal class CaptureEnded(
        provenance: AndroidCallbackProvenance,
        callbackSequence: Long,
        sampleNanos: Long,
    ) : AndroidCaptureFact(provenance, callbackSequence, sampleNanos)
}

internal fun interface AndroidCaptureFactSink {
    fun publish(fact: AndroidCaptureFact)
}


internal class AndroidFiniteOperationIdentity(
    internal val operationIdentity: Long,
    internal val deadlineIdentity: Long,
    internal val deadlineWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(operationIdentity > 0L)
        require(deadlineIdentity > 0L)
        require(deadlineWakeGeneration > 0L)
    }
}

internal class AndroidInitialResizeDeadlineIdentity(
    internal val deadlineIdentity: Long,
    internal val deadlineWakeGeneration: Long,
    internal val timeoutCause: Throwable,
) {
    init {
        require(deadlineIdentity > 0L)
        require(deadlineWakeGeneration > 0L)
    }
}

internal object AndroidTargetListenerInstallationReceipt : OperationReceipt

internal class AndroidTargetListenerInstallationEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerInstallationReceipt = AndroidTargetListenerInstallationReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var appliedTargetResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled? = null
        private set

    internal fun recordAppliedTargetResultLocked(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerInstalled,
    ): Boolean {
        if (appliedTargetResult != null) return false
        appliedTargetResult = result
        return true
    }
}

internal class AndroidTargetListenerInstallationOwnerBag(
    internal val target: CurrentTarget,
    internal val claim: TargetListenerInstallationRequestClaim,
) : OperationOwnerBag {
    internal val port: TargetPorts.AndroidListenerInstallationPort
        get() = claim.port

    private var fixedOperation:
        io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>? = null
    private var fixedPostTicket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>? = null
    private var fixedBinding: AndroidTargetOperationBinding? = null
    private var fixedResult: AndroidTargetPlatformResult.ListenerInstalled? = null

    internal val operation:
        io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>
        get() = checkNotNull(fixedOperation)
    internal val postTicket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>
        get() = checkNotNull(fixedPostTicket)
    internal val binding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedBinding)
    internal val result: AndroidTargetPlatformResult.ListenerInstalled
        get() = checkNotNull(fixedResult)

    internal fun bindOperation(
        operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    ): Boolean {
        if (fixedOperation != null || fixedBinding != null) return false
        val binding = AndroidTargetOperationBinding.create(port.bindingFact, operation)
        fixedOperation = operation
        fixedBinding = binding
        fixedResult = AndroidTargetPlatformResult.ListenerInstalled(binding)
        return true
    }

    internal fun bindPostTicket(ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>): Boolean {
        if (fixedPostTicket != null || ticket.occurrence !== operation) return false
        fixedPostTicket = ticket
        return true
    }
}

internal class AndroidTargetListenerInstallationUnboundClaimRetiredProof private constructor(
    internal val owner: AndroidCaptureOwner,
    internal val claim: TargetListenerInstallationRequestClaim,
) {
    init {
        check(owner.acceptsTargetListenerInstallationUnboundClaimRetiredProofCreation(claim))
    }

    internal companion object {
        internal fun create(
            owner: AndroidCaptureOwner,
            claim: TargetListenerInstallationRequestClaim,
        ): AndroidTargetListenerInstallationUnboundClaimRetiredProof =
            AndroidTargetListenerInstallationUnboundClaimRetiredProof(owner, claim)
    }
}

internal sealed class AndroidTargetListenerInstallationNoPlatformEntryOutcome(
    internal open val binding: AndroidTargetOperationBinding,
    internal open val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal open val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    internal open val schedulerOutcome: AndroidPostResult,
) {
    internal abstract fun isActivatedExactLocked(): Boolean

    internal class NotSubmitted internal constructor(
        override val binding: AndroidTargetOperationBinding,
        override val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
        override val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    ) : AndroidTargetListenerInstallationNoPlatformEntryOutcome(
        binding,
        operation,
        ticket,
        AndroidPostResult.NotSubmitted,
    ) {
        private val activated = java.util.concurrent.atomic.AtomicBoolean(false)

        internal fun activateLocked(): Boolean {
            check(operation.settlementGate.isHeldByCurrentThread)
            if (!matchesFrozenNoEntryLocked() ||
                operation.submissionDisposition !=
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.None ||
                operation.submissionFailure != null || ticket.postFailureResidue != null ||
                operation.disposition != io.screenstream.engine.internal.settlement.OperationDisposition.Cancelled
            ) {
                return false
            }
            return activated.compareAndSet(false, true)
        }

        override fun isActivatedExactLocked(): Boolean =
            activated.get() && matchesFrozenNoEntryLocked() &&
                operation.submissionDisposition ==
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.None &&
                operation.submissionFailure == null && ticket.postFailureResidue == null &&
                operation.disposition == io.screenstream.engine.internal.settlement.OperationDisposition.Cancelled
    }

    internal class Rejected internal constructor(
        override val binding: AndroidTargetOperationBinding,
        override val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
        override val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    ) : AndroidTargetListenerInstallationNoPlatformEntryOutcome(
        binding,
        operation,
        ticket,
        AndroidPostResult.Rejected,
    ) {
        private val activation = AtomicReference<Exception?>(null)

        internal val failure: Exception
            get() = checkNotNull(activation.get())

        internal fun activateLocked(failure: Exception): Boolean {
            check(operation.settlementGate.isHeldByCurrentThread)
            if (!matchesFrozenNoEntryLocked() || ticket.postFailureResidue !== failure ||
                operation.submissionDisposition !=
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.Rejected ||
                operation.submissionFailure !== failure ||
                operation.disposition !=
                io.screenstream.engine.internal.settlement.OperationDisposition.SchedulerRejected
            ) {
                return false
            }
            return activation.compareAndSet(null, failure)
        }

        override fun isActivatedExactLocked(): Boolean {
            val exactFailure = activation.get() ?: return false
            return matchesFrozenNoEntryLocked() && ticket.postFailureResidue === exactFailure &&
                operation.submissionDisposition ==
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.Rejected &&
                operation.submissionFailure === exactFailure &&
                operation.disposition ==
                io.screenstream.engine.internal.settlement.OperationDisposition.SchedulerRejected
        }
    }

    protected fun matchesFrozenNoEntryLocked(): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        return binding.operationIdentity == operation.identity && ticket.occurrence === operation &&
                ticket.operationIdentity == operation.identity &&
                ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack &&
                operation.entryDisposition ==
                io.screenstream.engine.internal.settlement.OperationEntryDisposition.Cancelled &&
                operation.returnCell.disposition ==
                io.screenstream.engine.internal.settlement.OperationReturnDisposition.Empty &&
                operation.submissionAmbiguousFatal == null
    }
}

internal class AndroidTargetListenerInstallationBoundRoot private constructor(
    internal val target: CurrentTarget,
    internal val claim: TargetListenerInstallationRequestClaim,
    internal val ownerBag: AndroidTargetListenerInstallationOwnerBag,
    internal val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    internal val notSubmittedOutcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome.NotSubmitted,
    internal val rejectedOutcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome.Rejected,
) {
    internal val binding: AndroidTargetOperationBinding = ownerBag.binding
    internal val result: AndroidTargetPlatformResult.ListenerInstalled = ownerBag.result
    private val committedCapability = AtomicReference<TargetListenerInstallationBindingCommittedFact?>(null)
    private val submissionClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        check(ownerBag.target === target)
        check(ownerBag.claim === claim)
        check(ownerBag.operation === operation)
        check(ownerBag.postTicket === ticket)
        check(ticket.occurrence === operation)
        check(binding.operationIdentity == operation.identity)
        check(notSubmittedOutcome.binding === binding && notSubmittedOutcome.operation === operation &&
            notSubmittedOutcome.ticket === ticket)
        check(rejectedOutcome.binding === binding && rejectedOutcome.operation === operation &&
            rejectedOutcome.ticket === ticket)
    }

    internal fun retainCommittedCapability(
        fact: TargetListenerInstallationBindingCommittedFact,
    ): Boolean {
        if (fact.claim !== claim || fact.binding !== binding) return false
        val current = committedCapability.get()
        return current === fact || current == null && committedCapability.compareAndSet(null, fact)
    }

    internal fun exactCommittedCapability(): TargetListenerInstallationBindingCommittedFact? {
        committedCapability.get()?.let { return it }
        val recovered = target.recoverListenerInstallationBindingCommittedFact(claim, binding) ?: return null
        return if (retainCommittedCapability(recovered)) committedCapability.get() else null
    }

    internal fun claimSubmission(): Boolean = submissionClaimed.compareAndSet(false, true)

    internal val hasClaimedSubmission: Boolean
        get() = submissionClaimed.get()

    internal fun activatedNoPlatformEntryOutcomeLocked():
        AndroidTargetListenerInstallationNoPlatformEntryOutcome? = when {
        notSubmittedOutcome.isActivatedExactLocked() -> notSubmittedOutcome
        rejectedOutcome.isActivatedExactLocked() -> rejectedOutcome
        else -> null
    }

    internal companion object {
        internal fun create(
            target: CurrentTarget,
            claim: TargetListenerInstallationRequestClaim,
            ownerBag: AndroidTargetListenerInstallationOwnerBag,
            operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
            ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
        ): AndroidTargetListenerInstallationBoundRoot {
            val binding = ownerBag.binding
            return AndroidTargetListenerInstallationBoundRoot(
                target,
                claim,
                ownerBag,
                operation,
                ticket,
                AndroidTargetListenerInstallationNoPlatformEntryOutcome.NotSubmitted(binding, operation, ticket),
                AndroidTargetListenerInstallationNoPlatformEntryOutcome.Rejected(binding, operation, ticket),
            )
        }
    }
}


internal object AndroidTargetListenerRemovalReceipt : OperationReceipt

internal sealed interface AndroidListenerSentinelPostOutcome {
    val binding: AndroidTargetOperationBinding

    class DefinitelyUnentered(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidListenerSentinelPostOutcome

    class PostExposed(
        override val binding: AndroidTargetOperationBinding,
    ) : AndroidListenerSentinelPostOutcome
}

internal class AndroidTargetListenerRemovalEvidence : OperationEvidence {
    override val receipt: AndroidTargetListenerRemovalReceipt = AndroidTargetListenerRemovalReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var listenerRemovalReturned: Boolean = false
        private set

    internal var sentinelPostOutcome: AndroidListenerSentinelPostOutcome? = null
        private set

    internal var removalReturnedTargetResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalReturned? = null
        private set

    internal var settledTargetResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalSettled? = null
        private set

    internal fun recordListenerRemovalReturnLocked(): Boolean {
        if (listenerRemovalReturned) return false
        listenerRemovalReturned = true
        return true
    }

    internal fun recordSentinelPostOutcomeLocked(outcome: AndroidListenerSentinelPostOutcome): Boolean {
        if (sentinelPostOutcome != null) return false
        sentinelPostOutcome = outcome
        return true
    }

    internal fun recordRemovalReturnedTargetResultLocked(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalReturned,
    ): Boolean {
        if (removalReturnedTargetResult != null) return false
        removalReturnedTargetResult = result
        return true
    }

    internal fun recordSettledTargetResultLocked(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerRemovalSettled,
    ): Boolean {
        if (settledTargetResult != null) return false
        settledTargetResult = result
        return true
    }
}

internal object AndroidListenerSentinelReceipt : OperationReceipt

internal class AndroidListenerSentinelEvidence : OperationEvidence {
    override val receipt: AndroidListenerSentinelReceipt = AndroidListenerSentinelReceipt
    override val returnedOwner: OperationReturnedOwner? = null

    internal var observedTargetResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved? = null
        private set

    internal fun recordObservedTargetResultLocked(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved,
    ): Boolean {
        if (observedTargetResult != null) return false
        observedTargetResult = result
        return true
    }
}

internal class AndroidTargetListenerRemovalOwnerBag(
    internal val target: CurrentTarget,
    internal val port: TargetPorts.AndroidListenerRemovalPort,
) : OperationOwnerBag {
    private var fixedRemovalBinding: AndroidTargetOperationBinding? = null
    private var fixedSentinelBinding: AndroidTargetOperationBinding? = null
    private var fixedSentinelTicket: AndroidPostTicket<AndroidListenerSentinelEvidence>? = null
    private var fixedRemovalReturnedResult: AndroidTargetPlatformResult.ListenerRemovalReturned? = null
    private var fixedRemovalSettledResult: AndroidTargetPlatformResult.ListenerRemovalSettled? = null
    private var fixedSentinelObservedResult: AndroidTargetPlatformResult.ListenerSentinelObserved? = null
    private var fixedSentinelDefinitelyUnentered: AndroidListenerSentinelPostOutcome.DefinitelyUnentered? = null
    private var fixedSentinelPostExposed: AndroidListenerSentinelPostOutcome.PostExposed? = null

    internal val removalBinding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedRemovalBinding)
    internal val sentinelBinding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedSentinelBinding)
    internal val sentinelTicket: AndroidPostTicket<AndroidListenerSentinelEvidence>
        get() = checkNotNull(fixedSentinelTicket)

    internal val removalReturnedResult: AndroidTargetPlatformResult.ListenerRemovalReturned
        get() = checkNotNull(fixedRemovalReturnedResult)
    internal val removalSettledResult: AndroidTargetPlatformResult.ListenerRemovalSettled
        get() = checkNotNull(fixedRemovalSettledResult)
    internal val sentinelObservedResult: AndroidTargetPlatformResult.ListenerSentinelObserved
        get() = checkNotNull(fixedSentinelObservedResult)
    internal val sentinelDefinitelyUnentered: AndroidListenerSentinelPostOutcome.DefinitelyUnentered
        get() = checkNotNull(fixedSentinelDefinitelyUnentered)
    internal val sentinelPostExposed: AndroidListenerSentinelPostOutcome.PostExposed
        get() = checkNotNull(fixedSentinelPostExposed)

    internal fun bindOperations(
        removalOperation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
        sentinelOperation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidListenerSentinelEvidence>,
    ): Boolean {
        if (fixedRemovalBinding != null || fixedSentinelBinding != null) return false
        val removalBinding = AndroidTargetOperationBinding.create(port.bindingFact, removalOperation)
        val sentinelBinding = AndroidTargetOperationBinding.create(port.bindingFact, sentinelOperation)
        fixedRemovalBinding = removalBinding
        fixedSentinelBinding = sentinelBinding
        fixedRemovalReturnedResult = AndroidTargetPlatformResult.ListenerRemovalReturned(removalBinding)
        fixedRemovalSettledResult = AndroidTargetPlatformResult.ListenerRemovalSettled(removalBinding)
        fixedSentinelObservedResult = AndroidTargetPlatformResult.ListenerSentinelObserved(sentinelBinding)
        fixedSentinelDefinitelyUnentered = AndroidListenerSentinelPostOutcome.DefinitelyUnentered(sentinelBinding)
        fixedSentinelPostExposed = AndroidListenerSentinelPostOutcome.PostExposed(sentinelBinding)
        return true
    }

    internal fun bindSentinelTicket(ticket: AndroidPostTicket<AndroidListenerSentinelEvidence>): Boolean {
        if (fixedSentinelTicket != null) return false
        fixedSentinelTicket = ticket
        return true
    }
}
