package io.screenstream.engine.internal.settlement

import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val DEADLINE_WAKE_PHASE_BITS: Int = 2
private const val DEADLINE_WAKE_PHASE_QUEUED: Long = 0L
private const val DEADLINE_WAKE_PHASE_RUNNING: Long = 1L
private const val DEADLINE_WAKE_PHASE_SUPPRESSED: Long = 2L
private const val MAX_DEADLINE_WAKE_GENERATION: Long = Long.MAX_VALUE shr DEADLINE_WAKE_PHASE_BITS

private fun deadlineWakeQueuedState(generation: Long): Long =
    generation shl DEADLINE_WAKE_PHASE_BITS or DEADLINE_WAKE_PHASE_QUEUED

private fun deadlineWakeRunningState(generation: Long): Long =
    generation shl DEADLINE_WAKE_PHASE_BITS or DEADLINE_WAKE_PHASE_RUNNING

private fun deadlineWakeSuppressedState(generation: Long): Long =
    generation shl DEADLINE_WAKE_PHASE_BITS or DEADLINE_WAKE_PHASE_SUPPRESSED

internal fun interface EngineClock {
    fun nowNanos(): Long
}

internal fun interface SettlementSignal {
    fun signal()
}

internal enum class DeadlineDisposition {
    Unarmed,
    Armed,
    Expired,
    Retired,
}

internal enum class DeadlineWakeSubmissionDisposition {
    None,
    Requested,
    Submitting,
    Accepted,
    Rejected,
}

internal enum class DeadlineWakeFireDisposition {
    Empty,
    Fired,
}

internal enum class DeadlineWakeCancellationDisposition {
    None,
    Requested,
    Cancelling,
    Succeeded,
    Failed,
    NotNeeded,
}

internal enum class DeadlineWakeSuppressionDisposition {
    NotAttempted,
    Succeeded,
    Failed,
}

internal enum class DeadlineWakeSchedulerReceiptDisposition {
    Empty,
    Returned,
}

internal enum class DeadlineWakeThrowableDisposition {
    None,
    NonfatalException,
    FatalError,
}

internal enum class DeadlineArmResult {
    Armed,
    AlreadySettled,
    InvalidClockOrOverflow,
}

internal enum class DeadlineWakeSuccessorResult {
    Requested,
    NotEligible,
    IdentityExhausted,
}

