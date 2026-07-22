package io.screenstream.engine.internal.android

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.internal.settlement.DirectFatalSlot
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationDisposition
import io.screenstream.engine.internal.settlement.OperationDomain
import io.screenstream.engine.internal.settlement.OperationEntryDisposition
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.OperationReturnDisposition
import io.screenstream.engine.internal.settlement.OperationSubmissionDisposition
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed class AndroidLaneStartupResult {
    object Pending : AndroidLaneStartupResult()

    class Ready : AndroidLaneStartupResult() {
        private val recordedHandler = AtomicReference<Handler?>(null)

        internal val handler: Handler
            get() = checkNotNull(recordedHandler.get())

        internal fun record(handler: Handler): Boolean = recordedHandler.compareAndSet(null, handler)
    }

    class Failed : AndroidLaneStartupResult() {
        private val recordedCause = AtomicReference<Throwable?>(null)

        internal val cause: Throwable
            get() = checkNotNull(recordedCause.get())

        internal fun record(raw: Throwable): Boolean = recordedCause.compareAndSet(null, raw)
    }
}

internal sealed class AndroidLaneQuitOutcome {
    class Returned(internal val accepted: Boolean) : AndroidLaneQuitOutcome()

    class Thrown : AndroidLaneQuitOutcome() {
        private val recordedCause = AtomicReference<Throwable?>(null)

        internal val cause: Throwable
            get() = checkNotNull(recordedCause.get())

        internal fun record(raw: Throwable): Boolean = recordedCause.compareAndSet(null, raw)
    }
}

internal class AndroidLaneTerminationReceipt private constructor(
    internal val lane: AndroidLaneRuntime,
    private val workerIdentity: AndroidLaneWorkerIdentity,
) {
    internal companion object {
        internal fun create(
            lane: AndroidLaneRuntime,
            workerIdentity: AndroidLaneWorkerIdentity,
        ): AndroidLaneTerminationReceipt = AndroidLaneTerminationReceipt(lane, workerIdentity)
    }

    internal fun matchesWorker(identity: AndroidLaneWorkerIdentity): Boolean =
        workerIdentity === identity && identity.lane === lane
}

internal class AndroidLaneWorkerIdentity private constructor(
    internal val lane: AndroidLaneRuntime,
) {
    internal companion object {
        internal fun create(lane: AndroidLaneRuntime): AndroidLaneWorkerIdentity =
            AndroidLaneWorkerIdentity(lane)
    }
}

internal class AndroidLaneRuntimeNeverStartedProof private constructor(
    internal val lane: AndroidLaneRuntime,
    internal val workerIdentity: AndroidLaneWorkerIdentity,
) {
    internal companion object {
        internal fun create(
            lane: AndroidLaneRuntime,
            workerIdentity: AndroidLaneWorkerIdentity,
        ): AndroidLaneRuntimeNeverStartedProof = AndroidLaneRuntimeNeverStartedProof(lane, workerIdentity)
    }
}

internal enum class AndroidPostPhysicalDisposition {
    NotOnStack,
    OnStack,
    Returned,
}

internal enum class AndroidPostResult {
    Accepted,
    Rejected,
    NotSubmitted,
}

internal enum class AndroidPostFailureExposure {
    None,
    AuthoritativeRejection,
    AcceptanceAmbiguous,
}

internal fun interface AndroidEnteredWork {
    fun run(handler: Handler)
}

internal sealed interface AndroidNoPlatformEntryProof<R : OperationEvidence> {
    val operation: OperationOccurrence<R>
}

internal class AndroidOccurrenceNoPlatformEntryProof<R : OperationEvidence> internal constructor(
    override val operation: OperationOccurrence<R>,
) : AndroidNoPlatformEntryProof<R>

internal class AndroidReturnedWithoutPlatformEntryProof<R : OperationEvidence> internal constructor(
    internal val ticket: AndroidPostTicket<R>,
    override val operation: OperationOccurrence<R>,
) : AndroidNoPlatformEntryProof<R> {
    private val activated = AtomicBoolean(false)

    internal fun activateLocked(): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (activated.get()) return true
        if (ticket.occurrence !== operation ||
            ticket.physicalState != AndroidPostPhysicalDisposition.Returned ||
            operation.entryDisposition != OperationEntryDisposition.Cancelled ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        ) return false
        val exactSubmission = operation.submissionDisposition == OperationSubmissionDisposition.Accepted &&
            ticket.postFailureResidue == null && operation.submissionFailure == null &&
            operation.submissionAmbiguousFatal == null &&
            ticket.failureExposure == AndroidPostFailureExposure.None
        return exactSubmission && activated.compareAndSet(false, true)
    }
}

internal class AndroidLanePostCutoffProof<R : OperationEvidence> internal constructor(
    internal val ticket: AndroidPostTicket<R>,
    override val operation: OperationOccurrence<R>,
) : AndroidNoPlatformEntryProof<R> {
    private val cutoffObserved = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)

    internal fun recordCutoff(): Boolean = cutoffObserved.compareAndSet(false, true)

    internal fun activateLocked(): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (activated.get()) return true
        if (!cutoffObserved.get() || ticket.occurrence !== operation ||
            ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
            ticket.postFailureResidue != null ||
            operation.submissionDisposition != OperationSubmissionDisposition.None ||
            operation.entryDisposition != OperationEntryDisposition.Unentered ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty ||
            !operation.settleInertBeforeEntryLocked()
        ) return false
        return activated.compareAndSet(false, true)
    }

    internal val isActivatedExact: Boolean
        get() = activated.get()

    internal val isCutoffObservedExact: Boolean
        get() = cutoffObserved.get()
}

internal class AndroidWorkAdmissionCutoff internal constructor() {
    private sealed interface State {
        data class Open(internal val reservations: Int) : State
        data class Closed(internal val reservations: Int) : State
    }

    private val state = AtomicReference<State>(State.Open(0))

    internal fun reserve(): Boolean {
        while (true) {
            val exact = state.get()
            if (exact !is State.Open || exact.reservations == Int.MAX_VALUE) return false
            if (state.compareAndSet(exact, State.Open(exact.reservations + 1))) return true
        }
    }

