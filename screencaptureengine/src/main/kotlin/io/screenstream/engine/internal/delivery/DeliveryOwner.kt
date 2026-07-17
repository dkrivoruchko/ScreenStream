package io.screenstream.engine.internal.delivery

import io.screenstream.engine.EncodedImageFrame
import io.screenstream.engine.ImageSize
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.settlement.DeadlineArmResult
import io.screenstream.engine.internal.settlement.DeadlineDisposition
import io.screenstream.engine.internal.settlement.DeadlineOccurrence
import io.screenstream.engine.internal.settlement.EngineClock
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationOwnerBag
import io.screenstream.engine.internal.settlement.OperationReceipt
import io.screenstream.engine.internal.settlement.OperationReturnCell
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationReturnedOwner
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionRejectionResult
import io.screenstream.engine.internal.settlement.SettlementSignal
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.EmptyCoroutineContext

internal enum class HandoffState {
    Prepared,
    Dispatching,
    AcceptedQueued,
    DetachedPreEntry,
    Entered,
    Resolved,
    Quarantined,
}

internal enum class DeliveryCommandResult {
    Applied,
    AlreadyApplied,
    NotCurrent,
}

internal enum class DeliverySubmissionResult {
    Attempted,
    NotCurrent,
}

internal enum class DeliveryTerminalTransferResult {
    Settled,
    Transferred,
    NotCurrent,
}

internal enum class DeliveryAcceptedEntryDeadlineResult {
    Pending,
    Expired,
    GuardFailed,
    Settled,
    NotCurrent,
}

internal enum class DeliveryFactUse {
    Unclaimed,
    Active,
    Cleanup,
}

internal enum class DeliveryTrampolineEntryDisposition {
    Empty,
    Entered,
    DetachedSelfRejected,
}

internal enum class DeliveryCallbackPhase {
    Empty,
    Pending,
    Complete,
}

internal enum class DeliveryCallbackReturnDisposition {
    Empty,
    Normal,
    Thrown,
}

internal enum class DeliveryLeaseReleaseDisposition {
    Unclaimed,
    Claimed,
    Released,
    Conflict,
}

internal class DeliveryRegistration internal constructor(
    private val owner: DeliveryOwner,
    internal val generation: Long,
    callback: (EncodedImageFrame) -> Unit,
) {
    init {
        require(generation > 0L)
    }

    internal var physicalFenceOpen: Boolean = false
        private set

    private var retainedCallback: ((EncodedImageFrame) -> Unit)? = callback

    internal val hasCallback: Boolean
        get() = retainedCallback != null

    internal fun openPhysicalFenceLocked() {
        physicalFenceOpen = true
    }

    internal fun closePhysicalFenceLocked(): Boolean {
        if (!physicalFenceOpen) return false
        physicalFenceOpen = false
        return true
    }

    internal fun callbackLocked(): ((EncodedImageFrame) -> Unit)? = retainedCallback

    internal fun clearCallbackLocked() {
        retainedCallback = null
    }

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner
}

internal class DeliveryDispatchEvidence internal constructor() : OperationEvidence {
    override val receipt: OperationReceipt? = null
    override val returnedOwner: OperationReturnedOwner? = null
}

internal class DeliveryDispatchOwnerBag internal constructor(
    internal val registration: DeliveryRegistration,
    internal val leaseSlot: DeliveryLeaseSlot,
) : OperationOwnerBag

internal class DeliveryLeaseSlot internal constructor(lease: EncodedStorageOwner.EncodedPayloadLease) {
    internal var leaseLocked: EncodedStorageOwner.EncodedPayloadLease? = lease
        private set

    internal fun clearConsumedLocked(expectedLease: EncodedStorageOwner.EncodedPayloadLease): Boolean {
        if (leaseLocked !== expectedLease) return false
        leaseLocked = null
        return true
    }
}

internal class DeliveryTrampolineEntryCell internal constructor() {
    internal var disposition: DeliveryTrampolineEntryDisposition = DeliveryTrampolineEntryDisposition.Empty
        private set

    internal var callbackThread: Thread? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishEnteredLocked(thread: Thread): Boolean {
        if (disposition != DeliveryTrampolineEntryDisposition.Empty) return false
        callbackThread = thread
        disposition = DeliveryTrampolineEntryDisposition.Entered
        return true
    }

    internal fun publishDetachedLocked(): Boolean {
        if (disposition != DeliveryTrampolineEntryDisposition.Empty) return false
        disposition = DeliveryTrampolineEntryDisposition.DetachedSelfRejected
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (disposition == DeliveryTrampolineEntryDisposition.Empty || use != DeliveryFactUse.Unclaimed) return false
        use = if (domain == OperationDomain.Active) DeliveryFactUse.Active else DeliveryFactUse.Cleanup
        return true
    }