internal class DeadlineWakeLink internal constructor(
    initialGeneration: Long,
    internal val timeoutCause: Throwable,
    private val settlementGate: ReentrantLock,
    private val clock: EngineClock,
    private val signal: SettlementSignal,
) {
    init {
        require(initialGeneration in 1L..MAX_DEADLINE_WAKE_GENERATION)
    }

    internal var deadlineDisposition: DeadlineDisposition = DeadlineDisposition.Unarmed
        private set

    internal var startNanos: Long = NO_TIME
        private set

    internal var deadlineNanos: Long = NO_TIME
        private set

    internal var generation: Long = initialGeneration
        private set

    internal var submissionDisposition: DeadlineWakeSubmissionDisposition = DeadlineWakeSubmissionDisposition.None
        private set

    internal var acceptedFuture: Future<*>? = null
        private set

    internal var schedulingRejection: Throwable? = null
        private set

    internal var schedulingThrowableDisposition: DeadlineWakeThrowableDisposition = DeadlineWakeThrowableDisposition.None
        private set

    internal var fireDisposition: DeadlineWakeFireDisposition = DeadlineWakeFireDisposition.Empty
        private set

    internal var firedAtNanos: Long = NO_TIME
        private set

    internal var cancellationDisposition: DeadlineWakeCancellationDisposition =
        DeadlineWakeCancellationDisposition.None
        private set

    internal var cancellationFailure: Throwable? = null
        private set

    internal var cancellationThrowableDisposition: DeadlineWakeThrowableDisposition = DeadlineWakeThrowableDisposition.None
        private set

    internal var cancellationSuppressionDisposition: DeadlineWakeSuppressionDisposition = DeadlineWakeSuppressionDisposition.NotAttempted
        private set

    internal var schedulerReceiptDisposition: DeadlineWakeSchedulerReceiptDisposition =
        DeadlineWakeSchedulerReceiptDisposition.Empty
        private set

    private val currentGenerationCallbackSettled: Boolean
        get() = fireDisposition == DeadlineWakeFireDisposition.Fired ||
                generationAndPhase.get() == deadlineWakeSuppressedState(generation) ||
                schedulerReceiptDisposition == DeadlineWakeSchedulerReceiptDisposition.Returned

    internal val wakeRetainsGeneration: Boolean
        get() = !currentGenerationCallbackSettled &&
                (cancellationDisposition == DeadlineWakeCancellationDisposition.Succeeded ||
                        cancellationDisposition == DeadlineWakeCancellationDisposition.Failed ||
                        submissionDisposition == DeadlineWakeSubmissionDisposition.Rejected &&
                        schedulingRejection !is RejectedExecutionException)

    internal fun isFullySettledLocked(): Boolean {
        if (deadlineDisposition != DeadlineDisposition.Expired && deadlineDisposition != DeadlineDisposition.Retired) {
            return false
        }

        val submissionSettled = when (submissionDisposition) {
            DeadlineWakeSubmissionDisposition.None,
            DeadlineWakeSubmissionDisposition.Accepted,
            DeadlineWakeSubmissionDisposition.Rejected,
                -> true

            DeadlineWakeSubmissionDisposition.Requested,
            DeadlineWakeSubmissionDisposition.Submitting,
                -> false
        }
        val cancellationSettled = when (cancellationDisposition) {
            DeadlineWakeCancellationDisposition.Succeeded,
            DeadlineWakeCancellationDisposition.NotNeeded,
                -> true

            DeadlineWakeCancellationDisposition.Failed -> currentGenerationCallbackSettled
            DeadlineWakeCancellationDisposition.None,
            DeadlineWakeCancellationDisposition.Requested,
            DeadlineWakeCancellationDisposition.Cancelling,
                -> false
        }
        return submissionSettled && cancellationSettled && acceptedFuture == null && !wakeRetainsGeneration
    }

    private val generationAndPhase = AtomicLong(deadlineWakeQueuedState(initialGeneration))

    private val wakeRunnable = DeadlineWakeRunnable(
        owner = this,
        generationAndPhase = generationAndPhase,
        initialGeneration = initialGeneration,
    )

    internal fun armLocked(start: Long, deadline: Long) {
        startNanos = start
        deadlineNanos = deadline
        deadlineDisposition = DeadlineDisposition.Armed
        submissionDisposition = DeadlineWakeSubmissionDisposition.Requested
    }

    internal fun expireLocked() {
        if (deadlineDisposition != DeadlineDisposition.Armed) return
        deadlineDisposition = DeadlineDisposition.Expired
        requestCancellationLocked()
    }

    internal fun retireLocked() {
        if (deadlineDisposition == DeadlineDisposition.Retired) return
        deadlineDisposition = DeadlineDisposition.Retired
        requestCancellationLocked()
    }

    internal fun requestSubmission(): Boolean = settlementGate.withLock {
        if (deadlineDisposition != DeadlineDisposition.Armed) return@withLock false
        when (submissionDisposition) {
            DeadlineWakeSubmissionDisposition.None -> {
                submissionDisposition = DeadlineWakeSubmissionDisposition.Requested
                true
            }

            DeadlineWakeSubmissionDisposition.Requested -> true
            DeadlineWakeSubmissionDisposition.Submitting,
            DeadlineWakeSubmissionDisposition.Accepted,
            DeadlineWakeSubmissionDisposition.Rejected,
                -> false
        }
    }

    internal fun submitRequested(scheduler: ScheduledExecutorService): Boolean {
        var delayNanos = 0L
        var claimedGeneration = 0L
        val claimed: Boolean = settlementGate.withLock {
            if (deadlineDisposition != DeadlineDisposition.Armed || submissionDisposition != DeadlineWakeSubmissionDisposition.Requested) {
                return@withLock false
            }
            claimedGeneration = generation
            wakeRunnable.prepareGenerationLocked(claimedGeneration)
            val nowNanos = clock.nowNanos()
            delayNanos = if (nowNanos !in 0L..<deadlineNanos) {
                0L
            } else {
                deadlineNanos - nowNanos
            }
            submissionDisposition = DeadlineWakeSubmissionDisposition.Submitting
            true
        }
        if (!claimed) return false

        val future: Future<*>
        try {
            future = scheduler.schedule(wakeRunnable, delayNanos, TimeUnit.NANOSECONDS)
        } catch (failure: Exception) {
            val published = publishSchedulingFailure(
                generation = claimedGeneration,
                failure = failure,
                throwableDisposition = DeadlineWakeThrowableDisposition.NonfatalException,
            )
            if (published) signal.signal()
            return true
        } catch (failure: Error) {
            val published = publishSchedulingFailure(
                generation = claimedGeneration,
                failure = failure,
                throwableDisposition = DeadlineWakeThrowableDisposition.FatalError,
            )
            signalAndRethrow(published, failure)
        }

        val accepted: Boolean = settlementGate.withLock {
            if (generation != claimedGeneration || submissionDisposition != DeadlineWakeSubmissionDisposition.Submitting) {
                return@withLock false
            }
            acceptedFuture = future
            submissionDisposition = DeadlineWakeSubmissionDisposition.Accepted
            clearSettledAcceptedFutureLocked()
            true
        }
        if (accepted) signal.signal()
        performRequestedCancellation()
        return true
    }

    internal fun performRequestedCancellation(): Boolean {
        var future: Future<*>? = null
        var claimedGeneration = 0L
        val claimed: Boolean = settlementGate.withLock {
            if (cancellationDisposition != DeadlineWakeCancellationDisposition.Requested ||
                submissionDisposition != DeadlineWakeSubmissionDisposition.Accepted
            ) {
                return@withLock false
            }
            future = acceptedFuture
            if (future == null) return@withLock false
            claimedGeneration = generation
            cancellationDisposition = DeadlineWakeCancellationDisposition.Cancelling
            true
        }
        if (!claimed) return false

        var cancelled: Boolean
        var suppressionDisposition = DeadlineWakeSuppressionDisposition.NotAttempted
        var failure: Throwable? = null
        var throwableDisposition = DeadlineWakeThrowableDisposition.None
        try {
            cancelled = future?.cancel(false) == true
            if (cancelled) {
                suppressionDisposition = if (
                    generationAndPhase.compareAndSet(
                        deadlineWakeQueuedState(claimedGeneration),
                        deadlineWakeSuppressedState(claimedGeneration),
                    )
                ) {
                    DeadlineWakeSuppressionDisposition.Succeeded
                } else {
                    DeadlineWakeSuppressionDisposition.Failed
                }
            }
        } catch (thrown: Exception) {
            cancelled = false
            failure = thrown
            throwableDisposition = DeadlineWakeThrowableDisposition.NonfatalException
        } catch (thrown: Error) {
            val published = publishCancellationResult(
                generation = claimedGeneration,
                cancelled = false,
                suppressionDisposition = DeadlineWakeSuppressionDisposition.NotAttempted,
                failure = thrown,
                throwableDisposition = DeadlineWakeThrowableDisposition.FatalError,
            )
            signalAndRethrow(published, thrown)
        }

        val published = publishCancellationResult(
            generation = claimedGeneration,
            cancelled = cancelled,
            suppressionDisposition = suppressionDisposition,
            failure = failure,
            throwableDisposition = throwableDisposition,
        )
        if (published) signal.signal()
        return true
    }

    internal fun prepareEarlyWakeSuccessor(): DeadlineWakeSuccessorResult = settlementGate.withLock {
        val futureOrRejectionSettled = when (submissionDisposition) {
            DeadlineWakeSubmissionDisposition.Accepted -> acceptedFuture == null
            DeadlineWakeSubmissionDisposition.Rejected ->
                schedulingRejection != null && schedulingRejection !is RejectedExecutionException

            DeadlineWakeSubmissionDisposition.None,
            DeadlineWakeSubmissionDisposition.Requested,
            DeadlineWakeSubmissionDisposition.Submitting,
                -> false
        }
        val cancellationInFlight = cancellationDisposition == DeadlineWakeCancellationDisposition.Requested ||
                cancellationDisposition == DeadlineWakeCancellationDisposition.Cancelling
        if (deadlineDisposition != DeadlineDisposition.Armed ||
            fireDisposition != DeadlineWakeFireDisposition.Fired ||
            firedAtNanos >= deadlineNanos ||
            !futureOrRejectionSettled ||
            cancellationInFlight ||
            schedulerReceiptDisposition != DeadlineWakeSchedulerReceiptDisposition.Empty ||
            generationAndPhase.get() != deadlineWakeRunningState(generation)
        ) {
            return@withLock DeadlineWakeSuccessorResult.NotEligible
        }
        if (generation >= MAX_DEADLINE_WAKE_GENERATION) {
            return@withLock DeadlineWakeSuccessorResult.IdentityExhausted
        }

        val currentGeneration = generation
        val nextGeneration = currentGeneration + 1L
        val successorPrepared = generationAndPhase.compareAndSet(
            deadlineWakeRunningState(currentGeneration),
            deadlineWakeQueuedState(nextGeneration),
        )
        if (!successorPrepared) return@withLock DeadlineWakeSuccessorResult.NotEligible

        generation = nextGeneration
        wakeRunnable.prepareGenerationLocked(nextGeneration)
        submissionDisposition = DeadlineWakeSubmissionDisposition.Requested
        acceptedFuture = null
        schedulingRejection = null
        schedulingThrowableDisposition = DeadlineWakeThrowableDisposition.None
        fireDisposition = DeadlineWakeFireDisposition.Empty
        firedAtNanos = NO_TIME
        cancellationDisposition = DeadlineWakeCancellationDisposition.None
        cancellationFailure = null
        cancellationThrowableDisposition = DeadlineWakeThrowableDisposition.None
        cancellationSuppressionDisposition = DeadlineWakeSuppressionDisposition.NotAttempted
        schedulerReceiptDisposition = DeadlineWakeSchedulerReceiptDisposition.Empty
        DeadlineWakeSuccessorResult.Requested
    }

    internal fun publishSchedulerReceipt(): Boolean {
        val published: Boolean = settlementGate.withLock {
            if (schedulerReceiptDisposition == DeadlineWakeSchedulerReceiptDisposition.Returned) {
                return@withLock false
            }
            schedulerReceiptDisposition = DeadlineWakeSchedulerReceiptDisposition.Returned
            clearSettledAcceptedFutureLocked()
            true
        }
        if (published) signal.signal()
        return published
    }

    private fun publishFired(callbackGeneration: Long) {
        val published: Boolean = settlementGate.withLock {
            if (generation != callbackGeneration ||
                generationAndPhase.get() != deadlineWakeRunningState(callbackGeneration) ||
                fireDisposition != DeadlineWakeFireDisposition.Empty
            ) {
                return@withLock false
            }
            firedAtNanos = clock.nowNanos()
            fireDisposition = DeadlineWakeFireDisposition.Fired
            clearSettledAcceptedFutureLocked()
            true
        }
        if (published) signal.signal()
    }

    private fun publishSchedulingFailure(
        generation: Long,
        failure: Throwable,
        throwableDisposition: DeadlineWakeThrowableDisposition,
    ): Boolean = settlementGate.withLock {
        if (this.generation != generation || submissionDisposition != DeadlineWakeSubmissionDisposition.Submitting) {
            return@withLock false
        }
        schedulingRejection = failure
        schedulingThrowableDisposition = throwableDisposition
        submissionDisposition = DeadlineWakeSubmissionDisposition.Rejected
        if (failure is RejectedExecutionException) {
            if (cancellationDisposition == DeadlineWakeCancellationDisposition.Requested) {
                cancellationDisposition = DeadlineWakeCancellationDisposition.NotNeeded
            }
        } else if (currentGenerationCallbackSettled) {
            cancellationDisposition = DeadlineWakeCancellationDisposition.NotNeeded
        } else if (cancellationDisposition == DeadlineWakeCancellationDisposition.None ||
            cancellationDisposition == DeadlineWakeCancellationDisposition.Requested
        ) {
            cancellationDisposition = DeadlineWakeCancellationDisposition.Requested
        }
        true
    }

    private fun publishCancellationResult(
        generation: Long,
        cancelled: Boolean,
        suppressionDisposition: DeadlineWakeSuppressionDisposition,
        failure: Throwable?,
        throwableDisposition: DeadlineWakeThrowableDisposition,
    ): Boolean = settlementGate.withLock {
        if (this.generation != generation || cancellationDisposition != DeadlineWakeCancellationDisposition.Cancelling) {
            return@withLock false
        }
        cancellationFailure = failure
        cancellationThrowableDisposition = throwableDisposition
        cancellationSuppressionDisposition = suppressionDisposition
        cancellationDisposition = if (cancelled) {
            DeadlineWakeCancellationDisposition.Succeeded
        } else {
            DeadlineWakeCancellationDisposition.Failed
        }
        clearSettledAcceptedFutureLocked()
        true
    }

    private fun signalAndRethrow(published: Boolean, failure: Error): Nothing {
        try {
            if (published) signal.signal()
        } finally {
            throw failure
        }
    }

    private fun requestCancellationLocked(): Boolean {
        if (cancellationDisposition != DeadlineWakeCancellationDisposition.None) return false
        cancellationDisposition = when (submissionDisposition) {
            DeadlineWakeSubmissionDisposition.None,
            DeadlineWakeSubmissionDisposition.Requested,
                -> DeadlineWakeCancellationDisposition.NotNeeded

            DeadlineWakeSubmissionDisposition.Rejected ->
                if (currentGenerationCallbackSettled || schedulingRejection is RejectedExecutionException) {
                    DeadlineWakeCancellationDisposition.NotNeeded
                } else {
                    DeadlineWakeCancellationDisposition.Requested
                }

            DeadlineWakeSubmissionDisposition.Submitting,
            DeadlineWakeSubmissionDisposition.Accepted,
                -> if (fireDisposition == DeadlineWakeFireDisposition.Fired) {
                DeadlineWakeCancellationDisposition.NotNeeded
            } else {
                DeadlineWakeCancellationDisposition.Requested
            }
        }
        if (submissionDisposition == DeadlineWakeSubmissionDisposition.Requested) {
            submissionDisposition = DeadlineWakeSubmissionDisposition.None
        }
        return true
    }

    private fun clearSettledAcceptedFutureLocked() {
        val callbackOrSchedulerSettled = fireDisposition == DeadlineWakeFireDisposition.Fired ||
                generationAndPhase.get() == deadlineWakeSuppressedState(generation) ||
                schedulerReceiptDisposition == DeadlineWakeSchedulerReceiptDisposition.Returned
        if (callbackOrSchedulerSettled && cancellationDisposition == DeadlineWakeCancellationDisposition.Requested) {
            cancellationDisposition = DeadlineWakeCancellationDisposition.NotNeeded
        }
        if (callbackOrSchedulerSettled || cancellationDisposition == DeadlineWakeCancellationDisposition.Succeeded) {
            acceptedFuture = null
        }
    }

    private companion object {
        private const val NO_TIME: Long = Long.MIN_VALUE
    }

    private class DeadlineWakeRunnable(
        private val owner: DeadlineWakeLink,
        private val generationAndPhase: AtomicLong,
        initialGeneration: Long,
    ) : Runnable {
        @Volatile
        private var scheduledGeneration: Long = initialGeneration

        fun prepareGenerationLocked(generation: Long) {
            scheduledGeneration = generation
        }

        override fun run() {
            val callbackGeneration = scheduledGeneration
            if (!generationAndPhase.compareAndSet(deadlineWakeQueuedState(callbackGeneration), deadlineWakeRunningState(callbackGeneration))) {
                return
            }
            owner.publishFired(callbackGeneration)
        }
    }
}

