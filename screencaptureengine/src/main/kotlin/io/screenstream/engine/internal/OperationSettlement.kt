package io.screenstream.engine.internal

import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal const val firstMetricsReadinessNanos: Long = 5_000_000_000L
internal const val initialCapturedResizeReadinessNanos: Long = 5_000_000_000L
internal const val androidEnteredOperationSafetyNanos: Long = 5_000_000_000L
internal const val glEnteredOperationSafetyNanos: Long = 10_000_000_000L
internal const val jpegEnteredOperationSafetyNanos: Long = 15_000_000_000L

internal fun interface EngineClock {
    fun nowNanos(): Long
}

internal fun interface SettlementSignal {
    fun signal()
}

internal interface OperationEvidence {
    val receipt: OperationReceipt?

    val returnedOwner: OperationReturnedOwner?
}

internal interface OperationOwnerBag

internal interface OperationReceipt

internal interface OperationReturnedOwner

internal enum class OperationDomain {
    Active,
    Cleanup,
}

internal enum class OperationSubmissionDisposition {
    None,
    Submitting,
    Accepted,
    Rejected,
    Cancelled,
}

internal enum class OperationEntryDisposition {
    Unentered,
    Entered,
    Cancelled,
}

internal enum class OperationReturnDisposition {
    Empty,
    Normal,
    Thrown,
}

internal enum class OperationReturnUse {
    Unclaimed,
    Timely,
    Cleanup,
}