    internal fun clearCallbackThreadLocked() {
        callbackThread = null
    }
}

internal class DeliveryCallbackReturnCell internal constructor() {
    internal var phase: DeliveryCallbackPhase = DeliveryCallbackPhase.Empty
        private set

    internal var disposition: DeliveryCallbackReturnDisposition = DeliveryCallbackReturnDisposition.Empty
        private set

    internal var throwable: Throwable? = null
        private set

    internal var use: DeliveryFactUse = DeliveryFactUse.Unclaimed
        private set

    internal fun publishPendingLocked(failure: Throwable?): Boolean {
        if (phase != DeliveryCallbackPhase.Empty) return false
        if (failure == null) {
            disposition = DeliveryCallbackReturnDisposition.Normal
        } else {
            throwable = failure
            disposition = DeliveryCallbackReturnDisposition.Thrown
        }
        phase = DeliveryCallbackPhase.Pending
        return true
    }

    internal fun completeLocked(): Boolean {
        if (phase != DeliveryCallbackPhase.Pending) return false
        phase = DeliveryCallbackPhase.Complete
        return true
    }

    internal fun claimLocked(domain: OperationDomain): Boolean {
        if (phase != DeliveryCallbackPhase.Complete || use != DeliveryFactUse.Unclaimed) return false
        use = if (domain == OperationDomain.Active) DeliveryFactUse.Active else DeliveryFactUse.Cleanup
        return true
    }
}

internal class DeliveryAcceptedEntryTimeoutCause internal constructor(
    internal val handoffIdentity: Long,
) : IllegalStateException("Accepted frame callback did not enter before its deadline.")

internal class DeliveryAcceptedEntryDeadlineGuardCause internal constructor(
    internal val handoffIdentity: Long,
) : IllegalStateException("Accepted frame callback deadline clock or arithmetic was invalid.")

internal class BorrowedEncodedImageFrame internal constructor(
    lease: EncodedStorageOwner.EncodedPayloadLease,
    private val settlementGate: ReentrantLock,
) : EncodedImageFrame {
    private var retainedLease: EncodedStorageOwner.EncodedPayloadLease? = lease

    private var callbackThread: Thread? = null

    private var authorityOpen: Boolean = false

    private val wrongThreadFailure = IllegalStateException("EncodedImageFrame is valid only on its callback thread.")
    private val closedAuthorityFailure = IllegalStateException("EncodedImageFrame is valid only during its callback body.")

    override val byteCount: Int
        get() = checkedLease().byteCount

    override val imageSize: ImageSize
        get() = checkedLease().imageSize

    override val sequence: Long
        get() = checkedLease().sequence

    override val timestampElapsedRealtimeNanos: Long
        get() = checkedLease().timestampElapsedRealtimeNanos

    override fun copyTo(destination: ByteArray, destinationOffset: Int): Int = checkedLease().copyTo(destination, destinationOffset)

    override fun copyBytes(): ByteArray = checkedLease().copyBytes()

    internal fun openLocked(thread: Thread) {
        callbackThread = thread
        authorityOpen = true
    }

    internal fun closeLocked() {
        authorityOpen = false
        callbackThread = null
        retainedLease = null
    }

    private fun checkedLease(): EncodedStorageOwner.EncodedPayloadLease = settlementGate.withLock {
        if (Thread.currentThread() !== callbackThread) throw wrongThreadFailure
        if (!authorityOpen) throw closedAuthorityFailure
        retainedLease ?: throw closedAuthorityFailure
    }
}

