package io.screenstream.engine.internal.android

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.screenstream.engine.internal.settlement.DirectFatalSlot
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import io.screenstream.engine.internal.settlement.OperationEntryResult
import io.screenstream.engine.internal.settlement.OperationEvidence
import io.screenstream.engine.internal.settlement.OperationOccurrence
import io.screenstream.engine.internal.settlement.SettlementSignal
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

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
) {
    internal companion object {
        internal fun create(lane: AndroidLaneRuntime): AndroidLaneTerminationReceipt =
            AndroidLaneTerminationReceipt(lane)
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

internal fun interface AndroidEnteredWork {
    fun run(handler: Handler)
}

internal class AndroidPostTicket<R : OperationEvidence> internal constructor(
    internal val lane: AndroidLaneRuntime,
    internal val occurrence: OperationOccurrence<R>,
    internal val postRejectedCause: RejectedExecutionException,
    internal val enteredWork: AndroidEnteredWork,
) {
    private val physicalDisposition = AtomicReference(AndroidPostPhysicalDisposition.NotOnStack)
    private val rawPostFailure = AtomicReference<Throwable?>(null)

    internal val runnable = Runnable { lane.runTicket(this) }

    internal val physicalState: AndroidPostPhysicalDisposition
        get() = physicalDisposition.get()

    internal val postFailureResidue: Throwable?
        get() = rawPostFailure.get()

    internal fun recordPostFailure(raw: Throwable): Boolean = rawPostFailure.compareAndSet(null, raw)

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

internal class AndroidLaneRuntime(
    private val settlementSignal: SettlementSignal,
    threadName: String = "ScreenCaptureEngine-Android",
) {
    private val startRequested = AtomicBoolean(false)
    private val quitRequested = AtomicBoolean(false)
    private val startup = AtomicReference<AndroidLaneStartupResult>(AndroidLaneStartupResult.Pending)
    private val startupReady = AndroidLaneStartupResult.Ready()
    private val startFailure = AndroidLaneStartupResult.Failed()
    private val looperFailure = AndroidLaneStartupResult.Failed()
    private val threadReturnCauseCell = AtomicReference<Throwable?>(null)
    private val ownedTerminationReceipt = AndroidLaneTerminationReceipt.create(this)
    private val publishedTerminationReceipt = AtomicReference<AndroidLaneTerminationReceipt?>(null)
    private val quitOutcome = AtomicReference<AndroidLaneQuitOutcome?>(null)
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
        receipt === ownedTerminationReceipt && receipt.lane === this

    internal val observedQuitOutcome: AndroidLaneQuitOutcome?
        get() = quitOutcome.get()

    internal fun start(): Boolean {
        if (!startRequested.compareAndSet(false, true)) return false
        try {
            handlerThread.start()
        } catch (raw: Throwable) {
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
        occurrence = occurrence,
        postRejectedCause = RejectedExecutionException(postRejectionMessage),
        enteredWork = enteredWork,
    )

    internal fun post(ticket: AndroidPostTicket<*>): AndroidPostResult {
        if (ticket.lane !== this || !reservePostTransition()) {
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
                failClosePostException(ticket, failure)
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
                failClosePostException(ticket, ticket.postRejectedCause)
                signalBestEffort()
                AndroidPostResult.Rejected
            }
        } catch (failure: Exception) {
            failClosePostException(ticket, failure)
            signalBestEffort()
            AndroidPostResult.Rejected
        } catch (raw: Throwable) {
            publishFatalFirst(raw)
            failClosePostDirectFatal(ticket, raw)
            signalBestEffort()
            FatalThrowablePolicy.rethrow(raw)
        }
    }

    internal fun requestQuitSafely(): Boolean {
        if (!quitRequested.compareAndSet(false, true)) return false
        closeAdmission()
        return try {
            val requested = handlerThread.quitSafely()
            quitOutcome.compareAndSet(null, AndroidLaneQuitOutcome.Returned(requested))
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
                OperationEntryResult.NotCurrent,
                    -> signalBestEffort()
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

    private fun failClosePostException(ticket: AndroidPostTicket<*>, failure: Exception) {
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(failure)
            try {
                ticket.occurrence.publishSubmissionFailed(failure)
            } finally {
                publishPoisoned()
            }
            return
        }

        ticket.recordPostFailure(failure)
        ticket.occurrence.publishSubmissionFailed(failure)
        awaitPoisonIfClosing()
    }

    private fun failClosePostDirectFatal(ticket: AndroidPostTicket<*>, raw: Throwable) {
        require(FatalThrowablePolicy.isDirectFatal(raw))
        val leader = beginFailClosing()
        if (leader) {
            awaitReservationDrain()
            ticket.recordPostFailure(raw)
            try {
                ticket.occurrence.publishSubmissionAmbiguousFatal(raw)
            } finally {
                publishPoisoned()
            }
            return
        }

        ticket.recordPostFailure(raw)
        ticket.occurrence.publishSubmissionAmbiguousFatal(raw)
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