    internal fun release() {
        while (true) {
            val exact = state.get()
            val replacement = when (exact) {
                is State.Open -> State.Open(exact.reservations - 1).also { check(exact.reservations > 0) }
                is State.Closed -> State.Closed(exact.reservations - 1).also { check(exact.reservations > 0) }
            }
            if (state.compareAndSet(exact, replacement)) return
        }
    }

    internal fun close(): Boolean {
        while (true) {
            val exact = state.get()
            if (exact is State.Closed) return false
            check(exact is State.Open)
            if (state.compareAndSet(exact, State.Closed(exact.reservations))) return true
        }
    }

    internal val isClosedAndUnreserved: Boolean
        get() = state.get() == State.Closed(0)
}

internal class AndroidOwnerPostCutoffProof<R : OperationEvidence> internal constructor(
    private val cutoff: AndroidWorkAdmissionCutoff,
    internal val ticket: AndroidPostTicket<R>,
    override val operation: OperationOccurrence<R>,
) : AndroidNoPlatformEntryProof<R> {
    private val activated = AtomicBoolean(false)

    internal fun activateLocked(): Boolean {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (activated.get()) return true
        if (!cutoff.isClosedAndUnreserved || ticket.occurrence !== operation ||
            ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
            ticket.postFailureResidue != null ||
            operation.submissionDisposition != OperationSubmissionDisposition.None ||
            operation.entryDisposition != OperationEntryDisposition.Unentered ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty ||
            !operation.settleInertBeforeEntryLocked()
        ) return false
        return activated.compareAndSet(false, true)
    }
}

internal class AndroidFinalLaneNoEntryProof<R : OperationEvidence> private constructor(
    internal val lane: AndroidLaneRuntime,
    internal val workerIdentity: AndroidLaneWorkerIdentity,
    internal val terminationReceipt: AndroidLaneTerminationReceipt,
    internal val ticket: AndroidPostTicket<R>,
    internal val operationIdentity: Long,
    override val operation: OperationOccurrence<R>,
) : AndroidNoPlatformEntryProof<R> {
    internal companion object {
        internal fun <R : OperationEvidence> create(
            lane: AndroidLaneRuntime,
            workerIdentity: AndroidLaneWorkerIdentity,
            terminationReceipt: AndroidLaneTerminationReceipt,
            ticket: AndroidPostTicket<R>,
            operation: OperationOccurrence<R>,
        ): AndroidFinalLaneNoEntryProof<R> = AndroidFinalLaneNoEntryProof(
            lane = lane,
            workerIdentity = workerIdentity,
            terminationReceipt = terminationReceipt,
            ticket = ticket,
            operationIdentity = operation.identity,
            operation = operation,
        )
    }
}

internal class AndroidPostTicket<R : OperationEvidence> internal constructor(
    internal val lane: AndroidLaneRuntime,
    internal val workerIdentity: AndroidLaneWorkerIdentity,
    internal val terminationReceipt: AndroidLaneTerminationReceipt,
    internal val occurrence: OperationOccurrence<R>,
    internal val postRejectedCause: RejectedExecutionException,
    internal val enteredWork: AndroidEnteredWork,
) {
    internal val operationIdentity: Long = occurrence.identity
    private val physicalDisposition = AtomicReference(AndroidPostPhysicalDisposition.NotOnStack)
    private val rawPostFailure = AtomicReference<Throwable?>(null)
    private val postFailureExposure = AtomicReference(AndroidPostFailureExposure.None)

    internal val finalLaneNoEntryProof: AndroidFinalLaneNoEntryProof<R> =
        AndroidFinalLaneNoEntryProof.create(
            lane = lane,
            workerIdentity = workerIdentity,
            terminationReceipt = terminationReceipt,
            ticket = this,
            operation = occurrence,
        )
    internal val authoritativePostCutoffProof = AndroidLanePostCutoffProof(this, occurrence)
    internal val returnedWithoutPlatformEntryProof = AndroidReturnedWithoutPlatformEntryProof(this, occurrence)

    internal val runnable = Runnable { lane.runTicket(this) }

    internal val physicalState: AndroidPostPhysicalDisposition
        get() = physicalDisposition.get()

    internal val postFailureResidue: Throwable?
        get() = rawPostFailure.get()

    internal val failureExposure: AndroidPostFailureExposure
        get() = postFailureExposure.get()

    internal fun recordPostFailure(raw: Throwable, exposure: AndroidPostFailureExposure): Boolean {
        if (!rawPostFailure.compareAndSet(null, raw)) return false
        check(postFailureExposure.compareAndSet(AndroidPostFailureExposure.None, exposure))
        return true
    }

    internal fun markOnStack(): Boolean =
        physicalDisposition.compareAndSet(
            AndroidPostPhysicalDisposition.NotOnStack,
            AndroidPostPhysicalDisposition.OnStack,
        )

    internal fun markReturned(): Boolean =
        physicalDisposition.compareAndSet(
            AndroidPostPhysicalDisposition.OnStack,
            AndroidPostPhysicalDisposition.Returned,
        )
}

internal enum class AndroidListenerSentinelSubmissionDisposition {
    None,
    Submitting,
    Accepted,
    Rejected,
}

private enum class AndroidListenerSentinelLinearState {
    Idle,
    CutoffBeforeSubmission,
    Submitting,
    Accepted,
    OnStackWhileSubmitting,
    EnteredWhileSubmitting,
    ReturnedWhileSubmitting,
    ReturnedBeforeEntryWhileSubmitting,
    OnStackAccepted,
    EnteredAccepted,
    ReturnedAccepted,
    ReturnedBeforeEntryAccepted,
    RejectedFinal,
    RejectedOnStackFinal,
    RejectedReturnedFinal,
    RejectedAwaitingEntry,
    OnStackAwaitingEntry,
    EnteredAfterRejection,
    ReturnedAfterRejection,
    ReturnedBeforeEntryAfterRejection,
}

internal enum class AndroidListenerSentinelMechanicalDisposition {
    Pending,
    Accepted,
    RejectedFinal,
    AwaitingEntry,
    DefinitelyUnentered,
}