internal class DeliveryHandoffRecord internal constructor(
    private val owner: DeliveryOwner,
    internal val registration: DeliveryRegistration,
    internal val identity: Long,
    acceptedEntryDeadlineIdentity: Long,
    acceptedEntryWakeGeneration: Long,
    lease: EncodedStorageOwner.EncodedPayloadLease,
    clock: EngineClock,
    signal: SettlementSignal,
) {
    init {
        require(identity > 0L)
        require(acceptedEntryDeadlineIdentity > 0L)
        require(acceptedEntryWakeGeneration > 0L)
    }

    internal val leaseSlot = DeliveryLeaseSlot(lease)
    internal val dispatchReturnCell: OperationReturnCell<DeliveryDispatchEvidence> = OperationReturnCell(DeliveryDispatchEvidence())
    internal val dispatchOccurrence: OperationOccurrence<DeliveryDispatchEvidence> = OperationOccurrence(
        identity = identity,
        clock = clock,
        returnCell = dispatchReturnCell,
        ownerBag = DeliveryDispatchOwnerBag(registration, leaseSlot),
    )
    internal val acceptedEntryDeadlineGuardCause = DeliveryAcceptedEntryDeadlineGuardCause(identity)
    internal val acceptedEntryDeadline: DeadlineOccurrence = DeadlineOccurrence(
        identity = acceptedEntryDeadlineIdentity,
        boundOccurrenceIdentity = identity,
        durationNanos = ACCEPTED_ENTRY_DEADLINE_NANOS,
        initialWakeGeneration = acceptedEntryWakeGeneration,
        timeoutCause = DeliveryAcceptedEntryTimeoutCause(identity),
        settlementGate = dispatchOccurrence.settlementGate,
        clock = clock,
        signal = signal,
    )
    internal val trampolineEntryCell = DeliveryTrampolineEntryCell()
    internal val callbackReturnCell = DeliveryCallbackReturnCell()
    internal val borrowedFrame = BorrowedEncodedImageFrame(lease, dispatchOccurrence.settlementGate)
    internal val worker: Runnable = Runnable { owner.runDeliveryWorker(this) }
    internal val trampoline: Runnable = Runnable { owner.runCallbackTrampoline(this) }

    internal var state: HandoffState = HandoffState.Prepared
    internal var workerAdmissionOpen: Boolean = true
    internal var trampolineAdmissionOpen: Boolean = true
    internal var leaseReleaseDisposition: DeliveryLeaseReleaseDisposition = DeliveryLeaseReleaseDisposition.Unclaimed
    internal var deadlineGuardFailure: Throwable? = null

    internal fun belongsTo(expectedOwner: DeliveryOwner): Boolean = owner === expectedOwner

    private companion object {
        private const val ACCEPTED_ENTRY_DEADLINE_NANOS: Long = 5_000_000_000L
    }
}

