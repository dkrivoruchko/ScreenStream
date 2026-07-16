package io.screenstream.engine.internal.settlement

import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val firstMetricsReadinessNanos: Long = 5_000_000_000L
internal const val initialCapturedResizeReadinessNanos: Long = 5_000_000_000L
internal const val androidEnteredOperationSafetyNanos: Long = 5_000_000_000L
internal const val jpegEnteredOperationSafetyNanos: Long = 15_000_000_000L

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

internal enum class DeadlineWakeSchedulerReceiptDisposition {
    Empty,
    Returned,
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
    internal var deadlineDisposition: DeadlineDisposition = DeadlineDisposition.Unarmed
        private set

    internal var startNanos: Long = NO_TIME
        private set

    internal var deadlineNanos: Long = NO_TIME
        private set

    internal var generation: Long = initialGeneration
        private set

    internal var submissionDisposition: DeadlineWakeSubmissionDisposition =
        DeadlineWakeSubmissionDisposition.None
        private set

    internal var acceptedFuture: Future<*>? = null
        private set

    internal var schedulingRejection: Throwable? = null
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

    internal var schedulerReceiptDisposition: DeadlineWakeSchedulerReceiptDisposition =
        DeadlineWakeSchedulerReceiptDisposition.Empty
        private set

    internal val callbackStopProven: Boolean
        get() = fireDisposition == DeadlineWakeFireDisposition.Fired ||
                schedulerReceiptDisposition == DeadlineWakeSchedulerReceiptDisposition.Returned

    internal val failedCancellationRetainsGeneration: Boolean
        get() = cancellationDisposition == DeadlineWakeCancellationDisposition.Failed && !callbackStopProven

    private var scheduledGeneration: Long = initialGeneration

    private val wakeRunnable: Runnable = Runnable {
        publishFired(scheduledGeneration)
    }

    internal fun armLocked(start: Long, deadline: Long) {
        startNanos = start
        deadlineNanos = deadline
        deadlineDisposition = DeadlineDisposition.Armed
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
        if (deadlineDisposition != DeadlineDisposition.Armed || submissionDisposition != DeadlineWakeSubmissionDisposition.None) {
            return@withLock false
        }
        submissionDisposition = DeadlineWakeSubmissionDisposition.Requested
        true
    }

    internal fun submitRequested(scheduler: ScheduledExecutorService): Boolean {
        var delayNanos = 0L
        var claimedGeneration = 0L
        val claimed: Boolean = settlementGate.withLock {
            if (deadlineDisposition != DeadlineDisposition.Armed || submissionDisposition != DeadlineWakeSubmissionDisposition.Requested) {
                return@withLock false
            }
            claimedGeneration = generation
            scheduledGeneration = claimedGeneration
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
        } catch (allocationFailure: OutOfMemoryError) {
            publishSchedulingFailure(claimedGeneration, allocationFailure)
            return true
        } catch (rejection: RejectedExecutionException) {
            publishSchedulingFailure(claimedGeneration, rejection)
            return true
        } catch (rejection: RuntimeException) {
            publishSchedulingFailure(claimedGeneration, rejection)
            return true
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
        val claimed: Boolean = settlementGate.withLock {
            if (cancellationDisposition != DeadlineWakeCancellationDisposition.Requested ||
                submissionDisposition != DeadlineWakeSubmissionDisposition.Accepted
            ) {
                return@withLock false
            }
            future = acceptedFuture
            if (future == null) return@withLock false
            cancellationDisposition = DeadlineWakeCancellationDisposition.Cancelling
            true
        }
        if (!claimed) return false

        var cancelled = false
        var failure: Throwable? = null
        try {
            cancelled = future?.cancel(false) == true
        } catch (thrown: RuntimeException) {
            failure = thrown
        }

        settlementGate.withLock {
            if (cancellationDisposition != DeadlineWakeCancellationDisposition.Cancelling) {
                return@withLock
            }
            cancellationFailure = failure
            cancellationDisposition = if (cancelled) {
                DeadlineWakeCancellationDisposition.Succeeded
            } else {
                DeadlineWakeCancellationDisposition.Failed
            }
            clearSettledAcceptedFutureLocked()
        }
        signal.signal()
        return true
    }

    internal fun prepareEarlyWakeSuccessor(): DeadlineWakeSuccessorResult = settlementGate.withLock {
        if (deadlineDisposition != DeadlineDisposition.Armed ||
            fireDisposition != DeadlineWakeFireDisposition.Fired ||
            firedAtNanos >= deadlineNanos ||
            submissionDisposition != DeadlineWakeSubmissionDisposition.Accepted ||
            cancellationDisposition != DeadlineWakeCancellationDisposition.None
        ) {
            return@withLock DeadlineWakeSuccessorResult.NotEligible
        }
        if (generation == Long.MAX_VALUE) return@withLock DeadlineWakeSuccessorResult.IdentityExhausted

        generation += 1L
        scheduledGeneration = generation
        submissionDisposition = DeadlineWakeSubmissionDisposition.Requested
        acceptedFuture = null
        schedulingRejection = null
        fireDisposition = DeadlineWakeFireDisposition.Empty
        firedAtNanos = NO_TIME
        cancellationDisposition = DeadlineWakeCancellationDisposition.None
        cancellationFailure = null
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
            if (generation != callbackGeneration || fireDisposition != DeadlineWakeFireDisposition.Empty) {
                return@withLock false
            }
            firedAtNanos = clock.nowNanos()
            fireDisposition = DeadlineWakeFireDisposition.Fired
            clearSettledAcceptedFutureLocked()
            true
        }
        if (published) signal.signal()
    }

    private fun publishSchedulingFailure(generation: Long, failure: Throwable) {
        val published: Boolean = settlementGate.withLock {
            if (this.generation != generation || submissionDisposition != DeadlineWakeSubmissionDisposition.Submitting) {
                return@withLock false
            }
            schedulingRejection = failure
            submissionDisposition = DeadlineWakeSubmissionDisposition.Rejected
            if (cancellationDisposition == DeadlineWakeCancellationDisposition.Requested) {
                cancellationDisposition = DeadlineWakeCancellationDisposition.NotNeeded
            }
            true
        }
        if (published) signal.signal()
    }

    private fun requestCancellationLocked(): Boolean {
        if (cancellationDisposition != DeadlineWakeCancellationDisposition.None) return false
        cancellationDisposition = when (submissionDisposition) {
            DeadlineWakeSubmissionDisposition.None,
            DeadlineWakeSubmissionDisposition.Requested,
            DeadlineWakeSubmissionDisposition.Rejected,
                -> DeadlineWakeCancellationDisposition.NotNeeded

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