internal fun interface AndroidListenerSentinelFinalWork {
    fun run(disposition: AndroidListenerSentinelMechanicalDisposition, postFailureResidue: Throwable?)
}

internal class AndroidListenerSentinelTicket internal constructor(
    internal val lane: AndroidLaneRuntime,
    internal val workerIdentity: AndroidLaneWorkerIdentity,
    internal val terminationReceipt: AndroidLaneTerminationReceipt,
    internal val operationIdentity: Long,
    internal val postRejectedCause: RejectedExecutionException,
    internal val enteredWork: AndroidEnteredWork,
    private val finalWork: AndroidListenerSentinelFinalWork,
) {
    private val stateGate = ReentrantLock()
    private var linearState = AndroidListenerSentinelLinearState.Idle
    private var rawPostFailure: Throwable? = null
    private var postFailureExposure = AndroidPostFailureExposure.None
    private var rawExecutionFailure: Throwable? = null
    private var finalWorkDispatched: Boolean = false

    internal val runnable = Runnable { lane.runListenerSentinel(this) }
    internal val submissionDisposition: AndroidListenerSentinelSubmissionDisposition
        get() = stateGate.withLock { when (linearState) {
            AndroidListenerSentinelLinearState.Idle,
            AndroidListenerSentinelLinearState.CutoffBeforeSubmission,
                -> AndroidListenerSentinelSubmissionDisposition.None
            AndroidListenerSentinelLinearState.Submitting,
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting,
                -> AndroidListenerSentinelSubmissionDisposition.Submitting
            AndroidListenerSentinelLinearState.Accepted,
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedWhileSubmitting,
            AndroidListenerSentinelLinearState.OnStackAccepted,
            AndroidListenerSentinelLinearState.EnteredAccepted,
            AndroidListenerSentinelLinearState.ReturnedAccepted,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted,
            AndroidListenerSentinelLinearState.EnteredAfterRejection,
            AndroidListenerSentinelLinearState.ReturnedAfterRejection,
                -> AndroidListenerSentinelSubmissionDisposition.Accepted
            AndroidListenerSentinelLinearState.RejectedFinal,
            AndroidListenerSentinelLinearState.RejectedOnStackFinal,
            AndroidListenerSentinelLinearState.RejectedReturnedFinal,
            AndroidListenerSentinelLinearState.RejectedAwaitingEntry,
            AndroidListenerSentinelLinearState.OnStackAwaitingEntry,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection,
                -> AndroidListenerSentinelSubmissionDisposition.Rejected
        } }
    internal val mechanicalDisposition: AndroidListenerSentinelMechanicalDisposition
        get() = stateGate.withLock { when (linearState) {
            AndroidListenerSentinelLinearState.Idle,
            AndroidListenerSentinelLinearState.Submitting,
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting,
                -> AndroidListenerSentinelMechanicalDisposition.Pending
            AndroidListenerSentinelLinearState.CutoffBeforeSubmission ->
                AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered
            AndroidListenerSentinelLinearState.Accepted,
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedWhileSubmitting,
            AndroidListenerSentinelLinearState.OnStackAccepted,
            AndroidListenerSentinelLinearState.EnteredAccepted,
            AndroidListenerSentinelLinearState.ReturnedAccepted,
            AndroidListenerSentinelLinearState.EnteredAfterRejection,
            AndroidListenerSentinelLinearState.ReturnedAfterRejection,
                -> AndroidListenerSentinelMechanicalDisposition.Accepted
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection,
                -> AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered
            AndroidListenerSentinelLinearState.RejectedFinal,
            AndroidListenerSentinelLinearState.RejectedOnStackFinal,
            AndroidListenerSentinelLinearState.RejectedReturnedFinal,
                ->
                AndroidListenerSentinelMechanicalDisposition.RejectedFinal
            AndroidListenerSentinelLinearState.RejectedAwaitingEntry,
            AndroidListenerSentinelLinearState.OnStackAwaitingEntry,
                ->
                AndroidListenerSentinelMechanicalDisposition.AwaitingEntry
        } }
    internal val physicalState: AndroidPostPhysicalDisposition
        get() = stateGate.withLock { when (linearState) {
            AndroidListenerSentinelLinearState.Idle,
            AndroidListenerSentinelLinearState.CutoffBeforeSubmission,
            AndroidListenerSentinelLinearState.Submitting,
            AndroidListenerSentinelLinearState.Accepted,
            AndroidListenerSentinelLinearState.RejectedFinal,
            AndroidListenerSentinelLinearState.RejectedAwaitingEntry,
                -> AndroidPostPhysicalDisposition.NotOnStack
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting,
            AndroidListenerSentinelLinearState.OnStackAccepted,
            AndroidListenerSentinelLinearState.RejectedOnStackFinal,
            AndroidListenerSentinelLinearState.OnStackAwaitingEntry,
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting,
            AndroidListenerSentinelLinearState.EnteredAccepted,
            AndroidListenerSentinelLinearState.EnteredAfterRejection,
                -> AndroidPostPhysicalDisposition.OnStack
            AndroidListenerSentinelLinearState.ReturnedWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedAccepted,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted,
            AndroidListenerSentinelLinearState.RejectedReturnedFinal,
            AndroidListenerSentinelLinearState.ReturnedAfterRejection,
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection,
                -> AndroidPostPhysicalDisposition.Returned
        } }
    internal val postFailureResidue: Throwable?
        get() = stateGate.withLock { rawPostFailure }
    internal val failureExposure: AndroidPostFailureExposure
        get() = stateGate.withLock { postFailureExposure }
    internal val enteredLane: Boolean
        get() = stateGate.withLock { when (linearState) {
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting,
            AndroidListenerSentinelLinearState.ReturnedWhileSubmitting,
            AndroidListenerSentinelLinearState.EnteredAccepted,
            AndroidListenerSentinelLinearState.ReturnedAccepted,
            AndroidListenerSentinelLinearState.EnteredAfterRejection,
            AndroidListenerSentinelLinearState.ReturnedAfterRejection,
                -> true
            else -> false
        } }
    internal val executionFailure: Throwable?
        get() = stateGate.withLock { rawExecutionFailure }

    internal fun beginSubmission(): Boolean = stateGate.withLock {
        if (linearState != AndroidListenerSentinelLinearState.Idle) return@withLock false
        linearState = AndroidListenerSentinelLinearState.Submitting
        true
    }

    internal fun recordCutoffBeforeSubmission(): Boolean = stateGate.withLock {
        if (linearState != AndroidListenerSentinelLinearState.Idle) return@withLock false
        linearState = AndroidListenerSentinelLinearState.CutoffBeforeSubmission
        true
    }

    internal fun publishAccepted(): Boolean = stateGate.withLock {
        linearState = when (linearState) {
            AndroidListenerSentinelLinearState.Submitting -> AndroidListenerSentinelLinearState.Accepted
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting ->
                AndroidListenerSentinelLinearState.OnStackAccepted
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting ->
                AndroidListenerSentinelLinearState.EnteredAccepted
            AndroidListenerSentinelLinearState.ReturnedWhileSubmitting ->
                AndroidListenerSentinelLinearState.ReturnedAccepted
            AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting ->
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted
            else -> return@withLock false
        }
        true
    }

    internal fun recordPostFailure(raw: Throwable, exposure: AndroidPostFailureExposure): Boolean {
        return stateGate.withLock {
            if (rawPostFailure != null) return@withLock false
            val updated = when (linearState) {
                AndroidListenerSentinelLinearState.Submitting ->
                    if (exposure == AndroidPostFailureExposure.AuthoritativeRejection) {
                        AndroidListenerSentinelLinearState.RejectedFinal
                    } else {
                        AndroidListenerSentinelLinearState.RejectedAwaitingEntry
                    }
                AndroidListenerSentinelLinearState.OnStackWhileSubmitting ->
                    if (exposure == AndroidPostFailureExposure.AuthoritativeRejection) {
                        AndroidListenerSentinelLinearState.RejectedOnStackFinal
                    } else {
                        AndroidListenerSentinelLinearState.OnStackAwaitingEntry
                    }
                AndroidListenerSentinelLinearState.EnteredWhileSubmitting ->
                    AndroidListenerSentinelLinearState.EnteredAfterRejection
                AndroidListenerSentinelLinearState.ReturnedWhileSubmitting ->
                    AndroidListenerSentinelLinearState.ReturnedAfterRejection
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting ->
                    AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection
                else -> return@withLock false
            }
            rawPostFailure = raw
            postFailureExposure = exposure
            linearState = updated
            true
        }
    }

    internal fun markOnStack(): Boolean = stateGate.withLock {
        linearState = when (linearState) {
            AndroidListenerSentinelLinearState.Submitting ->
                AndroidListenerSentinelLinearState.OnStackWhileSubmitting
            AndroidListenerSentinelLinearState.Accepted -> AndroidListenerSentinelLinearState.OnStackAccepted
            AndroidListenerSentinelLinearState.RejectedAwaitingEntry ->
                AndroidListenerSentinelLinearState.OnStackAwaitingEntry
            else -> return@withLock false
        }
        true
    }

    internal fun markEntered(): Boolean = stateGate.withLock {
        linearState = when (linearState) {
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting ->
                AndroidListenerSentinelLinearState.EnteredWhileSubmitting
            AndroidListenerSentinelLinearState.OnStackAccepted -> AndroidListenerSentinelLinearState.EnteredAccepted
            AndroidListenerSentinelLinearState.OnStackAwaitingEntry ->
                AndroidListenerSentinelLinearState.EnteredAfterRejection
            else -> return@withLock false
        }
        true
    }

    internal fun recordExecutionFailure(raw: Throwable): Boolean = stateGate.withLock {
        if (rawExecutionFailure != null) return@withLock false
        rawExecutionFailure = raw
        true
    }

    internal fun markReturned(): Boolean = stateGate.withLock {
        linearState = when (linearState) {
            AndroidListenerSentinelLinearState.EnteredWhileSubmitting ->
                AndroidListenerSentinelLinearState.ReturnedWhileSubmitting
            AndroidListenerSentinelLinearState.EnteredAccepted -> AndroidListenerSentinelLinearState.ReturnedAccepted
            AndroidListenerSentinelLinearState.EnteredAfterRejection ->
                AndroidListenerSentinelLinearState.ReturnedAfterRejection
            AndroidListenerSentinelLinearState.OnStackWhileSubmitting ->
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryWhileSubmitting
            AndroidListenerSentinelLinearState.OnStackAccepted ->
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted
            AndroidListenerSentinelLinearState.OnStackAwaitingEntry ->
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection
            AndroidListenerSentinelLinearState.RejectedOnStackFinal ->
                AndroidListenerSentinelLinearState.RejectedReturnedFinal
            else -> return@withLock false
        }
        true
    }

    internal fun dispatchFinalWork() {
        var exactDisposition: AndroidListenerSentinelMechanicalDisposition? = null
        var exactFailure: Throwable? = null
        stateGate.withLock {
            if (finalWorkDispatched) return
            exactDisposition = when (linearState) {
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted,
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection,
                    -> AndroidListenerSentinelMechanicalDisposition.DefinitelyUnentered
                AndroidListenerSentinelLinearState.ReturnedAccepted,
                AndroidListenerSentinelLinearState.ReturnedAfterRejection,
                    -> AndroidListenerSentinelMechanicalDisposition.Accepted
                else -> return
            }
            exactFailure = rawPostFailure
            finalWorkDispatched = true
        }
        val disposition = exactDisposition ?: return
        finalWork.run(disposition, exactFailure)
    }

    internal fun foldFinalLaneNoEntry(receipt: AndroidLaneTerminationReceipt): Boolean {
        if (receipt !== terminationReceipt || receipt.lane !== lane ||
            !receipt.matchesWorker(workerIdentity) || !lane.acceptsTerminationReceipt(receipt)
        ) return false
        val folded = stateGate.withLock {
            linearState = when (linearState) {
                AndroidListenerSentinelLinearState.Accepted,
                AndroidListenerSentinelLinearState.OnStackAccepted,
                    -> AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted
                AndroidListenerSentinelLinearState.RejectedAwaitingEntry,
                AndroidListenerSentinelLinearState.OnStackAwaitingEntry,
                    -> AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAccepted,
                AndroidListenerSentinelLinearState.ReturnedBeforeEntryAfterRejection -> return@withLock true
                else -> return@withLock false
            }
            true
        }
        if (folded) dispatchFinalWork()
        return folded
    }
}