internal class DeadlineOccurrence internal constructor(
    internal val identity: Long,
    internal val boundOccurrenceIdentity: Long,
    internal val durationNanos: Long,
    initialWakeGeneration: Long,
    timeoutCause: Throwable,
    settlementGate: ReentrantLock,
    clock: EngineClock,
    signal: SettlementSignal,
) {
    internal val wakeLink: DeadlineWakeLink = DeadlineWakeLink(
        initialGeneration = initialWakeGeneration,
        timeoutCause = timeoutCause,
        settlementGate = settlementGate,
        clock = clock,
        signal = signal,
    )

    internal val disposition: DeadlineDisposition
        get() = wakeLink.deadlineDisposition

    internal val startNanos: Long
        get() = wakeLink.startNanos

    internal val deadlineNanos: Long
        get() = wakeLink.deadlineNanos

    internal fun armLocked(startNanos: Long): DeadlineArmResult {
        if (disposition != DeadlineDisposition.Unarmed) return DeadlineArmResult.AlreadySettled
        if (durationNanos <= 0L || startNanos < 0L || startNanos > Long.MAX_VALUE - durationNanos) {
            wakeLink.retireLocked()
            return DeadlineArmResult.InvalidClockOrOverflow
        }
        wakeLink.armLocked(startNanos, Math.addExact(startNanos, durationNanos))
        return DeadlineArmResult.Armed
    }

    internal fun expireLocked() {
        wakeLink.expireLocked()
    }

    internal fun retireLocked() {
        wakeLink.retireLocked()
    }

}
