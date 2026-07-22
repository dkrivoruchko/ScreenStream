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

internal class AndroidTargetListenerInstallationAdmissionCutoff internal constructor()

internal class AndroidTargetListenerInstallationBoundNeverSubmittedProof internal constructor(
    internal val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    private val exactCutoff: AndroidTargetListenerInstallationAdmissionCutoff,
) {
    private val activation = AtomicReference<TargetListenerInstallationBindingCommittedFact?>(null)

    internal val committedFact: TargetListenerInstallationBindingCommittedFact?
        get() = activation.get()

    internal fun activateLocked(
        fact: TargetListenerInstallationBindingCommittedFact,
        cutoff: AndroidTargetListenerInstallationAdmissionCutoff,
    ): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (activation.get() === fact) return true
        if (cutoff !== exactCutoff || fact.binding.operationIdentity != operation.identity || ticket.occurrence !== operation ||
            ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
            ticket.postFailureResidue != null ||
            operation.submissionDisposition !=
            io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.None ||
            operation.entryDisposition !=
            io.screenstream.engine.internal.settlement.OperationEntryDisposition.Unentered ||
            operation.returnCell.disposition !=
            io.screenstream.engine.internal.settlement.OperationReturnDisposition.Empty ||
            !operation.settleInertBeforeEntryLocked()
        ) return false
        return activation.compareAndSet(null, fact)
    }
}

internal sealed class AndroidTargetListenerInstallationNoPlatformEntryOutcome(
    internal open val binding: AndroidTargetOperationBinding,
    internal open val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal open val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    internal open val schedulerOutcome: AndroidPostResult,
) {
    internal abstract fun isActivatedExactLocked(): Boolean

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
            if (!matchesFrozenNoEntryLocked(allowReturned = true) || ticket.postFailureResidue !== failure ||
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
            return matchesFrozenNoEntryLocked(allowReturned = true) && ticket.postFailureResidue === exactFailure &&
                operation.submissionDisposition ==
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.Rejected &&
                operation.submissionFailure === exactFailure &&
                operation.disposition ==
                io.screenstream.engine.internal.settlement.OperationDisposition.SchedulerRejected
        }
    }

    internal class AcceptedInert internal constructor(
        override val binding: AndroidTargetOperationBinding,
        override val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
        override val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    ) : AndroidTargetListenerInstallationNoPlatformEntryOutcome(
        binding,
        operation,
        ticket,
        AndroidPostResult.Accepted,
    ) {
        private val activation = AtomicReference<AndroidReturnedWithoutPlatformEntryProof<AndroidTargetListenerInstallationEvidence>?>(null)

        internal fun activateLocked(
            proof: AndroidReturnedWithoutPlatformEntryProof<AndroidTargetListenerInstallationEvidence>,
        ): Boolean {
            check(operation.settlementGate.isHeldByCurrentThread)
            if (!matchesFrozenNoEntryLocked(allowReturned = true) ||
                ticket.physicalState != AndroidPostPhysicalDisposition.Returned ||
                proof.ticket !== ticket || proof.operation !== operation || !proof.activateLocked() ||
                operation.submissionDisposition !=
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.Accepted ||
                operation.disposition != io.screenstream.engine.internal.settlement.OperationDisposition.Cancelled &&
                operation.disposition != io.screenstream.engine.internal.settlement.OperationDisposition.DeadlineGuardFailed
            ) return false
            return activation.compareAndSet(null, proof)
        }

        override fun isActivatedExactLocked(): Boolean {
            val proof = activation.get() ?: return false
            return matchesFrozenNoEntryLocked(allowReturned = true) &&
                ticket.physicalState == AndroidPostPhysicalDisposition.Returned &&
                proof.ticket === ticket && proof.operation === operation &&
                operation.submissionDisposition ==
                io.screenstream.engine.internal.settlement.OperationSubmissionDisposition.Accepted &&
                (operation.disposition == io.screenstream.engine.internal.settlement.OperationDisposition.Cancelled ||
                    operation.disposition == io.screenstream.engine.internal.settlement.OperationDisposition.DeadlineGuardFailed)
        }
    }

    protected fun matchesFrozenNoEntryLocked(allowReturned: Boolean): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        return binding.operationIdentity == operation.identity && ticket.occurrence === operation &&
                ticket.operationIdentity == operation.identity &&
                (ticket.physicalState == AndroidPostPhysicalDisposition.NotOnStack ||
                        allowReturned && ticket.physicalState == AndroidPostPhysicalDisposition.Returned) &&
                operation.entryDisposition ==
                io.screenstream.engine.internal.settlement.OperationEntryDisposition.Cancelled &&
                operation.returnCell.disposition ==
                io.screenstream.engine.internal.settlement.OperationReturnDisposition.Empty
    }
}

