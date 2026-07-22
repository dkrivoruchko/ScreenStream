package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.OperationDomain
import java.util.concurrent.locks.ReentrantLock

internal class DeliveryEntryRequest internal constructor(
    private val owner: DeliveryOwner,
    internal val handoff: DeliveryHandoffRecord,
    internal val registration: DeliveryRegistration,
    internal val registrationGeneration: Long,
    internal val ticket: DeliveryTicket,
    internal val endpoint: DeliveryEndpoint,
) {
    /** Called exactly once by the aggregate admission port while its Session gate is held. */
    internal fun commit(acceptedHandoffStillAdmitted: Boolean): DeliveryEntryDisposition =
        owner.commitEntry(this, acceptedHandoffStillAdmitted)
}

/** Exact unresolved physical record transferred to terminal cleanup; it owns no Session policy. */
internal class DeliveryTerminalResidue internal constructor(
    internal val handoff: DeliveryHandoffRecord,
    internal val endpoint: DeliveryEndpoint,
    internal val ticket: DeliveryTicket,
    internal val submissionCell: DeliverySubmissionCell,
    internal val entryCell: DeliveryEntryCell,
    internal val callbackCell: DeliveryCallbackCell,
    internal val runnableCell: DeliveryRunnableCell,
    internal val noCallbackCell: DeliveryNoCallbackCell,
    internal val shutdownCell: DeliveryShutdownCell,
    internal val leaseSlot: DeliveryLeaseSlot,
    internal val borrowedAuthority: BorrowedFrameAuthority,
    internal val runnable: Runnable,
)

/** One physical handoff occurrence admitted by the aggregate before this record is created. */
internal class DeliveryHandoffRecord internal constructor(
    private val owner: DeliveryOwner,
    internal val registration: DeliveryRegistration,
    internal val identity: Long,
    internal val outputKind: DeliveryOutputKind,
    internal val ticket: DeliveryTicket,
    internal val callback: (EncodedImageFrame) -> Unit,
    lease: EncodedStorageOwner.EncodedPayloadLease,
) {
    init {
        require(identity > 0L)
    }

    internal val settlementGate: ReentrantLock = ReentrantLock(false)
    internal val submissionCell = DeliverySubmissionCell()
    internal val entryCell = DeliveryEntryCell()
    internal val callbackCell = DeliveryCallbackCell()
    internal val runnableCell = DeliveryRunnableCell()
    internal val noCallbackCell = DeliveryNoCallbackCell()
    internal val leaseSlot = DeliveryLeaseSlot(lease)
    internal val borrowedAuthority = BorrowedFrameAuthority(lease, settlementGate)
    internal val entryRequest = DeliveryEntryRequest(
        owner = owner,
        handoff = this,
        registration = registration,
        registrationGeneration = registration.generation,
        ticket = ticket,
        endpoint = ticket.endpoint,
    )
    internal val submissionExceptionNotice = DeliveryFailureNotice(DeliveryFailureKind.SubmissionException, owner, this)
    internal val admissionExceptionNotice = DeliveryFailureNotice(DeliveryFailureKind.AdmissionPortException, owner, this)
    internal val runnableExceptionNotice = DeliveryFailureNotice(DeliveryFailureKind.RunnableException, owner, this)
    internal val directFatalNotice = DeliveryFailureNotice(DeliveryFailureKind.DirectFatal, owner, this)
    internal val runnable: Runnable = Runnable { owner.runHandoffRunnable(this) }
    internal val terminalResidue = DeliveryTerminalResidue(
        handoff = this,
        endpoint = ticket.endpoint,
        ticket = ticket,
        submissionCell = submissionCell,
        entryCell = entryCell,
        callbackCell = callbackCell,
        runnableCell = runnableCell,
        noCallbackCell = noCallbackCell,
        shutdownCell = ticket.endpoint.shutdownCell,
        leaseSlot = leaseSlot,
        borrowedAuthority = borrowedAuthority,
        runnable = runnable,
    )

    internal var state: HandoffState = HandoffState.Prepared
    internal var domain: OperationDomain = OperationDomain.Active
        private set
    internal var admissionOpen: Boolean = true
    internal var callbackInvocationStarted: Boolean = false
    internal var exactFatal: Throwable? = null

    internal fun transferToCleanupLocked() {
        check(settlementGate.isHeldByCurrentThread)
        domain = OperationDomain.Cleanup
    }

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner
}

internal sealed interface DeliveryHandoffPreparation {
    internal class Prepared internal constructor(
        private val owner: DeliveryOwner,
        internal val handoff: DeliveryHandoffRecord,
    ) : DeliveryHandoffPreparation {
        private val atomicState = java.util.concurrent.atomic.AtomicReference(DeliveryPreparationState.Prepared)

        internal val state: DeliveryPreparationState
            get() = atomicState.get()

        internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner

        internal fun beginCommit(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Prepared,
            DeliveryPreparationState.Committing,
        )

        internal fun publishInstalled(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Committing,
            DeliveryPreparationState.Installed,
        )

        internal fun restorePrepared(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Committing,
            DeliveryPreparationState.Prepared,
        )

        internal fun discard(): Boolean = atomicState.compareAndSet(
            DeliveryPreparationState.Prepared,
            DeliveryPreparationState.Discarded,
        )
    }

    internal object EndpointUnavailable : DeliveryHandoffPreparation
    internal object Busy : DeliveryHandoffPreparation
}