internal class AndroidLaneRuntime(
    private val settlementSignal: SettlementSignal,
    threadName: String = "ScreenCaptureEngine-Android",
) {
    private val startRequested = AtomicBoolean(false)
    private val threadRunEntered = AtomicBoolean(false)
    private val startFailedBeforeRun = AtomicBoolean(false)
    private val quitRequested = AtomicBoolean(false)
    private val startup = AtomicReference<AndroidLaneStartupResult>(AndroidLaneStartupResult.Pending)
    private val startupReady = AndroidLaneStartupResult.Ready()
    private val startFailure = AndroidLaneStartupResult.Failed()
    private val looperFailure = AndroidLaneStartupResult.Failed()
    private val threadReturnCauseCell = AtomicReference<Throwable?>(null)
    private val ownedWorkerIdentity = AndroidLaneWorkerIdentity.create(this)
    private val ownedNeverStartedProof = AndroidLaneRuntimeNeverStartedProof.create(this, ownedWorkerIdentity)
    private val ownedTerminationReceipt = AndroidLaneTerminationReceipt.create(this, ownedWorkerIdentity)
    private val publishedTerminationReceipt = AtomicReference<AndroidLaneTerminationReceipt?>(null)
    private val quitOutcome = AtomicReference<AndroidLaneQuitOutcome?>(null)
    private val quitReturnedAccepted = AndroidLaneQuitOutcome.Returned(true)
    private val quitReturnedRejected = AndroidLaneQuitOutcome.Returned(false)
    private val quitFailure = AndroidLaneQuitOutcome.Thrown()
    private val ordinaryLaneFailure = AtomicReference<Exception?>(null)
    private val fatalSlot = DirectFatalSlot()
    private val laneState = AtomicLong(0L)
    private val reservationOverflowCause =
        IllegalStateException("Android lane transition reservation overflow")
    private val reservationUnderflowCause =
        IllegalStateException("Android lane transition reservation underflow")

    private val handlerThread = object : HandlerThread(threadName) {
        override fun onLooperPrepared() {
            try {
                val handler = Handler(checkNotNull(Looper.myLooper()))
                check(startupReady.record(handler))
                startup.compareAndSet(AndroidLaneStartupResult.Pending, startupReady)
                signalBestEffort()
            } catch (raw: Throwable) {
                publishFatalFirst(raw)
                looperFailure.record(raw)
                startup.compareAndSet(AndroidLaneStartupResult.Pending, looperFailure)
                failCloseLane()
                signalBestEffort()
                FatalThrowablePolicy.rethrow(raw)
            }
        }

        override fun run() {
            threadRunEntered.set(true)
            var escaped: Throwable? = null
            try {
                super.run()
            } catch (raw: Throwable) {
                escaped = raw
                publishFatalFirst(raw)
                if (!FatalThrowablePolicy.isDirectFatal(raw)) {
                    ordinaryLaneFailure.compareAndSet(null, raw as Exception)
                }
                failCloseLane()
                signalBestEffort()
                FatalThrowablePolicy.rethrow(raw)
            } finally {
                threadReturnCauseCell.set(escaped)
                if (publishedTerminationReceipt.compareAndSet(null, ownedTerminationReceipt)) {
                    signalBestEffort()
                }
            }
        }
    }

    init {
        require(threadName.isNotBlank())
    }

    internal val startupResult: AndroidLaneStartupResult
        get() = startup.get()

    internal val observedFatal: Throwable?
        get() = fatalSlot.current

    internal val observedOrdinaryLaneFailure: Exception?
        get() = ordinaryLaneFailure.get()

    internal val isPoisoned: Boolean
        get() = laneState.get() and PHASE_MASK == POISONED

    internal val hasThreadReturned: Boolean
        get() = terminationReceipt != null

    internal val terminationReceipt: AndroidLaneTerminationReceipt?
        get() = publishedTerminationReceipt.get()

    internal val threadReturnCause: Throwable?
        get() = if (terminationReceipt != null) threadReturnCauseCell.get() else null

    internal fun acceptsTerminationReceipt(receipt: AndroidLaneTerminationReceipt): Boolean =
        receipt === ownedTerminationReceipt &&
                receipt.lane === this &&
                receipt.matchesWorker(ownedWorkerIdentity)

    internal val observedQuitOutcome: AndroidLaneQuitOutcome?
        get() = quitOutcome.get()

    internal fun start(): Boolean {
        if (!startRequested.compareAndSet(false, true)) return false
        try {
            handlerThread.start()
        } catch (raw: Throwable) {
            startFailedBeforeRun.set(true)
            publishFatalFirst(raw)
            startFailure.record(raw)
            startup.compareAndSet(AndroidLaneStartupResult.Pending, startFailure)
            failCloseLane()
            signalBestEffort()
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return false
        }
        return true
    }

    internal fun <R : OperationEvidence> ticket(
        occurrence: OperationOccurrence<R>,
        postRejectionMessage: String,
        enteredWork: AndroidEnteredWork,
    ): AndroidPostTicket<R> = AndroidPostTicket(
        lane = this,
        workerIdentity = ownedWorkerIdentity,
        terminationReceipt = ownedTerminationReceipt,
        occurrence = occurrence,
        postRejectedCause = RejectedExecutionException(postRejectionMessage),
        enteredWork = enteredWork,
    )

    internal fun listenerSentinelTicket(
        operationIdentity: Long,
        postRejectionMessage: String,
        enteredWork: AndroidEnteredWork,
        finalWork: AndroidListenerSentinelFinalWork,
    ): AndroidListenerSentinelTicket = AndroidListenerSentinelTicket(
        lane = this,
        workerIdentity = ownedWorkerIdentity,
        terminationReceipt = ownedTerminationReceipt,
        operationIdentity = operationIdentity,
        postRejectedCause = RejectedExecutionException(postRejectionMessage),
        enteredWork = enteredWork,
        finalWork = finalWork,
    )

    internal fun <R : OperationEvidence> proveFinalLaneNoEntryLocked(
        receipt: AndroidLaneTerminationReceipt,
        ticket: AndroidPostTicket<R>,
        operation: OperationOccurrence<R>,
    ): AndroidFinalLaneNoEntryProof<R>? {
        check(operation.settlementGate.isHeldByCurrentThread)
        val proof = observeFinalLaneNoEntryLocked(receipt, ticket, operation) ?: return null
        if (!operation.settleInertBeforeEntryLocked()) return null
        return proof
    }

    internal fun <R : OperationEvidence> observeFinalLaneNoEntryLocked(
        receipt: AndroidLaneTerminationReceipt,
        ticket: AndroidPostTicket<R>,
        operation: OperationOccurrence<R>,
    ): AndroidFinalLaneNoEntryProof<R>? {
        check(operation.settlementGate.isHeldByCurrentThread)
        if (publishedTerminationReceipt.get() !== receipt ||
            !acceptsTerminationReceipt(receipt) ||
            ticket.lane !== this ||
            ticket.workerIdentity !== ownedWorkerIdentity ||
            ticket.terminationReceipt !== receipt ||
            ticket.occurrence !== operation ||
            ticket.operationIdentity != operation.identity ||
            ticket.finalLaneNoEntryProof.operation !== operation ||
            ticket.finalLaneNoEntryProof.operationIdentity != operation.identity ||
            ticket.finalLaneNoEntryProof.ticket !== ticket ||
            ticket.finalLaneNoEntryProof.lane !== this ||
            ticket.finalLaneNoEntryProof.workerIdentity !== ownedWorkerIdentity ||
            ticket.finalLaneNoEntryProof.terminationReceipt !== receipt ||
            ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
            operation.submissionAmbiguousFatal != null ||
            operation.entryDisposition != OperationEntryDisposition.Unentered ||
            !acceptsFinalNoEntrySubmission(ticket, operation) ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return null
        }
        return ticket.finalLaneNoEntryProof
    }

    internal fun <R : OperationEvidence> proveFinalLaneNoEntryAfterCancellationLocked(
        receipt: AndroidLaneTerminationReceipt,
        ticket: AndroidPostTicket<R>,
        operation: OperationOccurrence<R>,
    ): AndroidFinalLaneNoEntryProof<R>? {
        check(operation.settlementGate.isHeldByCurrentThread)
        val exactSubmission = when (operation.submissionDisposition) {
            OperationSubmissionDisposition.Accepted ->
                ticket.postFailureResidue == null && ticket.failureExposure == AndroidPostFailureExposure.None &&
                    operation.submissionFailure == null && operation.submissionAmbiguousFatal == null &&
                    (operation.disposition == OperationDisposition.Cancelled ||
                        operation.disposition == OperationDisposition.DeadlineGuardFailed)

            OperationSubmissionDisposition.Rejected ->
                ticket.postFailureResidue != null &&
                    operation.submissionFailure === ticket.postFailureResidue &&
                    operation.submissionAmbiguousFatal == null &&
                    operation.disposition == OperationDisposition.SchedulerRejected

            else -> false
        }
        if (!exactSubmission || publishedTerminationReceipt.get() !== receipt ||
            !acceptsTerminationReceipt(receipt) || ticket.lane !== this ||
            ticket.workerIdentity !== ownedWorkerIdentity || ticket.terminationReceipt !== receipt ||
            ticket.occurrence !== operation || ticket.operationIdentity != operation.identity ||
            ticket.finalLaneNoEntryProof.operation !== operation ||
            ticket.finalLaneNoEntryProof.operationIdentity != operation.identity ||
            ticket.finalLaneNoEntryProof.ticket !== ticket || ticket.finalLaneNoEntryProof.lane !== this ||
            ticket.finalLaneNoEntryProof.workerIdentity !== ownedWorkerIdentity ||
            ticket.finalLaneNoEntryProof.terminationReceipt !== receipt ||
            ticket.physicalState != AndroidPostPhysicalDisposition.NotOnStack ||
            operation.entryDisposition != OperationEntryDisposition.Cancelled ||
            operation.returnCell.disposition != OperationReturnDisposition.Empty
        ) return null
        return ticket.finalLaneNoEntryProof
    }

    private fun acceptsFinalNoEntrySubmission(
        ticket: AndroidPostTicket<*>,
        operation: OperationOccurrence<*>,
    ): Boolean = when (operation.submissionDisposition) {
        OperationSubmissionDisposition.Accepted ->
            ticket.postFailureResidue == null && operation.submissionFailure == null &&
                    (operation.disposition == OperationDisposition.Pending ||
                            operation.disposition == OperationDisposition.Cleanup)

        OperationSubmissionDisposition.Rejected ->
            ticket.postFailureResidue != null && operation.submissionFailure === ticket.postFailureResidue &&
                    (operation.domain == OperationDomain.Active &&
                            operation.disposition == OperationDisposition.SchedulerRejected ||
                            operation.domain == OperationDomain.Cleanup &&
                            operation.disposition == OperationDisposition.Cleanup)

        else -> false
    }

    internal fun proveNeverStarted(): AndroidLaneRuntimeNeverStartedProof? =
        if (!threadRunEntered.get() && (!startRequested.get() || startFailedBeforeRun.get())) {
            ownedNeverStartedProof
        } else {
            null
        }

    internal fun post(ticket: AndroidPostTicket<*>): AndroidPostResult {
        if (ticket.lane !== this) return AndroidPostResult.NotSubmitted
        if (!reservePostTransition()) {
            ticket.authoritativePostCutoffProof.recordCutoff()
            return AndroidPostResult.NotSubmitted
        }
        val submissionStarted = try {
            ticket.occurrence.beginSubmission()
        } finally {
            releaseTransitionReservation()
        }
        if (!submissionStarted) return AndroidPostResult.NotSubmitted

        val handler = (startup.get() as? AndroidLaneStartupResult.Ready)?.handler
        if (handler == null) {
            val failure = (startup.get() as? AndroidLaneStartupResult.Failed)?.cause
                ?: ticket.postRejectedCause
            if (failure is Exception) {
                failClosePostException(ticket, failure, AndroidPostFailureExposure.AuthoritativeRejection)
                signalBestEffort()
            } else {
                publishFatalFirst(failure)
                failClosePostDirectFatal(ticket, failure)
                signalBestEffort()
            }
            return AndroidPostResult.Rejected
        }

        return try {
            if (handler.post(ticket.runnable)) {
                ticket.occurrence.publishSubmissionAccepted()
                signalBestEffort()
                AndroidPostResult.Accepted
            } else {
                failClosePostException(
                    ticket,
                    ticket.postRejectedCause,
                    AndroidPostFailureExposure.AuthoritativeRejection,
                )
                signalBestEffort()
                AndroidPostResult.Rejected
            }
        } catch (failure: Exception) {
            failClosePostException(ticket, failure, AndroidPostFailureExposure.AcceptanceAmbiguous)
            signalBestEffort()
            AndroidPostResult.Rejected
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            failClosePostDirectFatal(ticket, raw)
            signalBestEffort()
            FatalThrowablePolicy.rethrow(raw)
        }
    }

    internal fun post(ticket: AndroidListenerSentinelTicket): AndroidPostResult {
        if (ticket.lane !== this) return AndroidPostResult.NotSubmitted
        if (!reservePostTransition()) {
            ticket.recordCutoffBeforeSubmission()
            return AndroidPostResult.NotSubmitted
        }
        val submissionStarted = try {
            ticket.beginSubmission()
        } finally {
            releaseTransitionReservation()
        }
        if (!submissionStarted) return AndroidPostResult.NotSubmitted

        val handler = (startup.get() as? AndroidLaneStartupResult.Ready)?.handler
        if (handler == null) {
            val failure = (startup.get() as? AndroidLaneStartupResult.Failed)?.cause
                ?: ticket.postRejectedCause
            if (failure is Exception) {
                failCloseSentinelPostException(ticket, failure, AndroidPostFailureExposure.AuthoritativeRejection)
            } else {
                publishFatalFirst(failure)
                failCloseSentinelPostFatal(ticket, failure)
            }
            ticket.dispatchFinalWork()
            signalBestEffort()
            return AndroidPostResult.Rejected
        }

        return try {
            if (handler.post(ticket.runnable)) {
                check(ticket.publishAccepted())
                ticket.dispatchFinalWork()
                signalBestEffort()
                AndroidPostResult.Accepted
            } else {
                failCloseSentinelPostException(
                    ticket,
                    ticket.postRejectedCause,
                    AndroidPostFailureExposure.AuthoritativeRejection,
                )
                ticket.dispatchFinalWork()
                signalBestEffort()
                AndroidPostResult.Rejected
            }
        } catch (failure: Exception) {
            failCloseSentinelPostException(ticket, failure, AndroidPostFailureExposure.AcceptanceAmbiguous)
            ticket.dispatchFinalWork()
            signalBestEffort()
            AndroidPostResult.Rejected
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            failCloseSentinelPostFatal(ticket, raw)
            ticket.dispatchFinalWork()
            signalBestEffort()
            FatalThrowablePolicy.rethrow(raw)
        }
    }

    internal fun requestQuitSafely(): Boolean {
        if (!quitRequested.compareAndSet(false, true)) return false
        closeAdmission()
        return try {
            val requested = handlerThread.quitSafely()
            val returned = if (requested) quitReturnedAccepted else quitReturnedRejected
            quitOutcome.compareAndSet(null, returned)
            signalBestEffort()
            requested
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            quitFailure.record(raw)
            quitOutcome.compareAndSet(null, quitFailure)
            failCloseLane()
            signalBestEffort()
            if (FatalThrowablePolicy.isDirectFatal(raw)) {
                FatalThrowablePolicy.rethrow(raw)
            }
            false
        }
    }

    internal fun runTicket(ticket: AndroidPostTicket<*>) {
        if (ticket.lane !== this || !ticket.markOnStack()) return
        try {
            if (!reserveEntryTransition()) {
                awaitPoisonIfClosing()
                ticket.occurrence.settleInertBeforeEntry()
                signalBestEffort()
                return
            }
            val entryResult = try {
                ticket.occurrence.tryEnter()
            } finally {
                releaseTransitionReservation()
            }
            when (entryResult) {
                OperationEntryResult.Entered -> {
                    signalBestEffort()
                    val handler = (startup.get() as? AndroidLaneStartupResult.Ready)?.handler
                    checkNotNull(handler)
                    ticket.enteredWork.run(handler)
                }

                OperationEntryResult.InvalidDeadline,
                    -> {
                        ticket.occurrence.settleInertBeforeEntry()
                        signalBestEffort()
                    }

                OperationEntryResult.NotCurrent -> signalBestEffort()
            }
        } catch (failure: Exception) {
            ticket.occurrence.publishThrownReturn(failure)
            signalBestEffort()
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            failCloseLane()
            ticket.occurrence.publishDirectFatalReturn(raw)
            signalBestEffort()
            FatalThrowablePolicy.rethrow(raw)
        } finally {
            ticket.markReturned()
            signalBestEffort()
        }
    }

    internal fun runListenerSentinel(ticket: AndroidListenerSentinelTicket) {
        if (ticket.lane !== this || !ticket.markOnStack()) return
        try {
            if (!reserveEntryTransition()) {
                awaitPoisonIfClosing()
                signalBestEffort()
                return
            }
            try {
                if (!ticket.markEntered()) return
            } finally {
                releaseTransitionReservation()
            }
            val handler = (startup.get() as? AndroidLaneStartupResult.Ready)?.handler
            checkNotNull(handler)
            ticket.enteredWork.run(handler)
            signalBestEffort()
        } catch (failure: Exception) {
            ticket.recordExecutionFailure(failure)
            signalBestEffort()
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            ticket.recordExecutionFailure(raw)
            failCloseLane()
            signalBestEffort()
            FatalThrowablePolicy.rethrow(raw)
        } finally {
            ticket.markReturned()
            ticket.dispatchFinalWork()
            signalBestEffort()
        }
    }

    private fun reservePostTransition(): Boolean = reserveTransition(admissionMustBeOpen = true)

    private fun reserveEntryTransition(): Boolean = reserveTransition(admissionMustBeOpen = false)

    private fun reserveTransition(admissionMustBeOpen: Boolean): Boolean {
        while (true) {
            val state = laneState.get()
            when (state and PHASE_MASK) {
                POISONED,
                FAIL_CLOSING,
                    -> return false

                ADMISSION_CLOSED -> if (admissionMustBeOpen) return false
            }
            if (state and RESERVATION_MASK == RESERVATION_MASK) {
                routeReservationFailure(reservationOverflowCause)
                return false
            }
            if (laneState.compareAndSet(state, state + ONE_RESERVATION)) return true
        }
    }

    private fun releaseTransitionReservation() {
        while (true) {
            val state = laneState.get()
            if (state and RESERVATION_MASK == 0L) {
                routeReservationFailure(reservationUnderflowCause)
                return
            }
            if (laneState.compareAndSet(state, state - ONE_RESERVATION)) return
        }
    }

    private fun closeAdmission() {
        while (true) {
            val state = laneState.get()
            if (state and PHASE_MASK != OPEN) return
            val updated = ADMISSION_CLOSED or (state and RESERVATION_MASK)
            if (laneState.compareAndSet(state, updated)) return
        }
    }

    private fun publishFatalFirst(raw: Throwable) {
        if (FatalThrowablePolicy.isDirectFatal(raw)) fatalSlot.publish(raw)
    }

    private fun failCloseLane() {
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            publishPoisoned()
            return
        }
        awaitPoisonIfClosing()
    }

    private fun failClosePostException(
        ticket: AndroidPostTicket<*>,
        failure: Exception,
        exposure: AndroidPostFailureExposure,
    ) {
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(failure, exposure)
            try {
                ticket.occurrence.publishSubmissionFailed(failure)
            } finally {
                publishPoisoned()
            }
            return
        }

        ticket.recordPostFailure(failure, exposure)
        ticket.occurrence.publishSubmissionFailed(failure)
        awaitPoisonIfClosing()
    }

    private fun failClosePostDirectFatal(ticket: AndroidPostTicket<*>, raw: Throwable) {
        require(FatalThrowablePolicy.isDirectFatal(raw))
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(raw, AndroidPostFailureExposure.AcceptanceAmbiguous)
            try {
                ticket.occurrence.publishSubmissionAmbiguousFatal(raw)
            } finally {
                publishPoisoned()
            }
            return
        }

        ticket.recordPostFailure(raw, AndroidPostFailureExposure.AcceptanceAmbiguous)
        ticket.occurrence.publishSubmissionAmbiguousFatal(raw)
        awaitPoisonIfClosing()
    }

    private fun failCloseSentinelPostException(
        ticket: AndroidListenerSentinelTicket,
        failure: Exception,
        exposure: AndroidPostFailureExposure,
    ) {
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(failure, exposure)
            publishPoisoned()
            return
        }
        ticket.recordPostFailure(failure, exposure)
        awaitPoisonIfClosing()
    }

    private fun failCloseSentinelPostFatal(ticket: AndroidListenerSentinelTicket, raw: Throwable) {
        require(FatalThrowablePolicy.isDirectFatal(raw))
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(raw, AndroidPostFailureExposure.AcceptanceAmbiguous)
            publishPoisoned()
            return
        }
        ticket.recordPostFailure(raw, AndroidPostFailureExposure.AcceptanceAmbiguous)
        awaitPoisonIfClosing()
    }

    private fun beginFailClosing(): Boolean {
        while (true) {
            val state = laneState.get()
            when (state and PHASE_MASK) {
                FAIL_CLOSING,
                POISONED,
                    -> return false
            }
            val updated = FAIL_CLOSING or (state and RESERVATION_MASK)
            if (laneState.compareAndSet(state, updated)) return true
        }
    }

    private fun awaitReservationDrain() {
        awaitLaneState { state ->
            state and PHASE_MASK == FAIL_CLOSING && state and RESERVATION_MASK != 0L
        }
    }

    private fun awaitPoisonIfClosing() {
        awaitLaneState { state -> state and PHASE_MASK == FAIL_CLOSING }
    }

    private inline fun awaitLaneState(shouldWait: (Long) -> Boolean) {
        var restoreInterrupt = false
        while (shouldWait(laneState.get())) {
            LockSupport.parkNanos(FAILURE_WAIT_NANOS)
            if (Thread.interrupted()) restoreInterrupt = true
        }
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }

    private fun publishPoisoned() {
        while (true) {
            val state = laneState.get()
            if (state and PHASE_MASK == POISONED) return
            if (state != FAIL_CLOSING) return
            if (laneState.compareAndSet(state, POISONED)) return
        }
    }

    private fun routeReservationFailure(cause: Exception) {
        ordinaryLaneFailure.compareAndSet(null, cause)
        failCloseLane()
        signalBestEffort()
    }

    private fun signalBestEffort() {
        try {
            settlementSignal.signal()
        } catch (_: Throwable) {
            // Durable lane cells remain authoritative.
        }
    }

    private companion object {
        private const val OPEN: Long = 0L
        private const val ADMISSION_CLOSED: Long = 1L shl 62
        private const val FAIL_CLOSING: Long = Long.MIN_VALUE
        private const val POISONED: Long = Long.MIN_VALUE or ADMISSION_CLOSED
        private const val PHASE_MASK: Long = POISONED
        private const val RESERVATION_MASK: Long = ADMISSION_CLOSED - 1L
        private const val ONE_RESERVATION: Long = 1L
        private const val FAILURE_WAIT_NANOS: Long = 100_000L
    }
}