internal class DeliveryOwner internal constructor(
    private val deliveryIoView: CoroutineDispatcher,
    private val frameCallbackDispatcher: CoroutineDispatcher,
    private val sessionGate: ReentrantLock,
    private val clock: EngineClock,
    private val settlementSignal: SettlementSignal,
) {
    private var currentRegistration: DeliveryRegistration? = null
    private var currentHandoff: DeliveryHandoffRecord? = null

    internal fun prepareRegistration(generation: Long, callback: (EncodedImageFrame) -> Unit): DeliveryRegistration =
        DeliveryRegistration(this, generation, callback)

    internal fun prepareHandoff(
        registration: DeliveryRegistration,
        handoffIdentity: Long,
        acceptedEntryDeadlineIdentity: Long,
        acceptedEntryWakeGeneration: Long,
        preparedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryHandoffRecord {
        require(registration.belongsTo(this))
        return DeliveryHandoffRecord(
            owner = this,
            registration = registration,
            identity = handoffIdentity,
            acceptedEntryDeadlineIdentity = acceptedEntryDeadlineIdentity,
            acceptedEntryWakeGeneration = acceptedEntryWakeGeneration,
            lease = preparedLease,
            clock = clock,
            signal = settlementSignal,
        )
    }

    internal fun installRegistration(registration: DeliveryRegistration): DeliveryCommandResult = sessionGate.withLock {
        if (!registration.belongsTo(this)) return@withLock DeliveryCommandResult.NotCurrent
        if (currentRegistration === registration && registration.physicalFenceOpen) {
            return@withLock DeliveryCommandResult.AlreadyApplied
        }
        if (currentRegistration != null || currentHandoff != null || !registration.hasCallback) {
            return@withLock DeliveryCommandResult.NotCurrent
        }
        registration.openPhysicalFenceLocked()
        currentRegistration = registration
        DeliveryCommandResult.Applied
    }

    internal fun installHandoff(handoff: DeliveryHandoffRecord): DeliveryCommandResult = sessionGate.withLock outer@{
        val registration = handoff.registration
        if (currentRegistration !== registration || !registration.physicalFenceOpen ||
            !registration.belongsTo(this) || !handoff.belongsTo(this) || currentHandoff != null
        ) {
            return@outer DeliveryCommandResult.NotCurrent
        }
        val installable = handoff.dispatchOccurrence.settlementGate.withLock inner@{
            val lease = handoff.leaseSlot.leaseLocked
            if (handoff.state != HandoffState.Prepared ||
                handoff.dispatchOccurrence.submissionDisposition != OperationSubmissionDisposition.None ||
                handoff.dispatchOccurrence.entryDisposition != OperationEntryDisposition.Unentered ||
                handoff.dispatchReturnCell.disposition != OperationReturnDisposition.Empty ||
                handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Unclaimed ||
                lease == null || lease.isReleased
            ) {
                return@inner false
            }
            true
        }
        if (!installable) return@outer DeliveryCommandResult.NotCurrent
        currentHandoff = handoff
        DeliveryCommandResult.Applied
    }

    internal fun submitHandoff(handoff: DeliveryHandoffRecord): DeliverySubmissionResult {
        val claimed = sessionGate.withLock outer@{
            if (currentHandoff !== handoff || currentRegistration !== handoff.registration ||
                !handoff.registration.physicalFenceOpen || !handoff.registration.belongsTo(this) ||
                !handoff.belongsTo(this)
            ) {
                return@outer false
            }
            handoff.dispatchOccurrence.settlementGate.withLock inner@{
                if (handoff.state != HandoffState.Prepared || !handoff.workerAdmissionOpen) return@inner false
                handoff.dispatchOccurrence.beginSubmission()
            }
        }
        if (!claimed) return DeliverySubmissionResult.NotCurrent

        try {
            deliveryIoView.dispatch(EmptyCoroutineContext, handoff.worker)
        } catch (failure: Exception) {
            val rejectionResult = handoff.dispatchOccurrence.publishSubmissionRejected(failure)
            settleClosedWorkerSubmission(
                handoff = handoff,
                activeRejected = rejectionResult == OperationSubmissionRejectionResult.Active,
            )
            settlementSignal.signal()
            return DeliverySubmissionResult.Attempted
        } catch (failure: Error) {
            val published = handoff.dispatchOccurrence.publishSubmissionAmbiguousError(failure)
            convergeAfterSubmission(handoff)
            signalAndRethrow(published, failure)
        }

        handoff.dispatchOccurrence.publishSubmissionAccepted()
        settleClosedWorkerSubmission(handoff, activeRejected = false)
        settlementSignal.signal()
        return DeliverySubmissionResult.Attempted
    }

    internal fun closeRegistration(registration: DeliveryRegistration): DeliveryCommandResult {
        var handoffToRelease: DeliveryHandoffRecord? = null
        var result = DeliveryCommandResult.NotCurrent
        sessionGate.withLock {
            if (!registration.belongsTo(this) || currentRegistration !== registration) return@withLock
            val changed = registration.closePhysicalFenceLocked()
            val handoff = currentHandoff
            if (handoff != null && handoff.registration === registration) {
                handoff.dispatchOccurrence.settlementGate.withLock {
                    handoff.trampolineAdmissionOpen = false
                    when (handoff.state) {
                        HandoffState.Prepared -> {
                            handoff.workerAdmissionOpen = false
                            handoff.dispatchOccurrence.arbitrateTerminal(mandatoryCleanup = false)
                            if (settleClosedPreparedLocked(handoff)) handoffToRelease = handoff
                        }

                        HandoffState.Dispatching,
                        HandoffState.AcceptedQueued,
                            -> if (handoff.trampolineEntryCell.disposition == DeliveryTrampolineEntryDisposition.Empty) {
                            handoff.state = HandoffState.DetachedPreEntry
                        }

                        HandoffState.DetachedPreEntry,
                        HandoffState.Entered,
                        HandoffState.Resolved,
                        HandoffState.Quarantined,
                            -> Unit
                    }
                }
            }
            result = if (changed) DeliveryCommandResult.Applied else DeliveryCommandResult.AlreadyApplied
        }
        val releaseTarget = handoffToRelease
        if (releaseTarget != null) performClaimedLeaseRelease(releaseTarget)
        if (result != DeliveryCommandResult.NotCurrent) settlementSignal.signal()
        return result
    }

    internal fun evaluateAcceptedEntryDeadline(handoff: DeliveryHandoffRecord): DeliveryAcceptedEntryDeadlineResult {
        if (!handoff.belongsTo(this)) return DeliveryAcceptedEntryDeadlineResult.NotCurrent
        var releaseClaimed = false
        var result = DeliveryAcceptedEntryDeadlineResult.NotCurrent
        sessionGate.withLock {
            handoff.dispatchOccurrence.settlementGate.withLock {
                if (currentHandoff !== handoff && handoff.state != HandoffState.Quarantined) {
                    return@withLock
                }
                val durableResult = when {
                    handoff.deadlineGuardFailure != null -> DeliveryAcceptedEntryDeadlineResult.GuardFailed
                    handoff.acceptedEntryDeadline.disposition == DeadlineDisposition.Expired ->
                        DeliveryAcceptedEntryDeadlineResult.Expired

                    else -> null
                }
                if (handoff.trampolineEntryCell.disposition != DeliveryTrampolineEntryDisposition.Empty) {
                    handoff.acceptedEntryDeadline.retireLocked()
                    convergeLocked(handoff)
                    result = durableResult ?: acceptedEntryDeadlineResultLocked(handoff)
                    return@withLock
                }
                if (handoff.acceptedEntryDeadline.disposition != DeadlineDisposition.Armed) {
                    convergeLocked(handoff)
                    result = durableResult ?: when (handoff.acceptedEntryDeadline.disposition) {
                        DeadlineDisposition.Unarmed,
                        DeadlineDisposition.Armed,
                            -> DeliveryAcceptedEntryDeadlineResult.Pending

                        DeadlineDisposition.Expired,
                        DeadlineDisposition.Retired,
                            -> acceptedEntryDeadlineResultLocked(handoff)
                    }
                    return@withLock
                }
                val nowNanos = clock.nowNanos()
                if (nowNanos < 0L) {
                    handoff.deadlineGuardFailure = handoff.acceptedEntryDeadlineGuardCause
                    handoff.acceptedEntryDeadline.retireLocked()
                    releaseClaimed = detachAndClaimReleaseLocked(handoff)
                    result = DeliveryAcceptedEntryDeadlineResult.GuardFailed
                } else if (nowNanos >= handoff.acceptedEntryDeadline.deadlineNanos) {
                    handoff.acceptedEntryDeadline.expireLocked()
                    releaseClaimed = detachAndClaimReleaseLocked(handoff)
                    result = DeliveryAcceptedEntryDeadlineResult.Expired
                } else {
                    result = DeliveryAcceptedEntryDeadlineResult.Pending
                }
                convergeLocked(handoff)
            }
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff)
        if (result != DeliveryAcceptedEntryDeadlineResult.NotCurrent) settlementSignal.signal()
        return result
    }

    internal fun detachResolvedHandoff(handoff: DeliveryHandoffRecord): DeliveryCommandResult =
        sessionGate.withLock outer@{
            if (currentHandoff !== handoff || !handoff.belongsTo(this)) return@outer DeliveryCommandResult.NotCurrent
            val resolved = handoff.dispatchOccurrence.settlementGate.withLock {
                handoff.state == HandoffState.Resolved
            }
            if (!resolved) return@outer DeliveryCommandResult.NotCurrent
            currentHandoff = null
            DeliveryCommandResult.Applied
        }

    internal fun detachClosedRegistration(registration: DeliveryRegistration): DeliveryCommandResult = sessionGate.withLock {
        if (!registration.belongsTo(this) || currentRegistration !== registration || currentHandoff != null ||
            registration.physicalFenceOpen
        ) {
            return@withLock DeliveryCommandResult.NotCurrent
        }
        registration.clearCallbackLocked()
        currentRegistration = null
        DeliveryCommandResult.Applied
    }

    internal fun transferForTerminal(registration: DeliveryRegistration, handoff: DeliveryHandoffRecord?): DeliveryTerminalTransferResult {
        var releaseClaimed = false
        var result = DeliveryTerminalTransferResult.NotCurrent
        sessionGate.withLock {
            if (!registration.belongsTo(this) || currentRegistration !== registration || currentHandoff !== handoff) {
                return@withLock
            }
            registration.closePhysicalFenceLocked()
            if (handoff == null) {
                registration.clearCallbackLocked()
                currentRegistration = null
                result = DeliveryTerminalTransferResult.Settled
                return@withLock
            }
            handoff.dispatchOccurrence.settlementGate.withLock {
                handoff.workerAdmissionOpen = false
                handoff.trampolineAdmissionOpen = false
                handoff.dispatchOccurrence.arbitrateTerminal(mandatoryCleanup = false)
                if (handoff.state == HandoffState.Prepared) {
                    releaseClaimed = settleClosedPreparedLocked(handoff)
                }
                convergeLocked(handoff)
                if (handoff.state != HandoffState.Resolved) handoff.state = HandoffState.Quarantined
            }
            registration.clearCallbackLocked()
            currentHandoff = null
            currentRegistration = null
            result = DeliveryTerminalTransferResult.Transferred
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff!!)
        if (result == DeliveryTerminalTransferResult.Transferred && handoff != null) {
            val settled = handoff.dispatchOccurrence.settlementGate.withLock { handoff.state == HandoffState.Resolved }
            if (settled) result = DeliveryTerminalTransferResult.Settled
        }
        if (result != DeliveryTerminalTransferResult.NotCurrent) settlementSignal.signal()
        return result
    }

    internal fun releasedLeaseForStorageConsumption(
        handoff: DeliveryHandoffRecord,
    ): EncodedStorageOwner.EncodedPayloadLease? {
        if (!handoff.belongsTo(this)) return null
        return handoff.dispatchOccurrence.settlementGate.withLock {
            if (handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Released &&
                handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Conflict
            ) {
                return@withLock null
            }
            handoff.leaseSlot.leaseLocked
        }
    }

    internal fun acknowledgeStorageLeaseConsumption(
        handoff: DeliveryHandoffRecord,
        consumedLease: EncodedStorageOwner.EncodedPayloadLease,
    ): DeliveryCommandResult {
        if (!handoff.belongsTo(this)) return DeliveryCommandResult.NotCurrent
        val applied = handoff.dispatchOccurrence.settlementGate.withLock {
            val releasePublished = handoff.leaseReleaseDisposition == DeliveryLeaseReleaseDisposition.Released ||
                    handoff.leaseReleaseDisposition == DeliveryLeaseReleaseDisposition.Conflict
            if (!releasePublished || !handoff.leaseSlot.clearConsumedLocked(consumedLease)) {
                return@withLock false
            }
            convergeLocked(handoff)
            true
        }
        if (!applied) return DeliveryCommandResult.NotCurrent
        settlementSignal.signal()
        return DeliveryCommandResult.Applied
    }

    internal fun runDeliveryWorker(handoff: DeliveryHandoffRecord) {
        if (!handoff.belongsTo(this)) return
        var entered = false
        var releaseClaimed = false
        var occurrenceChanged = false
        handoff.dispatchOccurrence.settlementGate.withLock {
            if (handoff.state == HandoffState.Prepared && handoff.workerAdmissionOpen) {
                if (handoff.dispatchOccurrence.tryEnter() == OperationEntryResult.Entered) {
                    handoff.state = HandoffState.Dispatching
                    entered = true
                    occurrenceChanged = true
                }
            } else {
                handoff.dispatchOccurrence.tryEnter()
                handoff.dispatchOccurrence.arbitrateTerminal(mandatoryCleanup = false)
                releaseClaimed = settleClosedPreparedLocked(handoff)
                occurrenceChanged = true
            }
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff)
        if (!entered) {
            if (occurrenceChanged) settlementSignal.signal()
            return
        }

        settlementSignal.signal()
        try {
            frameCallbackDispatcher.dispatch(EmptyCoroutineContext, handoff.trampoline)
        } catch (failure: Throwable) {
            try {
                publishDispatcherThrown(handoff, failure)
            } finally {
                if (failure is Error) throw failure
            }
            return
        }

        val returnSampleNanos = clock.nowNanos()
        publishDispatcherNormal(handoff, returnSampleNanos)
    }

    internal fun runCallbackTrampoline(handoff: DeliveryHandoffRecord) {
        if (!handoff.belongsTo(this)) return
        var callback: ((EncodedImageFrame) -> Unit)? = null
        var releaseClaimed = false
        sessionGate.withLock {
            handoff.dispatchOccurrence.settlementGate.withLock {
                if (handoff.trampolineEntryCell.disposition != DeliveryTrampolineEntryDisposition.Empty) return@withLock
                if (handoff.dispatchOccurrence.entryDisposition != OperationEntryDisposition.Entered) return@withLock

                val deadlineAllowsEntry = evaluateDeadlineAtTrampolineLocked(handoff)
                val registration = handoff.registration
                val physicallyAdmitted = deadlineAllowsEntry &&
                        currentRegistration === registration && currentHandoff === handoff &&
                        registration.physicalFenceOpen && handoff.trampolineAdmissionOpen &&
                        (handoff.state == HandoffState.Dispatching || handoff.state == HandoffState.AcceptedQueued)
                val retainedCallback = if (physicallyAdmitted) registration.callbackLocked() else null
                if (retainedCallback == null) {
                    releaseClaimed = detachAndClaimReleaseLocked(handoff)
                    return@withLock
                }

                val callbackThread = Thread.currentThread()
                handoff.trampolineEntryCell.publishEnteredLocked(callbackThread)
                handoff.acceptedEntryDeadline.retireLocked()
                handoff.borrowedFrame.openLocked(callbackThread)
                handoff.state = HandoffState.Entered
                callback = retainedCallback
            }
        }
        if (releaseClaimed) {
            performClaimedLeaseRelease(handoff)
            settlementSignal.signal()
            return
        }

        val admittedCallback = callback ?: return
        settlementSignal.signal()
        var callbackFailure: Throwable? = null
        try {
            admittedCallback(handoff.borrowedFrame)
        } catch (failure: Throwable) {
            callbackFailure = failure
        }
        try {
            completeCallback(handoff, callbackFailure)
        } finally {
            if (callbackFailure is Error) throw callbackFailure
        }
    }

    private fun settleClosedWorkerSubmission(handoff: DeliveryHandoffRecord, activeRejected: Boolean) {
        var releaseClaimed = false
        handoff.dispatchOccurrence.settlementGate.withLock {
            if (activeRejected) {
                handoff.workerAdmissionOpen = false
                handoff.trampolineAdmissionOpen = false
                handoff.dispatchOccurrence.arbitrateTerminal(mandatoryCleanup = false)
            }
            releaseClaimed = settleClosedPreparedLocked(handoff)
            convergeLocked(handoff)
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff)
    }

    private fun settleClosedPreparedLocked(handoff: DeliveryHandoffRecord): Boolean {
        if (handoff.state != HandoffState.Prepared && handoff.state != HandoffState.Quarantined ||
            handoff.workerAdmissionOpen ||
            handoff.dispatchOccurrence.domain != OperationDomain.Cleanup ||
            handoff.dispatchOccurrence.entryDisposition == OperationEntryDisposition.Entered
        ) {
            return false
        }
        handoff.dispatchOccurrence.arbitrateTerminal(mandatoryCleanup = false)
        if (handoff.dispatchOccurrence.entryDisposition != OperationEntryDisposition.Cancelled) return false
        return detachAndClaimReleaseLocked(handoff)
    }

    private fun publishDispatcherNormal(handoff: DeliveryHandoffRecord, returnSampleNanos: Long) {
        var releaseClaimed = false
        handoff.dispatchOccurrence.settlementGate.withLock {
            handoff.dispatchReturnCell.publishNormalLocked(returnSampleNanos)
            when (handoff.trampolineEntryCell.disposition) {
                DeliveryTrampolineEntryDisposition.Empty -> {
                    when (handoff.acceptedEntryDeadline.armLocked(returnSampleNanos)) {
                        DeadlineArmResult.Armed -> {
                            if (handoff.state != HandoffState.Quarantined) {
                                handoff.state = if (handoff.trampolineAdmissionOpen) {
                                    HandoffState.AcceptedQueued
                                } else {
                                    HandoffState.DetachedPreEntry
                                }
                            }
                        }

                        DeadlineArmResult.InvalidClockOrOverflow -> {
                            handoff.deadlineGuardFailure = handoff.acceptedEntryDeadlineGuardCause
                            releaseClaimed = detachAndClaimReleaseLocked(handoff)
                        }

                        DeadlineArmResult.AlreadySettled -> Unit
                    }
                }

                DeliveryTrampolineEntryDisposition.Entered -> Unit
                DeliveryTrampolineEntryDisposition.DetachedSelfRejected -> Unit
            }
            convergeLocked(handoff)
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff)
        settlementSignal.signal()
    }

    private fun publishDispatcherThrown(handoff: DeliveryHandoffRecord, failure: Throwable) {
        var releaseClaimed = false
        handoff.dispatchOccurrence.settlementGate.withLock {
            handoff.dispatchReturnCell.publishThrownLocked(NO_SETTLEMENT_SAMPLE, failure)
            if (handoff.trampolineEntryCell.disposition == DeliveryTrampolineEntryDisposition.Empty) {
                releaseClaimed = detachAndClaimReleaseLocked(handoff)
            }
            convergeLocked(handoff)
        }
        if (releaseClaimed) performClaimedLeaseRelease(handoff)
        settlementSignal.signal()
    }

    private fun evaluateDeadlineAtTrampolineLocked(handoff: DeliveryHandoffRecord): Boolean {
        return when (handoff.acceptedEntryDeadline.disposition) {
            DeadlineDisposition.Unarmed -> true
            DeadlineDisposition.Armed -> {
                val nowNanos = clock.nowNanos()
                if (nowNanos < 0L) {
                    handoff.deadlineGuardFailure = handoff.acceptedEntryDeadlineGuardCause
                    handoff.acceptedEntryDeadline.retireLocked()
                    false
                } else if (nowNanos >= handoff.acceptedEntryDeadline.deadlineNanos) {
                    handoff.acceptedEntryDeadline.expireLocked()
                    false
                } else {
                    true
                }
            }

            DeadlineDisposition.Expired,
            DeadlineDisposition.Retired,
                -> false
        }
    }

    private fun completeCallback(handoff: DeliveryHandoffRecord, failure: Throwable?) {
        var releaseClaimed = false
        handoff.dispatchOccurrence.settlementGate.withLock {
            if (!handoff.callbackReturnCell.publishPendingLocked(failure)) return@withLock
            handoff.borrowedFrame.closeLocked()
            handoff.trampolineEntryCell.clearCallbackThreadLocked()
            releaseClaimed = claimLeaseReleaseLocked(handoff)
        }
        if (releaseClaimed) {
            performClaimedLeaseRelease(handoff)
        } else {
            handoff.dispatchOccurrence.settlementGate.withLock {
                completePendingCallbackLocked(handoff)
                convergeLocked(handoff)
            }
            settlementSignal.signal()
        }
    }

    private fun claimLeaseReleaseLocked(handoff: DeliveryHandoffRecord): Boolean {
        if (handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Unclaimed) return false
        handoff.leaseReleaseDisposition = DeliveryLeaseReleaseDisposition.Claimed
        return true
    }

    private fun detachAndClaimReleaseLocked(handoff: DeliveryHandoffRecord): Boolean {
        handoff.trampolineAdmissionOpen = false
        handoff.trampolineEntryCell.publishDetachedLocked()
        if (handoff.acceptedEntryDeadline.disposition != DeadlineDisposition.Expired) {
            handoff.acceptedEntryDeadline.retireLocked()
        }
        handoff.borrowedFrame.closeLocked()
        if (handoff.state != HandoffState.Quarantined) handoff.state = HandoffState.DetachedPreEntry
        return claimLeaseReleaseLocked(handoff)
    }

    private fun performClaimedLeaseRelease(handoff: DeliveryHandoffRecord) {
        val lease = handoff.dispatchOccurrence.settlementGate.withLock {
            if (handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Claimed) return@withLock null
            handoff.leaseSlot.leaseLocked
        } ?: return
        val released = try {
            lease.release()
        } catch (failure: Error) {
            signalAndRethrow(published = true, failure = failure)
        }
        handoff.dispatchOccurrence.settlementGate.withLock {
            if (handoff.leaseReleaseDisposition == DeliveryLeaseReleaseDisposition.Claimed) {
                handoff.leaseReleaseDisposition = if (released) {
                    DeliveryLeaseReleaseDisposition.Released
                } else {
                    DeliveryLeaseReleaseDisposition.Conflict
                }
            }
            completePendingCallbackLocked(handoff)
            convergeLocked(handoff)
        }
        settlementSignal.signal()
    }

    private fun completePendingCallbackLocked(handoff: DeliveryHandoffRecord) {
        if (handoff.callbackReturnCell.phase != DeliveryCallbackPhase.Pending) return
        if (handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Released &&
            handoff.leaseReleaseDisposition != DeliveryLeaseReleaseDisposition.Conflict
        ) {
            return
        }
        handoff.callbackReturnCell.completeLocked()
    }

    private fun convergeLocked(handoff: DeliveryHandoffRecord) {
        val submissionSettled = handoff.dispatchOccurrence.submissionDisposition != OperationSubmissionDisposition.None &&
                handoff.dispatchOccurrence.submissionDisposition != OperationSubmissionDisposition.Submitting
        val dispatchSettled = handoff.dispatchReturnCell.disposition != OperationReturnDisposition.Empty ||
                handoff.dispatchOccurrence.entryDisposition == OperationEntryDisposition.Cancelled
        val trampolineSettled = handoff.trampolineEntryCell.disposition != DeliveryTrampolineEntryDisposition.Empty
        val callbackSettled = when (handoff.trampolineEntryCell.disposition) {
            DeliveryTrampolineEntryDisposition.Empty -> false
            DeliveryTrampolineEntryDisposition.Entered -> handoff.callbackReturnCell.phase == DeliveryCallbackPhase.Complete
            DeliveryTrampolineEntryDisposition.DetachedSelfRejected -> true
        }
        val releaseSettled = (handoff.leaseReleaseDisposition == DeliveryLeaseReleaseDisposition.Released ||
                handoff.leaseReleaseDisposition == DeliveryLeaseReleaseDisposition.Conflict) &&
                handoff.leaseSlot.leaseLocked == null
        val deadlineSettled = handoff.acceptedEntryDeadline.wakeLink.isFullySettledLocked()
        if (!submissionSettled || !dispatchSettled || !trampolineSettled || !callbackSettled || !releaseSettled || !deadlineSettled) {
            return
        }

        handoff.state = HandoffState.Resolved
    }

    private fun convergeAfterSubmission(handoff: DeliveryHandoffRecord) {
        handoff.dispatchOccurrence.settlementGate.withLock { convergeLocked(handoff) }
    }

    private fun acceptedEntryDeadlineResultLocked(
        handoff: DeliveryHandoffRecord,
    ): DeliveryAcceptedEntryDeadlineResult =
        if (handoff.acceptedEntryDeadline.wakeLink.isFullySettledLocked()) {
            DeliveryAcceptedEntryDeadlineResult.Settled
        } else {
            DeliveryAcceptedEntryDeadlineResult.Pending
        }

    private fun signalAndRethrow(published: Boolean, failure: Error): Nothing {
        try {
            if (published) settlementSignal.signal()
        } finally {
            throw failure
        }
    }

    private companion object {
        private const val NO_SETTLEMENT_SAMPLE: Long = Long.MIN_VALUE
    }
}