internal class AndroidTargetListenerInstallationBoundRoot private constructor(
    internal val target: CurrentTarget,
    internal val claim: TargetListenerInstallationRequestClaim,
    internal val ownerBag: AndroidTargetListenerInstallationOwnerBag,
    internal val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
    internal val ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
    internal val admissionCutoff: AndroidTargetListenerInstallationAdmissionCutoff,
    internal val rejectedOutcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome.Rejected,
    internal val acceptedInertOutcome: AndroidTargetListenerInstallationNoPlatformEntryOutcome.AcceptedInert,
) {
    internal val binding: AndroidTargetOperationBinding = ownerBag.binding
    internal val result: AndroidTargetPlatformResult.ListenerInstalled = ownerBag.result
    private val committedCapability = AtomicReference<TargetListenerInstallationBindingCommittedFact?>(null)
    private val submissionClaimed = java.util.concurrent.atomic.AtomicBoolean(false)
    internal val boundNeverSubmittedProof =
        AndroidTargetListenerInstallationBoundNeverSubmittedProof(operation, ticket, admissionCutoff)

    init {
        check(ownerBag.target === target)
        check(ownerBag.claim === claim)
        check(ownerBag.operation === operation)
        check(ownerBag.postTicket === ticket)
        check(ticket.occurrence === operation)
        check(binding.operationIdentity == operation.identity)
        check(rejectedOutcome.binding === binding && rejectedOutcome.operation === operation &&
            rejectedOutcome.ticket === ticket)
        check(acceptedInertOutcome.binding === binding && acceptedInertOutcome.operation === operation &&
            acceptedInertOutcome.ticket === ticket)
        check(boundNeverSubmittedProof.operation === operation && boundNeverSubmittedProof.ticket === ticket)
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
        rejectedOutcome.isActivatedExactLocked() -> rejectedOutcome
        acceptedInertOutcome.isActivatedExactLocked() -> acceptedInertOutcome
        else -> null
    }

    internal companion object {
        internal fun create(
            target: CurrentTarget,
            claim: TargetListenerInstallationRequestClaim,
            ownerBag: AndroidTargetListenerInstallationOwnerBag,
            operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerInstallationEvidence>,
            ticket: AndroidPostTicket<AndroidTargetListenerInstallationEvidence>,
            admissionCutoff: AndroidTargetListenerInstallationAdmissionCutoff,
        ): AndroidTargetListenerInstallationBoundRoot {
            val binding = ownerBag.binding
            return AndroidTargetListenerInstallationBoundRoot(
                target,
                claim,
                ownerBag,
                operation,
                ticket,
                admissionCutoff,
                AndroidTargetListenerInstallationNoPlatformEntryOutcome.Rejected(binding, operation, ticket),
                AndroidTargetListenerInstallationNoPlatformEntryOutcome.AcceptedInert(binding, operation, ticket),
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
    internal var sentinelPostFailureResidue: Throwable? = null
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

    internal fun recordSentinelPostOutcomeLocked(
        outcome: AndroidListenerSentinelPostOutcome,
        exactFailureResidue: Throwable?,
    ): Boolean {
        if (sentinelPostOutcome != null) return false
        sentinelPostOutcome = outcome
        sentinelPostFailureResidue = exactFailureResidue
        return true
    }

    internal fun refineSentinelPostOutcomeToDefinitelyUnenteredLocked(
        expectedPostExposed: AndroidListenerSentinelPostOutcome.PostExposed,
        definitelyUnentered: AndroidListenerSentinelPostOutcome.DefinitelyUnentered,
        exactFailureResidue: Throwable?,
    ): Boolean {
        val current = sentinelPostOutcome
        if (current !== expectedPostExposed ||
            current.binding !== definitelyUnentered.binding ||
            sentinelPostFailureResidue !== exactFailureResidue
        ) return false
        sentinelPostOutcome = definitelyUnentered
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

internal class AndroidTargetListenerRemovalOwnerBag(
    internal val target: CurrentTarget,
    internal val operationIdentity: Long,
) : OperationOwnerBag {
    private var fixedPort: TargetPorts.AndroidListenerRemovalPort? = null
    private var fixedRemovalTicket: AndroidPostTicket<AndroidTargetListenerRemovalEvidence>? = null
    private var fixedRemovalBinding: AndroidTargetOperationBinding? = null
    private var fixedSentinelBinding: AndroidTargetOperationBinding? = null
    private var fixedSentinelTicket: AndroidListenerSentinelTicket? = null
    private var fixedRemovalReturnedResult: AndroidTargetPlatformResult.ListenerRemovalReturned? = null
    private var fixedRemovalSettledResult: AndroidTargetPlatformResult.ListenerRemovalSettled? = null
    private var fixedSentinelObservedResult: AndroidTargetPlatformResult.ListenerSentinelObserved? = null
    private var fixedSentinelDefinitelyUnentered: AndroidListenerSentinelPostOutcome.DefinitelyUnentered? = null
    private var fixedSentinelPostExposed: AndroidListenerSentinelPostOutcome.PostExposed? = null
    private val sentinelObservedApplication = AtomicReference<
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved?
    >(null)

    internal val removalBinding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedRemovalBinding)
    internal val port: TargetPorts.AndroidListenerRemovalPort
        get() = checkNotNull(fixedPort)
    internal val removalTicket: AndroidPostTicket<AndroidTargetListenerRemovalEvidence>
        get() = checkNotNull(fixedRemovalTicket)
    internal val sentinelBinding: AndroidTargetOperationBinding
        get() = checkNotNull(fixedSentinelBinding)
    internal val sentinelTicket: AndroidListenerSentinelTicket
        get() = checkNotNull(fixedSentinelTicket)
    internal val sentinelObservedApplicationResult:
        io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved?
        get() = sentinelObservedApplication.get()

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

    internal fun bindTargetPort(port: TargetPorts.AndroidListenerRemovalPort): Boolean {
        if (fixedPort != null || port.targetIdentity !== target.identity ||
            port.operationIdentity != operationIdentity
        ) return false
        fixedPort = port
        return true
    }

    internal fun bindOperations(
        removalOperation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
    ): Boolean {
        if (fixedRemovalBinding != null || fixedSentinelBinding != null) return false
        val removalBinding = AndroidTargetOperationBinding.create(port.bindingFact, removalOperation)
        val sentinelBinding = AndroidTargetOperationBinding.create(port.bindingFact, removalOperation)
        fixedRemovalBinding = removalBinding
        fixedSentinelBinding = sentinelBinding
        fixedRemovalReturnedResult = AndroidTargetPlatformResult.ListenerRemovalReturned(removalBinding)
        fixedRemovalSettledResult = AndroidTargetPlatformResult.ListenerRemovalSettled(removalBinding)
        fixedSentinelObservedResult = AndroidTargetPlatformResult.ListenerSentinelObserved(sentinelBinding)
        fixedSentinelDefinitelyUnentered = AndroidListenerSentinelPostOutcome.DefinitelyUnentered(sentinelBinding)
        fixedSentinelPostExposed = AndroidListenerSentinelPostOutcome.PostExposed(sentinelBinding)
        return true
    }

    internal fun bindSentinelTicket(ticket: AndroidListenerSentinelTicket): Boolean {
        if (fixedSentinelTicket != null || ticket.operationIdentity != operationIdentity) return false
        fixedSentinelTicket = ticket
        return true
    }

    internal fun recordSentinelObservedApplicationResult(
        result: io.screenstream.engine.internal.target.TargetAndroidPlatformApplicationResult.ListenerSentinelObserved,
    ): Boolean = sentinelObservedApplication.compareAndSet(null, result)

    internal fun bindRemovalTicket(ticket: AndroidPostTicket<AndroidTargetListenerRemovalEvidence>): Boolean {
        if (fixedRemovalTicket != null || ticket.operationIdentity != operationIdentity) return false
        fixedRemovalTicket = ticket
        return true
    }
}

internal enum class AndroidTargetListenerRemovalRootDisposition {
    Preparing,
    TargetBound,
    TargetRejected,
}

internal class AndroidTargetListenerRemovalRoot(
    internal val ownerBag: AndroidTargetListenerRemovalOwnerBag,
    internal val removalOperation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
    internal val sentinelTicket: AndroidListenerSentinelTicket,
) {
    private val disposition = AtomicReference(AndroidTargetListenerRemovalRootDisposition.Preparing)

    internal val currentDisposition: AndroidTargetListenerRemovalRootDisposition
        get() = disposition.get()

    internal fun recordTargetBound(): Boolean =
        disposition.compareAndSet(
            AndroidTargetListenerRemovalRootDisposition.Preparing,
            AndroidTargetListenerRemovalRootDisposition.TargetBound,
        )

    internal fun recordTargetRejected(): Boolean =
        disposition.compareAndSet(
            AndroidTargetListenerRemovalRootDisposition.Preparing,
            AndroidTargetListenerRemovalRootDisposition.TargetRejected,
        )
}

internal sealed interface AndroidTargetListenerRemovalPreparationResult {
    class Ready(
        internal val operation: io.screenstream.engine.internal.settlement.OperationOccurrence<AndroidTargetListenerRemovalEvidence>,
        internal val root: AndroidTargetListenerRemovalRoot,
    ) : AndroidTargetListenerRemovalPreparationResult

    class TargetRejected(
        internal val root: AndroidTargetListenerRemovalRoot,
    ) : AndroidTargetListenerRemovalPreparationResult
}
