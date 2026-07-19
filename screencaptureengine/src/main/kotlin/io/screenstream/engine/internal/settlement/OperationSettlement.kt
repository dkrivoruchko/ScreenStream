package io.screenstream.engine.internal.settlement

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
    EntryWon,
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

    internal val controlWakeLink: ControlWakeLink?
        get() = deadlineOccurrence?.controlWakeLink

    internal var domain: OperationDomain = OperationDomain.Active
        private set

    internal var submissionDisposition: OperationSubmissionDisposition =
        OperationSubmissionDisposition.None
        private set

    internal var submissionFailure: Exception? = null
        private set

    internal var submissionAmbiguousFatal: Throwable? = null
        private set

    private var submissionExecutionObserved: Boolean = false

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
        beginSubmissionLocked()
    }

    internal fun beginSubmissionLocked(): Boolean {
        check(settlementGate.isHeldByCurrentThread)
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
            return false
        }
        submissionDisposition = OperationSubmissionDisposition.Submitting
        return true
    }

    internal fun publishSubmissionAccepted(): Boolean = settlementGate.withLock {
        publishSubmissionAcceptedLocked()
    }

    internal fun publishSubmissionAcceptedLocked(): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        if (submissionDisposition != OperationSubmissionDisposition.Submitting) return false
        submissionDisposition = OperationSubmissionDisposition.Accepted
        cancelSafelyUnenteredCleanupLocked()
        return true
    }

    internal fun publishSubmissionRejected(thrown: Exception): OperationSubmissionRejectionResult =
        settlementGate.withLock {
            publishSubmissionFailedLocked(thrown)
        }

    internal fun publishSubmissionFailed(thrown: Exception): OperationSubmissionRejectionResult =
        settlementGate.withLock { publishSubmissionFailedLocked(thrown) }

    internal fun publishSubmissionFailedLocked(thrown: Exception): OperationSubmissionRejectionResult {
        check(settlementGate.isHeldByCurrentThread)
        if (submissionDisposition != OperationSubmissionDisposition.Submitting) {
            return OperationSubmissionRejectionResult.NotCurrent
        }
        submissionFailure = thrown
        if (submissionExecutionObserved ||
            entryDisposition == OperationEntryDisposition.Entered ||
            returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            submissionExecutionObserved = true
            submissionDisposition = OperationSubmissionDisposition.Accepted
            return OperationSubmissionRejectionResult.EntryWon
        }
        submissionDisposition = OperationSubmissionDisposition.Rejected
        if (entryDisposition == OperationEntryDisposition.Unentered &&
            domain == OperationDomain.Active &&
            disposition == OperationDisposition.Pending
        ) {
            disposition = OperationDisposition.SchedulerRejected
            return OperationSubmissionRejectionResult.Active
        }
        cancelSafelyUnenteredCleanupLocked()
        return OperationSubmissionRejectionResult.Cleanup
    }

    internal fun publishSubmissionAmbiguousFatal(raw: Throwable): Boolean = settlementGate.withLock {
        require(raw !is Exception)
        if (submissionAmbiguousFatal != null ||
            submissionDisposition != OperationSubmissionDisposition.Submitting &&
            submissionDisposition != OperationSubmissionDisposition.Accepted
        ) {
            return@withLock false
        }
        submissionAmbiguousFatal = raw
        if (submissionExecutionObserved) {
            submissionDisposition = OperationSubmissionDisposition.Accepted
        }
        true
    }

    internal fun resolveAmbiguousSubmissionAfterTermination(): Boolean = settlementGate.withLock {
        if (submissionAmbiguousFatal == null ||
            submissionExecutionObserved ||
            submissionDisposition != OperationSubmissionDisposition.Submitting ||
            entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return@withLock false
        }
        submissionDisposition = OperationSubmissionDisposition.Cancelled
        entryDisposition = OperationEntryDisposition.Cancelled
        deadlineOccurrence?.retireLocked()
        if (disposition == OperationDisposition.Pending || disposition == OperationDisposition.Cleanup) {
            disposition = OperationDisposition.Cancelled
        }
        true
    }

    internal fun tryEnter(): OperationEntryResult = settlementGate.withLock {
        tryEnterLocked()
    }

    internal fun tryEnterLocked(): OperationEntryResult {
        check(settlementGate.isHeldByCurrentThread)
        if (submissionDisposition == OperationSubmissionDisposition.Submitting ||
            submissionDisposition == OperationSubmissionDisposition.Accepted
        ) {
            submissionExecutionObserved = true
            if (submissionAmbiguousFatal != null) {
                submissionDisposition = OperationSubmissionDisposition.Accepted
            }
        }
        if (entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty ||
            submissionDisposition != OperationSubmissionDisposition.Submitting &&
            submissionDisposition != OperationSubmissionDisposition.Accepted ||
            domain == OperationDomain.Cleanup && !cleanupEntryAllowed ||
            disposition != OperationDisposition.Pending &&
            disposition != OperationDisposition.Cleanup
        ) {
            return OperationEntryResult.NotCurrent
        }

        val deadline = deadlineOccurrence
        if (domain == OperationDomain.Active && deadline != null) {
            when (deadline.armLocked(clock.nowNanos())) {
                DeadlineArmResult.Armed -> Unit
                DeadlineArmResult.AlreadySettled -> return OperationEntryResult.NotCurrent
                DeadlineArmResult.InvalidClockOrOverflow -> {
                    disposition = OperationDisposition.DeadlineGuardFailed
                    return OperationEntryResult.InvalidDeadline
                }
            }
        } else {
            deadline?.retireLocked()
        }

        entryDisposition = OperationEntryDisposition.Entered
        return OperationEntryResult.Entered
    }

    internal fun publishNormalReturn(): Boolean = settlementGate.withLock {
        if (entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        returnCell.publishNormalLocked(settlementSampleLocked())
    }

    internal fun publishThrownReturn(thrown: Exception): Boolean = settlementGate.withLock {
        if (entryDisposition != OperationEntryDisposition.Entered) return@withLock false
        returnCell.publishThrownLocked(settlementSampleLocked(), thrown)
    }

    internal fun publishDirectFatalReturn(raw: Throwable): Boolean = settlementGate.withLock {
        publishDirectFatalReturnLocked(raw)
    }

    internal fun publishDirectFatalReturnLocked(raw: Throwable): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        require(raw !is Exception)
        if (entryDisposition != OperationEntryDisposition.Entered) return false
        return returnCell.publishThrownLocked(settlementSampleLocked(), raw)
    }

    internal fun settleInertBeforeEntry(): Boolean = settlementGate.withLock {
        settleInertBeforeEntryLocked()
    }

    internal fun settleInertBeforeEntryLocked(): Boolean {
        check(settlementGate.isHeldByCurrentThread)
        if (entryDisposition != OperationEntryDisposition.Unentered ||
            returnCell.disposition != OperationReturnDisposition.Empty
        ) {
            return false
        }
        entryDisposition = OperationEntryDisposition.Cancelled
        deadlineOccurrence?.retireLocked()
        if (disposition == OperationDisposition.Pending || disposition == OperationDisposition.Cleanup) {
            disposition = OperationDisposition.Cancelled
        }
        return true
    }

    internal fun arbitrate(): OperationArbitration = settlementGate.withLock {
        arbitrateLocked()
    }

    internal fun arbitrateTerminal(mandatoryCleanup: Boolean): OperationTerminalArbitration =
        settlementGate.withLock {
            if (returnCell.disposition != OperationReturnDisposition.Empty &&
                returnCell.use == OperationReturnUse.Unclaimed
            ) {
                val arbitration = terminalArbitration(arbitrateLocked())
                if (arbitration != OperationTerminalArbitration.Transferred) return@withLock arbitration
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
        val activeDeadline = deadlineOccurrence
        val activeWake = activeDeadline?.controlWakeLink
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

        if (domain == OperationDomain.Active && disposition == OperationDisposition.Pending &&
            activeWake?.submissionDisposition == ControlWakeSubmissionDisposition.Rejected
        ) {
            return when (activeWake.schedulingThrowableDisposition) {
                ControlWakeThrowableDisposition.NonfatalException -> {
                    checkNotNull(activeDeadline).retireLocked()
                    disposition = OperationDisposition.SchedulerRejected
                    OperationArbitration.SchedulerRejected
                }

                ControlWakeThrowableDisposition.None,
                ControlWakeThrowableDisposition.FatalThrowable,
                    -> OperationArbitration.None
            }
        }

        if (domain != OperationDomain.Active || disposition != OperationDisposition.Pending) {
            return when (disposition) {
                OperationDisposition.DeadlineGuardFailed -> OperationArbitration.DeadlineGuardFailed
                else -> OperationArbitration.None
            }
        }

        val deadline = deadlineOccurrence ?: return OperationArbitration.None
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
