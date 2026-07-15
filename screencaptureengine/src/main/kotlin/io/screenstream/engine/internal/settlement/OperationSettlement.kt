package io.screenstream.engine.internal.settlement

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
        if (domain != OperationDomain.Cleanup || cleanupEntryAllowed || entryDisposition != OperationEntryDisposition.Unentered ||
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