internal enum class OperationDisposition {
    Pending,
    Timely,
    Expired,
    SchedulerRejected,
    DeadlineGuardFailed,
    Cancelled,
    Cleanup,
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

internal enum class OperationEntryResult {
    Entered,
    NotCurrent,
    InvalidDeadline,
}

internal enum class OperationSubmissionRejectionResult {
    Active,
    Cleanup,
    NotCurrent,
}

internal enum class OperationArbitration {
    None,
    TimelyNormal,
    TimelyThrown,
    ExpiredEmpty,
    ExpiredNormal,
    ExpiredThrown,
    CleanupNormal,
    CleanupThrown,
    SchedulerRejected,
    DeadlineGuardFailed,
}

internal enum class OperationTerminalArbitration {
    Transferred,
    CancelledUnentered,
    AlreadySettled,
    TimelyNormal,
    TimelyThrown,
    ExpiredNormal,
    ExpiredThrown,
    CleanupNormal,
    CleanupThrown,
}

internal enum class DeadlineWakeSuccessorResult {
    Requested,
    NotEligible,
    IdentityExhausted,
}

internal class OperationReturnCell<R : OperationEvidence>(
    internal val evidence: R,
) {
    internal var disposition: OperationReturnDisposition = OperationReturnDisposition.Empty
        private set

    internal var use: OperationReturnUse = OperationReturnUse.Unclaimed
        private set

    internal var throwable: Throwable? = null
        private set

    internal var settlementNanos: Long = NO_SETTLEMENT_SAMPLE
        private set

    internal fun publishNormalLocked(sampleNanos: Long): Boolean {
        if (disposition != OperationReturnDisposition.Empty) return false
        settlementNanos = sampleNanos
        disposition = OperationReturnDisposition.Normal
        return true
    }

    internal fun publishThrownLocked(sampleNanos: Long, thrown: Throwable): Boolean {
        if (disposition != OperationReturnDisposition.Empty) return false
        throwable = thrown
        settlementNanos = sampleNanos
        disposition = OperationReturnDisposition.Thrown
        return true
    }

    internal fun claimTimelyLocked(): Boolean {
        if (disposition == OperationReturnDisposition.Empty || use != OperationReturnUse.Unclaimed) return false
        use = OperationReturnUse.Timely
        return true
    }

    internal fun claimCleanupLocked(): Boolean {
        if (disposition == OperationReturnDisposition.Empty || use != OperationReturnUse.Unclaimed) return false
        use = OperationReturnUse.Cleanup
        return true
    }

    private companion object {
        private const val NO_SETTLEMENT_SAMPLE: Long = Long.MIN_VALUE
    }
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

internal class OperationOccurrence<R : OperationEvidence>(
    internal val identity: Long,
    private val clock: EngineClock,
    internal val returnCell: OperationReturnCell<R>,
    internal val ownerBag: OperationOwnerBag,
    deadlineIdentity: Long? = null,
    deadlineDurationNanos: Long? = null,
    initialWakeGeneration: Long = deadlineIdentity ?: 0L,
    timeoutCause: Throwable? = null,
    wakeSignal: SettlementSignal? = null,
) {
    internal val settlementGate: ReentrantLock = ReentrantLock(false)

    internal val deadlineOccurrence: DeadlineOccurrence?

    internal var domain: OperationDomain = OperationDomain.Active
        private set

    internal var submissionDisposition: OperationSubmissionDisposition =
        OperationSubmissionDisposition.None
        private set

    internal var submissionRejection: Throwable? = null
        private set

    internal var entryDisposition: OperationEntryDisposition = OperationEntryDisposition.Unentered
        private set

    internal var disposition: OperationDisposition = OperationDisposition.Pending
        private set

    private var cleanupEntryAllowed: Boolean = false

    init {
        require((deadlineIdentity == null) == (deadlineDurationNanos == null))
        require((deadlineIdentity == null) == (timeoutCause == null))
        require((deadlineIdentity == null) == (wakeSignal == null))
        deadlineOccurrence = if (deadlineIdentity == null) {
            null
        } else {
            DeadlineOccurrence(
                identity = deadlineIdentity,
                boundOccurrenceIdentity = identity,
                durationNanos = checkNotNull(deadlineDurationNanos),
                initialWakeGeneration = initialWakeGeneration,
                timeoutCause = checkNotNull(timeoutCause),
                settlementGate = settlementGate,
                clock = clock,
                signal = checkNotNull(wakeSignal),
            )
        }
    }

    internal fun beginSubmission(): Boolean = settlementGate.withLock {
        val submissionAllowed = domain == OperationDomain.Active &&
                disposition == OperationDisposition.Pending ||
                domain == OperationDomain.Cleanup &&
                cleanupEntryAllowed &&
                disposition == OperationDisposition.Cleanup
        if (!submissionAllowed ||
            submissionDisposition != OperationSubmissionDisposition.None ||
            entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return@withLock false
        }
        submissionDisposition = OperationSubmissionDisposition.Submitting
        true
    }

    internal fun publishSubmissionAccepted(): Boolean = settlementGate.withLock {
        if (submissionDisposition != OperationSubmissionDisposition.Submitting) return@withLock false
        submissionDisposition = OperationSubmissionDisposition.Accepted
        cancelSafelyUnenteredCleanupLocked()
        true
    }

    internal fun publishSubmissionRejected(thrown: Throwable): OperationSubmissionRejectionResult =
        settlementGate.withLock {
            if (submissionDisposition != OperationSubmissionDisposition.Submitting) {
                return@withLock OperationSubmissionRejectionResult.NotCurrent
            }
            submissionRejection = thrown
            submissionDisposition = OperationSubmissionDisposition.Rejected
            if (entryDisposition == OperationEntryDisposition.Unentered &&
                returnCell.disposition == OperationReturnDisposition.Empty &&
                domain == OperationDomain.Active &&
                disposition == OperationDisposition.Pending
            ) {
                disposition = OperationDisposition.SchedulerRejected
                OperationSubmissionRejectionResult.Active
            } else {
                cancelSafelyUnenteredCleanupLocked()
                OperationSubmissionRejectionResult.Cleanup
            }
        }

    internal fun tryEnter(): OperationEntryResult = settlementGate.withLock {
        if (entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty ||
            submissionDisposition != OperationSubmissionDisposition.Submitting &&
            submissionDisposition != OperationSubmissionDisposition.Accepted ||
            domain == OperationDomain.Cleanup && !cleanupEntryAllowed ||
            disposition != OperationDisposition.Pending &&
            disposition != OperationDisposition.Cleanup
        ) {
            return@withLock OperationEntryResult.NotCurrent
        }

        val deadline = deadlineOccurrence
        if (domain == OperationDomain.Active && deadline != null) {
            when (deadline.armLocked(clock.nowNanos())) {
                DeadlineArmResult.Armed -> Unit
                DeadlineArmResult.AlreadySettled -> return@withLock OperationEntryResult.NotCurrent
                DeadlineArmResult.InvalidClockOrOverflow -> {
                    disposition = OperationDisposition.DeadlineGuardFailed
                    return@withLock OperationEntryResult.InvalidDeadline
                }
            }
        } else {
            deadline?.retireLocked()
        }

        entryDisposition = OperationEntryDisposition.Entered
        OperationEntryResult.Entered
    }

    internal fun publishNormalReturn(): Boolean = settlementGate.withLock {
        if (entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        returnCell.publishNormalLocked(settlementSampleLocked())
    }

    internal fun publishThrownReturn(thrown: Throwable): Boolean = settlementGate.withLock {
        if (entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        returnCell.publishThrownLocked(settlementSampleLocked(), thrown)
    }

    internal fun arbitrate(): OperationArbitration = settlementGate.withLock {
        arbitrateLocked()
    }

    internal fun requestDeadlineWake(): Boolean = deadlineOccurrence?.wakeLink?.requestSubmission() ?: false

    internal fun submitRequestedDeadlineWake(scheduler: ScheduledExecutorService): Boolean =
        deadlineOccurrence?.wakeLink?.submitRequested(scheduler) ?: false

    internal fun performRequestedDeadlineCancellation(): Boolean =
        deadlineOccurrence?.wakeLink?.performRequestedCancellation() ?: false

    internal fun arbitrateTerminal(mandatoryCleanup: Boolean): OperationTerminalArbitration =
        settlementGate.withLock {
            if (returnCell.disposition != OperationReturnDisposition.Empty && returnCell.use == OperationReturnUse.Unclaimed) {
                return@withLock terminalArbitration(arbitrateLocked())
            }
            if (returnCell.use != OperationReturnUse.Unclaimed) {
                return@withLock OperationTerminalArbitration.AlreadySettled
            }
            if (!mandatoryCleanup && entryDisposition == OperationEntryDisposition.Cancelled) {
                return@withLock OperationTerminalArbitration.CancelledUnentered
            }

            domain = OperationDomain.Cleanup
            if (mandatoryCleanup && entryDisposition == OperationEntryDisposition.Unentered) {
                cleanupEntryAllowed = true
            }
            deadlineOccurrence?.retireLocked()
            if (!cleanupEntryAllowed &&
                submissionDisposition != OperationSubmissionDisposition.Submitting &&
                entryDisposition == OperationEntryDisposition.Unentered &&
                returnCell.disposition == OperationReturnDisposition.Empty
            ) {
                entryDisposition = OperationEntryDisposition.Cancelled
                submissionDisposition = OperationSubmissionDisposition.Cancelled
                if (disposition == OperationDisposition.Pending || disposition == OperationDisposition.Cleanup) {
                    disposition = OperationDisposition.Cancelled
                }
                return@withLock OperationTerminalArbitration.CancelledUnentered
            }
            if (disposition == OperationDisposition.Pending) {
                disposition = OperationDisposition.Cleanup
            }
            OperationTerminalArbitration.Transferred
        }

    private fun cancelSafelyUnenteredCleanupLocked() {
        if (domain != OperationDomain.Cleanup ||
            cleanupEntryAllowed ||
            entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return
        }
        entryDisposition = OperationEntryDisposition.Cancelled
        if (disposition == OperationDisposition.Pending || disposition == OperationDisposition.Cleanup) {
            disposition = OperationDisposition.Cancelled
        }
    }

    private fun arbitrateLocked(): OperationArbitration {
        if (returnCell.disposition != OperationReturnDisposition.Empty && returnCell.use == OperationReturnUse.Unclaimed) {
            if (domain == OperationDomain.Cleanup || disposition != OperationDisposition.Pending) {
                returnCell.claimCleanupLocked()
                return cleanupReturnArbitration()
            }

            val deadline = deadlineOccurrence
            if (deadline == null ||
                deadline.disposition == DeadlineDisposition.Armed &&
                returnCell.settlementNanos < deadline.deadlineNanos
            ) {
                deadline?.retireLocked()
                disposition = OperationDisposition.Timely
                returnCell.claimTimelyLocked()
                return timelyReturnArbitration()
            }

            deadline.expireLocked()
            disposition = OperationDisposition.Expired
            returnCell.claimCleanupLocked()
            return expiredReturnArbitration()
        }

        if (domain != OperationDomain.Active || disposition != OperationDisposition.Pending) {
            return when (disposition) {
                OperationDisposition.DeadlineGuardFailed -> OperationArbitration.DeadlineGuardFailed
                else -> OperationArbitration.None
            }
        }

        val deadline = deadlineOccurrence ?: return OperationArbitration.None
        if (deadline.wakeLink.submissionDisposition == DeadlineWakeSubmissionDisposition.Rejected) {
            deadline.retireLocked()
            disposition = OperationDisposition.SchedulerRejected
            return OperationArbitration.SchedulerRejected
        }
        if (deadline.disposition == DeadlineDisposition.Armed && clock.nowNanos() >= deadline.deadlineNanos) {
            deadline.expireLocked()
            disposition = OperationDisposition.Expired
            return OperationArbitration.ExpiredEmpty
        }
        return OperationArbitration.None
    }

    private fun settlementSampleLocked(): Long {
        val deadline = deadlineOccurrence
        return if (domain == OperationDomain.Active && deadline?.disposition == DeadlineDisposition.Armed) {
            clock.nowNanos()
        } else {
            NO_SETTLEMENT_SAMPLE
        }
    }

    private fun timelyReturnArbitration(): OperationArbitration =
        when (returnCell.disposition) {
            OperationReturnDisposition.Normal -> OperationArbitration.TimelyNormal
            OperationReturnDisposition.Thrown -> OperationArbitration.TimelyThrown
            OperationReturnDisposition.Empty -> OperationArbitration.None
        }

    private fun expiredReturnArbitration(): OperationArbitration =
        when (returnCell.disposition) {
            OperationReturnDisposition.Normal -> OperationArbitration.ExpiredNormal
            OperationReturnDisposition.Thrown -> OperationArbitration.ExpiredThrown
            OperationReturnDisposition.Empty -> OperationArbitration.ExpiredEmpty
        }

    private fun cleanupReturnArbitration(): OperationArbitration =
        when (returnCell.disposition) {
            OperationReturnDisposition.Normal -> OperationArbitration.CleanupNormal
            OperationReturnDisposition.Thrown -> OperationArbitration.CleanupThrown
            OperationReturnDisposition.Empty -> OperationArbitration.None
        }

    private fun terminalArbitration(arbitration: OperationArbitration): OperationTerminalArbitration =
        when (arbitration) {
            OperationArbitration.TimelyNormal -> OperationTerminalArbitration.TimelyNormal
            OperationArbitration.TimelyThrown -> OperationTerminalArbitration.TimelyThrown
            OperationArbitration.ExpiredNormal -> OperationTerminalArbitration.ExpiredNormal
            OperationArbitration.ExpiredThrown -> OperationTerminalArbitration.ExpiredThrown
            OperationArbitration.CleanupNormal -> OperationTerminalArbitration.CleanupNormal
            OperationArbitration.CleanupThrown -> OperationTerminalArbitration.CleanupThrown
            else -> OperationTerminalArbitration.Transferred
        }

    private companion object {
        private const val NO_SETTLEMENT_SAMPLE: Long = Long.MIN_VALUE
    }
}
