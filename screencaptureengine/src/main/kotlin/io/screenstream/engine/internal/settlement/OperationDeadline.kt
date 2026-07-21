package io.screenstream.engine.internal.settlement

import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val CONTROL_WAKE_STATE_BITS: Int = 3
private const val CONTROL_WAKE_CALLBACK_QUEUED: Long = 0L
private const val CONTROL_WAKE_CALLBACK_RUNNING: Long = 1L
private const val CONTROL_WAKE_CALLBACK_SUPPRESSED: Long = 2L
private const val CONTROL_WAKE_CALLBACK_TERMINATION_SETTLED: Long = 3L
private const val CONTROL_WAKE_PHYSICAL_PENDING: Long = 0L
private const val CONTROL_WAKE_PHYSICAL_ON_STACK: Long = 1L
private const val CONTROL_WAKE_PHYSICAL_RETURNED: Long = 2L
private const val CONTROL_WAKE_PHYSICAL_REMOVED: Long = 3L
private const val CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED: Long = 4L
private const val CONTROL_WAKE_PHYSICAL_PREPARED: Long = 5L
private const val CONTROL_WAKE_PHYSICAL_TERMINATION_SETTLED: Long = 6L
private const val MAX_CONTROL_WAKE_GENERATION: Long = Long.MAX_VALUE shr CONTROL_WAKE_STATE_BITS

private fun controlWakeState(generation: Long, phase: Long): Long =
    generation shl CONTROL_WAKE_STATE_BITS or phase

internal fun interface EngineClock {
    fun nowNanos(): Long
}

internal fun interface SettlementSignal {
    fun signal()
}

internal fun interface ControlWakeBody {
    fun run(generation: Long)
}

internal interface ControlSchedulerRuntimeIdentity

internal interface ControlSchedulerTerminationEvidence {
    val runtimeIdentity: ControlSchedulerRuntimeIdentity

    fun isReleasePublished(): Boolean
}

internal enum class ControlPoisonPublicationDisposition {
    Published,
    AlreadyPublished,
    ClaimExhausted,
}

internal enum class ControlPoisonClaim {
    FiniteDeadlineSuccessorCommit,
    WakeScheduleInvocation,
    DrainerExecuteInvocation,
    WakeCancelInvocation,
    WakeRemoveInvocation,
    ReplacementWorkerEngineEntry,
}

internal enum class ControlPoisonClaimOutcome {
    Admitted,
    PoisonFenced,
    ClaimExhausted,
}

internal class ControlPoisonAuthority internal constructor() {
    private val firstRaw: AtomicReference<Throwable?> = AtomicReference(null)
    private val state: AtomicLong = AtomicLong(OPEN_INITIAL)

    internal fun publish(raw: Throwable): ControlPoisonPublicationDisposition {
        while (true) {
            val observed = state.get()
            when {
                observed >= OPEN_INITIAL -> if (state.compareAndSet(observed, PUBLISHING)) {
                    firstRaw.set(raw)
                    state.set(POISONED)
                    return ControlPoisonPublicationDisposition.Published
                }

                observed == PUBLISHING -> Unit
                observed == CLAIM_EXHAUSTED -> return ControlPoisonPublicationDisposition.ClaimExhausted
                else -> return ControlPoisonPublicationDisposition.AlreadyPublished
            }
        }
    }

    internal fun observe(): Throwable? {
        while (true) {
            when (state.get()) {
                PUBLISHING -> Unit
                POISONED -> return firstRaw.get()
                CLAIM_EXHAUSTED -> return CLAIM_EXHAUSTED_RAW
                else -> return null
            }
        }
    }

    internal fun claim(@Suppress("UNUSED_PARAMETER") claim: ControlPoisonClaim): ControlPoisonClaimOutcome {
        while (true) {
            val observed = state.get()
            when {
                observed in OPEN_INITIAL..<OPEN_MAX ->
                    if (state.compareAndSet(observed, observed + 1L)) {
                        return ControlPoisonClaimOutcome.Admitted
                    }

                observed == OPEN_MAX -> if (state.compareAndSet(OPEN_MAX, CLAIM_EXHAUSTED)) {
                    return ControlPoisonClaimOutcome.ClaimExhausted
                }

                observed == CLAIM_EXHAUSTED -> return ControlPoisonClaimOutcome.ClaimExhausted
                else -> return ControlPoisonClaimOutcome.PoisonFenced
            }
        }
    }

    private companion object {
        private const val OPEN_INITIAL: Long = 0L
        private const val OPEN_MAX: Long = Long.MAX_VALUE
        private const val PUBLISHING: Long = -1L
        private const val POISONED: Long = -2L
        private const val CLAIM_EXHAUSTED: Long = -3L
        private val CLAIM_EXHAUSTED_RAW: Throwable = IllegalStateException("Control poison claim identity exhausted")
    }
}

internal enum class ControlSchedulerRejectionReceipt {
    None,
    BindingRejected,
    ExecutorRejected,
}

internal enum class ControlSchedulerRejectionReceiptPublicationOutcome {
    Published,
    NotEligible,
}

internal interface ControlScheduledTaskRecord {
    fun bindDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean

    fun publishBindingRejected(delegate: Runnable): ControlSchedulerRejectionReceiptPublicationOutcome

    fun publishExecutorRejected(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlSchedulerRejectionReceiptPublicationOutcome

    fun observeSchedulerRejectionReceipt(): ControlSchedulerRejectionReceipt

    fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean

    fun markBoundOuterReturned(outerWrapper: Runnable): Boolean

    fun markBoundTaskInert(outerWrapper: Runnable, delegate: Runnable): Boolean

    fun observeAfterExecuteThrowable(outerWrapper: Runnable, delegate: Runnable): Throwable?

    fun observeAfterExecuteDirectFatal(outerWrapper: Runnable, delegate: Runnable): Throwable?

    fun claimAfterExecutePortInvocation(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean

    fun observeAfterExecutePortDisposition(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlAfterExecutePortDisposition?

    fun publishAfterExecutePortReturned(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean

    fun publishAfterExecutePortThrown(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
        portRaw: Throwable,
    ): Boolean

    fun publishAfterExecuteApplied(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable?,
    ): Boolean
}

internal interface ControlScheduledRunner : Runnable {
    val scheduledTaskRecord: ControlScheduledTaskRecord
}

internal enum class ControlWakeKind {
    Deadline,
    Pacing,
    Repeat,
    Stats,
}

internal class ControlWakeIdentity internal constructor(
    internal val kind: ControlWakeKind,
    internal val leafIdentity: Long,
) {
    init {
        require(leafIdentity > 0L)
    }
}

internal enum class ControlWakeSubmissionDisposition {
    None,
    Requested,
    Submitting,
    Accepted,
    Rejected,
    NotInvoked,
    PoisonFenced,
}

internal enum class ControlWakeScheduleInvocationDisposition {
    None,
    Claimed,
    Invoked,
    NotInvoked,
}

internal enum class ControlWakeScheduleReturnPublicationOutcome {
    Accepted,
    Detached,
    NotEligible,
}

internal enum class ControlWakeScheduleFailurePublicationOutcome {
    Rejected,
    Ambiguous,
    NotEligible,
}

internal enum class ControlWakeActionPublicationOutcome {
    Published,
    NotEligible,
}

internal enum class ControlWakeFireDisposition {
    Empty,
    Fired,
}

internal enum class ControlWakeBodyDisposition {
    Empty,
    Running,
    Returned,
    Thrown,
}

internal enum class ControlWakeCancellationDisposition {
    None,
    Requested,
    Cancelling,
    Published,
    NotNeeded,
    PoisonFenced,
}

internal enum class ControlWakeInvocationDisposition {
    None,
    Admitted,
    NotInvoked,
}

internal enum class ControlWakeDetachedAcceptanceDisposition {
    None,
    Returned,
    Settling,
    Settled,
}

internal enum class ControlWakeSuppressionDisposition {
    NotAttempted,
    Succeeded,
    Failed,
}

internal enum class ControlWakeOuterRemovalDisposition {
    NotAttempted,
    Returned,
    Thrown,
    PoisonFenced,
}

internal enum class ControlWakeRemovalFailureDisposition {
    None,
    OrdinaryException,
    DirectFatal,
    OtherThrowable,
}

internal enum class ControlWakeThrowableDisposition {
    None,
    NonfatalException,
    FatalThrowable,
}

internal enum class ControlWakePhysicalDisposition {
    Unused,
    Prepared,
    Pending,
    OnStack,
    Returned,
    Removed,
    NotSubmitted,
    TerminationSettled,
    Stale,
}

internal enum class ControlWakeTerminationApplicationOutcome {
    Applied,
    IdentityMismatch,
    GenerationMismatch,
    NotEligible,
}

internal sealed interface ControlWakeSuccessorResult {
    data object NotEligible : ControlWakeSuccessorResult

    data object IdentityExhausted : ControlWakeSuccessorResult

    data object PoisonFenced : ControlWakeSuccessorResult

    data object ClaimExhausted : ControlWakeSuccessorResult

    class Requested internal constructor(
        internal val action: ControlWakeRequestedAction,
    ) : ControlWakeSuccessorResult
}

internal enum class ControlAfterExecutePortDisposition {
    Pending,
    Invoking,
    Returned,
    Thrown,
}

internal enum class DeadlineDisposition {
    Unarmed,
    Armed,
    Expired,
    Retired,
}

internal enum class DeadlineArmResult {
    Armed,
    AlreadySettled,
    InvalidClockOrOverflow,
}

/**
 * The per-task record shared by the future Controller-owned scheduled wrapper and this link's engine runner.
 * A slot remains bound to [generation] until its physical frame is definitively settled, so a live wrapper can
 * never observe a later generation when the two slots alternate.
 */
internal class ControlWakeTaskRecord private constructor(
    private val owner: ControlWakeLink,
) : ControlScheduledTaskRecord {
    private val physicalState: AtomicLong = AtomicLong(0L)
    private val taskThrowable = AtomicReference<Throwable?>(null)
    private val directFatal = AtomicReference<Throwable?>(null)
    private val afterExecuteBridge = AtomicInteger(AFTER_EXECUTE_PENDING)
    private val afterExecutePort = AtomicInteger(PORT_PENDING)
    private val afterExecutePortFailure = AtomicReference<Throwable?>(null)
    private val exactOuterWrapper = AtomicReference<Runnable?>(null)
    private val exactDelegate = AtomicReference<Runnable?>(null)
    private val rejectedDelegate = AtomicReference<Runnable?>(null)
    private val inertBeforeDelegate = AtomicInteger(INERT_OPEN)
    private val rejectionReceipt = AtomicInteger(REJECTION_NONE)
    private val detachedSettlement = AtomicInteger(DETACHED_NONE)
    private val terminationReceipt = AtomicReference<ControlSchedulerTerminationEvidence?>(null)

    @Volatile
    private var scheduledGeneration: Long = 0L

    internal val runner: ControlScheduledRunner = object : ControlScheduledRunner {
        override val scheduledTaskRecord: ControlScheduledTaskRecord
            get() = this@ControlWakeTaskRecord

        override fun run() {
            if (inertBeforeDelegate.get() == INERT_MARKED) return
            val generation = scheduledGeneration
            try {
                owner.runGeneration(generation)
            } catch (raw: Throwable) {
                taskThrowable.compareAndSet(null, raw)
                if (FatalThrowablePolicy.isDirectFatal(raw)) directFatal.compareAndSet(null, raw)
                throw raw
            }
        }
    }

    internal fun prepareLocked(generation: Long): Boolean {
        val prior = physicalState.get()
        if (prior != 0L) {
            val priorPhase = prior and CONTROL_WAKE_PHASE_MASK
            if (priorPhase != CONTROL_WAKE_PHYSICAL_RETURNED &&
                priorPhase != CONTROL_WAKE_PHYSICAL_REMOVED &&
                priorPhase != CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED &&
                priorPhase != CONTROL_WAKE_PHYSICAL_TERMINATION_SETTLED
            ) {
                return false
            }
        }
        if (detachedSettlement.get() == DETACHED_PENDING) return false
        taskThrowable.set(null)
        directFatal.set(null)
        afterExecuteBridge.set(AFTER_EXECUTE_PENDING)
        afterExecutePort.set(PORT_PENDING)
        afterExecutePortFailure.set(null)
        exactOuterWrapper.set(null)
        exactDelegate.set(null)
        rejectedDelegate.set(null)
        inertBeforeDelegate.set(INERT_OPEN)
        rejectionReceipt.set(REJECTION_NONE)
        detachedSettlement.set(DETACHED_NONE)
        terminationReceipt.set(null)
        scheduledGeneration = generation
        physicalState.set(controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PREPARED))
        return true
    }

    internal fun markSubmissionStarted(generation: Long): Boolean =
        physicalState.compareAndSet(
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PREPARED),
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PENDING),
        )

    internal fun markOuterOnStack(generation: Long): Boolean =
        physicalState.compareAndSet(
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PENDING),
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_ON_STACK),
        )

    internal fun markOuterReturned(generation: Long): Boolean =
        physicalState.compareAndSet(
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_ON_STACK),
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_RETURNED),
        )

    override fun bindDecoratedTask(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): Boolean {
        if (scheduledGeneration != generation()) return false
        if (!exactDelegate.compareAndSet(null, delegate)) return false
        if (exactOuterWrapper.compareAndSet(null, outerWrapper)) return true
        exactDelegate.compareAndSet(delegate, null)
        return false
    }

    override fun publishBindingRejected(
        delegate: Runnable,
    ): ControlSchedulerRejectionReceiptPublicationOutcome {
        if (scheduledGeneration != generation() || exactOuterWrapper.get() != null ||
            exactDelegate.get() != null || rejectionReceipt.get() != REJECTION_NONE ||
            !rejectedDelegate.compareAndSet(null, delegate)
        ) {
            return ControlSchedulerRejectionReceiptPublicationOutcome.NotEligible
        }
        if (!markNotSubmitted(scheduledGeneration)) {
            rejectedDelegate.compareAndSet(delegate, null)
            return ControlSchedulerRejectionReceiptPublicationOutcome.NotEligible
        }
        check(rejectionReceipt.compareAndSet(REJECTION_NONE, REJECTION_BINDING))
        afterExecuteBridge.set(AFTER_EXECUTE_APPLIED)
        return ControlSchedulerRejectionReceiptPublicationOutcome.Published
    }

    override fun publishExecutorRejected(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlSchedulerRejectionReceiptPublicationOutcome {
        if (!matchesDecoratedTask(outerWrapper, delegate) || rejectionReceipt.get() != REJECTION_NONE ||
            !markNotSubmitted(scheduledGeneration)
        ) {
            return ControlSchedulerRejectionReceiptPublicationOutcome.NotEligible
        }
        check(rejectionReceipt.compareAndSet(REJECTION_NONE, REJECTION_EXECUTOR))
        afterExecuteBridge.set(AFTER_EXECUTE_APPLIED)
        return ControlSchedulerRejectionReceiptPublicationOutcome.Published
    }

    override fun observeSchedulerRejectionReceipt(): ControlSchedulerRejectionReceipt =
        rejectionReceipt(rejectionReceipt.get())

    override fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean {
        if (exactOuterWrapper.get() !== outerWrapper) return false
        return markOuterOnStack(scheduledGeneration)
    }

    override fun markBoundOuterReturned(outerWrapper: Runnable): Boolean {
        if (exactOuterWrapper.get() !== outerWrapper) return false
        return markOuterReturned(scheduledGeneration)
    }

    override fun markBoundTaskInert(outerWrapper: Runnable, delegate: Runnable): Boolean {
        if (!matchesDecoratedTask(outerWrapper, delegate) ||
            currentDisposition() != ControlWakePhysicalDisposition.OnStack
        ) {
            return false
        }
        return inertBeforeDelegate.compareAndSet(INERT_OPEN, INERT_MARKED)
    }

    override fun observeAfterExecuteThrowable(outerWrapper: Runnable, delegate: Runnable): Throwable? {
        if (!matchesPendingAfterExecute(outerWrapper, delegate)) return null
        return taskThrowable.get()
    }

    override fun observeAfterExecuteDirectFatal(outerWrapper: Runnable, delegate: Runnable): Throwable? {
        if (!matchesPendingAfterExecute(outerWrapper, delegate)) return null
        val raw = directFatal.get() ?: return null
        return if (taskThrowable.get() === raw) raw else null
    }

    override fun claimAfterExecutePortInvocation(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean {
        if (!matchesPendingThrowable(outerWrapper, delegate, expectedRaw)) return false
        return afterExecutePort.compareAndSet(PORT_PENDING, PORT_INVOKING)
    }

    override fun observeAfterExecutePortDisposition(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlAfterExecutePortDisposition? {
        if (!matchesDecoratedTask(outerWrapper, delegate)) return null
        return portDisposition(afterExecutePort.get())
    }

    override fun publishAfterExecutePortReturned(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean {
        if (!matchesPendingThrowable(outerWrapper, delegate, expectedRaw)) return false
        return afterExecutePort.compareAndSet(PORT_INVOKING, PORT_RETURNED)
    }

    override fun publishAfterExecutePortThrown(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
        portRaw: Throwable,
    ): Boolean {
        if (!matchesPendingThrowable(outerWrapper, delegate, expectedRaw) ||
            afterExecutePort.get() != PORT_INVOKING ||
            !afterExecutePortFailure.compareAndSet(null, portRaw)
        ) {
            return false
        }
        return afterExecutePort.compareAndSet(PORT_INVOKING, PORT_THROWN)
    }

    override fun publishAfterExecuteApplied(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable?,
    ): Boolean {
        if (!matchesDecoratedTask(outerWrapper, delegate) || taskThrowable.get() !== expectedRaw) return false
        if (expectedRaw != null && afterExecutePort.get() != PORT_RETURNED) return false
        return afterExecuteBridge.compareAndSet(AFTER_EXECUTE_PENDING, AFTER_EXECUTE_APPLIED)
    }

    private fun matchesDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean =
        exactOuterWrapper.get() === outerWrapper && exactDelegate.get() === delegate

    private fun matchesPendingAfterExecute(outerWrapper: Runnable, delegate: Runnable): Boolean =
        matchesDecoratedTask(outerWrapper, delegate) && afterExecuteBridge.get() == AFTER_EXECUTE_PENDING

    private fun matchesPendingThrowable(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean = matchesPendingAfterExecute(outerWrapper, delegate) && taskThrowable.get() === expectedRaw

    private fun portDisposition(value: Int): ControlAfterExecutePortDisposition = when (value) {
        PORT_PENDING -> ControlAfterExecutePortDisposition.Pending
        PORT_INVOKING -> ControlAfterExecutePortDisposition.Invoking
        PORT_RETURNED -> ControlAfterExecutePortDisposition.Returned
        else -> ControlAfterExecutePortDisposition.Thrown
    }

    private fun rejectionReceipt(value: Int): ControlSchedulerRejectionReceipt = when (value) {
        REJECTION_BINDING -> ControlSchedulerRejectionReceipt.BindingRejected
        REJECTION_EXECUTOR -> ControlSchedulerRejectionReceipt.ExecutorRejected
        else -> ControlSchedulerRejectionReceipt.None
    }

    internal fun markOuterRemoved(generation: Long): Boolean =
        physicalState.compareAndSet(
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PENDING),
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_REMOVED),
        )

    internal fun markNotSubmitted(generation: Long): Boolean =
        physicalState.compareAndSet(
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PENDING),
            controlWakeState(generation, CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED),
        )

    internal fun applySchedulerTermination(
        generation: Long,
        receipt: ControlSchedulerTerminationEvidence,
    ): Boolean {
        val expectedReceipt = terminationReceipt.get()
        if (expectedReceipt != null) {
            val exactDisposition = disposition(generation)
            return expectedReceipt === receipt &&
                    (exactDisposition == ControlWakePhysicalDisposition.Returned ||
                            exactDisposition == ControlWakePhysicalDisposition.Removed ||
                            exactDisposition == ControlWakePhysicalDisposition.NotSubmitted ||
                            exactDisposition == ControlWakePhysicalDisposition.TerminationSettled)
        }
        val disposition = disposition(generation)
        if (disposition != ControlWakePhysicalDisposition.Pending &&
            disposition != ControlWakePhysicalDisposition.Returned &&
            disposition != ControlWakePhysicalDisposition.Removed &&
            disposition != ControlWakePhysicalDisposition.NotSubmitted
        ) {
            return false
        }
        if (disposition == ControlWakePhysicalDisposition.Returned &&
            afterExecuteBridge.get() != AFTER_EXECUTE_APPLIED
        ) {
            return false
        }
        if (!terminationReceipt.compareAndSet(null, receipt)) return terminationReceipt.get() === receipt
        if (disposition == ControlWakePhysicalDisposition.Pending &&
            !physicalState.compareAndSet(
                controlWakeState(generation, CONTROL_WAKE_PHYSICAL_PENDING),
                controlWakeState(generation, CONTROL_WAKE_PHYSICAL_TERMINATION_SETTLED),
            )
        ) {
            terminationReceipt.compareAndSet(receipt, null)
            return false
        }
        if (disposition != ControlWakePhysicalDisposition.Returned) {
            afterExecuteBridge.set(AFTER_EXECUTE_APPLIED)
        }
        return true
    }

    internal fun generation(): Long = physicalState.get() ushr CONTROL_WAKE_STATE_BITS

    internal fun currentDisposition(): ControlWakePhysicalDisposition = physicalDisposition(physicalState.get())

    internal fun disposition(generation: Long): ControlWakePhysicalDisposition {
        val state = physicalState.get()
        if (state == 0L) return ControlWakePhysicalDisposition.Unused
        if (state ushr CONTROL_WAKE_STATE_BITS != generation) return ControlWakePhysicalDisposition.Stale
        return physicalDisposition(state)
    }

    internal fun matchesBoundOuter(generation: Long, outerWrapper: Runnable): Boolean =
        scheduledGeneration == generation && exactOuterWrapper.get() === outerWrapper

    internal fun markDetachedAcceptance(generation: Long): Boolean =
        scheduledGeneration == generation && detachedSettlement.compareAndSet(DETACHED_NONE, DETACHED_PENDING)

    internal fun markDetachedSettled(generation: Long): Boolean =
        scheduledGeneration == generation && detachedSettlement.compareAndSet(DETACHED_PENDING, DETACHED_SETTLED)

    internal fun isPhysicallySettled(): Boolean = when (currentDisposition()) {
        ControlWakePhysicalDisposition.Unused,
        ControlWakePhysicalDisposition.Prepared,
        ControlWakePhysicalDisposition.Returned,
        ControlWakePhysicalDisposition.Removed,
        ControlWakePhysicalDisposition.NotSubmitted,
        ControlWakePhysicalDisposition.TerminationSettled,
            -> true

        ControlWakePhysicalDisposition.Pending,
        ControlWakePhysicalDisposition.OnStack,
        ControlWakePhysicalDisposition.Stale,
            -> false
    }

    private fun physicalDisposition(state: Long): ControlWakePhysicalDisposition {
        if (state == 0L) return ControlWakePhysicalDisposition.Unused
        return when (state and CONTROL_WAKE_PHASE_MASK) {
            CONTROL_WAKE_PHYSICAL_PREPARED -> ControlWakePhysicalDisposition.Prepared
            CONTROL_WAKE_PHYSICAL_PENDING -> ControlWakePhysicalDisposition.Pending
            CONTROL_WAKE_PHYSICAL_ON_STACK -> ControlWakePhysicalDisposition.OnStack
            CONTROL_WAKE_PHYSICAL_RETURNED -> ControlWakePhysicalDisposition.Returned
            CONTROL_WAKE_PHYSICAL_REMOVED -> ControlWakePhysicalDisposition.Removed
            CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED -> ControlWakePhysicalDisposition.NotSubmitted
            CONTROL_WAKE_PHYSICAL_TERMINATION_SETTLED -> ControlWakePhysicalDisposition.TerminationSettled
            else -> ControlWakePhysicalDisposition.Stale
        }
    }

    internal companion object {
        private const val CONTROL_WAKE_PHASE_MASK: Long = (1L shl CONTROL_WAKE_STATE_BITS) - 1L
        private const val AFTER_EXECUTE_PENDING: Int = 0
        private const val AFTER_EXECUTE_APPLIED: Int = 1
        private const val PORT_PENDING: Int = 0
        private const val PORT_INVOKING: Int = 1
        private const val PORT_RETURNED: Int = 2
        private const val PORT_THROWN: Int = 3
        private const val INERT_OPEN: Int = 0
        private const val INERT_MARKED: Int = 1
        private const val REJECTION_NONE: Int = 0
        private const val REJECTION_BINDING: Int = 1
        private const val REJECTION_EXECUTOR: Int = 2
        private const val DETACHED_NONE: Int = 0
        private const val DETACHED_PENDING: Int = 1
        private const val DETACHED_SETTLED: Int = 2

        internal fun create(owner: ControlWakeLink): ControlWakeTaskRecord = ControlWakeTaskRecord(owner)
    }
}

internal class ControlWakeScheduleAction internal constructor(
    private val owner: ControlWakeLink,
    internal val taskRecord: ControlWakeTaskRecord,
) {
    internal var generation: Long = 0L
        private set

    internal var dueNanos: Long = 0L
        private set

    internal var invocationDisposition: ControlWakeScheduleInvocationDisposition =
        ControlWakeScheduleInvocationDisposition.None
        private set

    internal var runtimeIdentity: ControlSchedulerRuntimeIdentity? = null
        private set

    internal var returnedFuture: Future<*>? = null
        private set

    internal var returnedOuterWrapper: Runnable? = null
        private set

    internal var returnPublicationOutcome: ControlWakeScheduleReturnPublicationOutcome =
        ControlWakeScheduleReturnPublicationOutcome.NotEligible
        private set

    internal var invocationFailure: Throwable? = null
        private set

    internal var invocationFailureThrowableDisposition: ControlWakeThrowableDisposition =
        ControlWakeThrowableDisposition.None
        private set

    internal var invocationRejectionReceipt: ControlSchedulerRejectionReceipt =
        ControlSchedulerRejectionReceipt.None
        private set

    internal var terminationReceipt: ControlSchedulerTerminationEvidence? = null
        private set

    internal var detachedAcceptanceDisposition: ControlWakeDetachedAcceptanceDisposition =
        ControlWakeDetachedAcceptanceDisposition.None
        private set

    internal var detachedCancelInvocationDisposition: ControlWakeInvocationDisposition =
        ControlWakeInvocationDisposition.None
        private set

    internal var detachedCancelReturned: Boolean? = null
        private set

    internal var detachedCancelFailure: Throwable? = null
        private set

    internal var detachedCancelThrowableDisposition: ControlWakeThrowableDisposition =
        ControlWakeThrowableDisposition.None
        private set

    internal var detachedSuppressionDisposition: ControlWakeSuppressionDisposition =
        ControlWakeSuppressionDisposition.NotAttempted
        private set

    internal var detachedRemoveInvocationDisposition: ControlWakeInvocationDisposition =
        ControlWakeInvocationDisposition.None
        private set

    internal var detachedRemovalDisposition: ControlWakeOuterRemovalDisposition =
        ControlWakeOuterRemovalDisposition.NotAttempted
        private set

    internal var detachedRemoveReturned: Boolean? = null
        private set

    internal var detachedRemoveFailure: Throwable? = null
        private set

    internal var detachedRemoveThrowableDisposition: ControlWakeThrowableDisposition =
        ControlWakeThrowableDisposition.None
        private set

    internal var detachedRemovalFailureDisposition: ControlWakeRemovalFailureDisposition =
        ControlWakeRemovalFailureDisposition.None
        private set

    internal var detachedPhysicalDisposition: ControlWakePhysicalDisposition = ControlWakePhysicalDisposition.Unused
        private set

    internal val runner: Runnable
        get() = taskRecord.runner

    internal fun bindLocked(generation: Long, dueNanos: Long) {
        this.generation = generation
        this.dueNanos = dueNanos
        invocationDisposition = ControlWakeScheduleInvocationDisposition.None
        runtimeIdentity = null
        returnedFuture = null
        returnedOuterWrapper = null
        returnPublicationOutcome = ControlWakeScheduleReturnPublicationOutcome.NotEligible
        invocationFailure = null
        invocationFailureThrowableDisposition = ControlWakeThrowableDisposition.None
        invocationRejectionReceipt = ControlSchedulerRejectionReceipt.None
        terminationReceipt = null
        detachedAcceptanceDisposition = ControlWakeDetachedAcceptanceDisposition.None
        detachedCancelInvocationDisposition = ControlWakeInvocationDisposition.None
        detachedCancelReturned = null
        detachedCancelFailure = null
        detachedCancelThrowableDisposition = ControlWakeThrowableDisposition.None
        detachedSuppressionDisposition = ControlWakeSuppressionDisposition.NotAttempted
        detachedRemoveInvocationDisposition = ControlWakeInvocationDisposition.None
        detachedRemovalDisposition = ControlWakeOuterRemovalDisposition.NotAttempted
        detachedRemoveReturned = null
        detachedRemoveFailure = null
        detachedRemoveThrowableDisposition = ControlWakeThrowableDisposition.None
        detachedRemovalFailureDisposition = ControlWakeRemovalFailureDisposition.None
        detachedPhysicalDisposition = ControlWakePhysicalDisposition.Unused
    }

    internal fun claimInvocation(controlPoison: ControlPoisonAuthority): ControlPoisonClaimOutcome =
        owner.claimScheduleInvocation(this, controlPoison)

    internal fun markInvocationStarted(
        runtimeIdentity: ControlSchedulerRuntimeIdentity,
    ): ControlWakeActionPublicationOutcome = owner.markScheduleInvocationStarted(this, runtimeIdentity)

    internal fun publishSignalledPreInvocationFailure(raw: Throwable): ControlWakeActionPublicationOutcome {
        require(!FatalThrowablePolicy.isDirectFatal(raw))
        return owner.publishSchedulePreInvocationFailure(this, raw, signalSettlement = true)
    }

    internal fun publishDirectFatalPreInvocationFailure(raw: Throwable): ControlWakeActionPublicationOutcome {
        require(FatalThrowablePolicy.isDirectFatal(raw))
        return owner.publishSchedulePreInvocationFailure(this, raw, signalSettlement = false)
    }

    internal fun publishInvocationFailure(raw: Throwable): ControlWakeScheduleFailurePublicationOutcome =
        owner.publishScheduleInvocationFailure(this, raw)

    internal fun publishReturned(
        future: Future<*>,
        outerWrapper: Runnable,
    ): ControlWakeScheduleReturnPublicationOutcome = owner.publishSchedulingReturned(this, future, outerWrapper)

    internal fun claimDetachedCancelInvocation(
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome = owner.claimDetachedCancelInvocation(this, controlPoison)

    /** Called only after the exact returned Future returned true from cancel(false). */
    internal fun trySuppressDetached(): Boolean = owner.trySuppressDetached(this)

    internal fun claimDetachedRemoveInvocation(
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome = owner.claimDetachedRemoveInvocation(this, controlPoison)

    internal fun publishDetachedSettlement(
        cancelReturned: Boolean?,
        cancelFailure: Throwable?,
        suppressionDisposition: ControlWakeSuppressionDisposition,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ): ControlWakeActionPublicationOutcome = owner.publishDetachedSettlement(
        action = this,
        cancelReturned = cancelReturned,
        cancelFailure = cancelFailure,
        suppressionDisposition = suppressionDisposition,
        removalDisposition = removalDisposition,
        removalReturned = removalReturned,
        removalFailure = removalFailure,
    )

    internal fun physicalDisposition(): ControlWakePhysicalDisposition = taskRecord.disposition(generation)

    internal fun recordReturnLocked(
        future: Future<*>,
        outerWrapper: Runnable,
        outcome: ControlWakeScheduleReturnPublicationOutcome,
    ) {
        returnedFuture = future
        returnedOuterWrapper = outerWrapper
        returnPublicationOutcome = outcome
        if (outcome == ControlWakeScheduleReturnPublicationOutcome.Detached) {
            detachedAcceptanceDisposition = ControlWakeDetachedAcceptanceDisposition.Returned
        }
    }

    internal fun markDetachedSettlingLocked() {
        detachedAcceptanceDisposition = ControlWakeDetachedAcceptanceDisposition.Settling
    }

    internal fun publishDetachedLocked(
        cancelReturned: Boolean?,
        cancelFailure: Throwable?,
        suppressionDisposition: ControlWakeSuppressionDisposition,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ) {
        detachedCancelReturned = cancelReturned
        detachedCancelFailure = cancelFailure
        detachedCancelThrowableDisposition = throwableDisposition(cancelFailure)
        detachedSuppressionDisposition = suppressionDisposition
        detachedRemovalDisposition = removalDisposition
        detachedRemoveReturned = removalReturned
        detachedRemoveFailure = removalFailure
        detachedRemoveThrowableDisposition = throwableDisposition(removalFailure)
        detachedRemovalFailureDisposition = removalFailureDisposition(removalFailure)
        detachedPhysicalDisposition = taskRecord.disposition(generation)
        detachedAcceptanceDisposition = ControlWakeDetachedAcceptanceDisposition.Settled
    }

    internal fun markDetachedCancelInvocationLocked(disposition: ControlWakeInvocationDisposition) {
        detachedCancelInvocationDisposition = disposition
    }

    internal fun markDetachedRemoveInvocationLocked(disposition: ControlWakeInvocationDisposition) {
        detachedRemoveInvocationDisposition = disposition
    }

    internal fun markInvocationLocked(disposition: ControlWakeScheduleInvocationDisposition) {
        invocationDisposition = disposition
    }

    internal fun bindRuntimeIdentityLocked(runtimeIdentity: ControlSchedulerRuntimeIdentity) {
        this.runtimeIdentity = runtimeIdentity
    }

    internal fun recordInvocationFailureLocked(
        raw: Throwable,
        receipt: ControlSchedulerRejectionReceipt,
    ) {
        invocationFailure = raw
        invocationFailureThrowableDisposition = throwableDisposition(raw)
        invocationRejectionReceipt = receipt
    }

    internal fun recordTerminationReceiptLocked(receipt: ControlSchedulerTerminationEvidence) {
        terminationReceipt = receipt
    }

    private fun throwableDisposition(raw: Throwable?): ControlWakeThrowableDisposition = when (raw) {
        null -> ControlWakeThrowableDisposition.None
        is Exception -> ControlWakeThrowableDisposition.NonfatalException
        else -> ControlWakeThrowableDisposition.FatalThrowable
    }

    private fun removalFailureDisposition(raw: Throwable?): ControlWakeRemovalFailureDisposition = when {
        raw == null -> ControlWakeRemovalFailureDisposition.None
        FatalThrowablePolicy.isDirectFatal(raw) -> ControlWakeRemovalFailureDisposition.DirectFatal
        raw is Exception -> ControlWakeRemovalFailureDisposition.OrdinaryException
        else -> ControlWakeRemovalFailureDisposition.OtherThrowable
    }
}

internal class ControlWakeRequestedAction internal constructor(
    internal val wakeLink: ControlWakeLink,
    internal val generation: Long,
    internal val identity: ControlWakeIdentity,
    internal val dueNanos: Long,
) {
    internal fun claimSubmissionAction(): ControlWakeScheduleAction? =
        wakeLink.claimRequestedSubmissionAction(this)
}

internal class ControlWakeCancellationAction internal constructor(
    private val owner: ControlWakeLink,
) {
    internal var generation: Long = 0L
        private set

    internal var future: Future<*>? = null
        private set

    internal var outerWrapper: Runnable? = null
        private set

    internal var taskRecord: ControlWakeTaskRecord? = null
        private set

    internal fun prepareLocked(
        generation: Long,
        future: Future<*>,
        outerWrapper: Runnable,
        taskRecord: ControlWakeTaskRecord,
    ) {
        this.generation = generation
        this.future = future
        this.outerWrapper = outerWrapper
        this.taskRecord = taskRecord
    }

    /** Called unlocked, and only after the exact Future returned true from cancel(false). */
    internal fun trySuppress(): Boolean = owner.trySuppress(generation)

    internal fun claimCancelInvocation(controlPoison: ControlPoisonAuthority): ControlPoisonClaimOutcome =
        owner.claimCancelInvocation(this, controlPoison)

    internal fun claimRemoveInvocation(controlPoison: ControlPoisonAuthority): ControlPoisonClaimOutcome =
        owner.claimRemoveInvocation(this, controlPoison)
}

internal class FiniteDeadlineEarlySuccessorHandle internal constructor(
    private val occurrence: DeadlineOccurrence,
) {
    internal fun prepare(controlPoison: ControlPoisonAuthority): ControlWakeSuccessorResult =
        occurrence.prepareEarlyWakeSuccessor(controlPoison)
}

/**
 * Generic one-shot Control wake mechanics. It deliberately has no scheduler reference and performs no
 * schedule/cancel/remove call. SessionController executes the claimed actions and publishes their exact results.
 */
internal class ControlWakeLink internal constructor(
    initialGeneration: Long,
    initialIdentity: ControlWakeIdentity,
    private val parentGate: ReentrantLock,
    private val clock: EngineClock,
    private val signal: SettlementSignal,
    private val body: ControlWakeBody? = null,
    private val sampleClockOnFire: Boolean = true,
) {
    init {
        require(initialGeneration in 1L..MAX_CONTROL_WAKE_GENERATION)
    }

    internal var generation: Long = initialGeneration
        private set

    internal var identity: ControlWakeIdentity = initialIdentity
        private set

    internal var dueNanos: Long = NO_TIME
        private set

    internal var submissionDisposition: ControlWakeSubmissionDisposition = ControlWakeSubmissionDisposition.None
        private set

    internal var scheduleInvocationDisposition: ControlWakeScheduleInvocationDisposition =
        ControlWakeScheduleInvocationDisposition.None
        private set

    internal var acceptedFuture: Future<*>? = null
        private set

    internal var acceptedOuterWrapper: Runnable? = null
        private set

    internal var schedulingFailure: Throwable? = null
        private set

    internal var schedulingThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
        private set

    internal var schedulerRejectionReceipt: ControlSchedulerRejectionReceipt =
        ControlSchedulerRejectionReceipt.None
        private set

    internal var schedulerRuntimeIdentity: ControlSchedulerRuntimeIdentity? = null
        private set

    internal var schedulerTerminationReceipt: ControlSchedulerTerminationEvidence? = null
        private set

    internal var fireDisposition: ControlWakeFireDisposition = ControlWakeFireDisposition.Empty
        private set

    internal var firedAtNanos: Long = NO_TIME
        private set

    internal var bodyDisposition: ControlWakeBodyDisposition = ControlWakeBodyDisposition.Empty
        private set

    internal var bodyFailure: Throwable? = null
        private set

    internal var bodyThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
        private set

    internal var cancellationDisposition: ControlWakeCancellationDisposition = ControlWakeCancellationDisposition.None
        private set

    internal var cancelInvocationDisposition: ControlWakeInvocationDisposition = ControlWakeInvocationDisposition.None
        private set

    internal var cancellationReturned: Boolean? = null
        private set

    internal var cancellationFailure: Throwable? = null
        private set

    internal var cancellationThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
        private set

    internal var suppressionDisposition: ControlWakeSuppressionDisposition = ControlWakeSuppressionDisposition.NotAttempted
        private set

    internal var outerRemovalDisposition: ControlWakeOuterRemovalDisposition = ControlWakeOuterRemovalDisposition.NotAttempted
        private set

    internal var removeInvocationDisposition: ControlWakeInvocationDisposition = ControlWakeInvocationDisposition.None
        private set

    internal var outerRemovalReturned: Boolean? = null
        private set

    internal var outerRemovalFailure: Throwable? = null
        private set

    internal var outerRemovalThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
        private set

    internal var outerRemovalFailureDisposition: ControlWakeRemovalFailureDisposition =
        ControlWakeRemovalFailureDisposition.None
        private set

    private val callbackState: AtomicLong = AtomicLong(
        controlWakeState(initialGeneration, CONTROL_WAKE_CALLBACK_QUEUED),
    )
    private val finiteDeadlineEarlySuccessor: AtomicReference<FiniteDeadlineEarlySuccessorHandle?> =
        AtomicReference(null)

    private val taskRecords: Array<ControlWakeTaskRecord> = arrayOf(
        ControlWakeTaskRecord.create(this),
        ControlWakeTaskRecord.create(this),
    )
    private val scheduleActions: Array<ControlWakeScheduleAction> = arrayOf(
        ControlWakeScheduleAction(this, taskRecords[0]),
        ControlWakeScheduleAction(this, taskRecords[1]),
    )
    private val cancellationAction: ControlWakeCancellationAction = ControlWakeCancellationAction(this)
    private var currentTaskRecord: ControlWakeTaskRecord = taskRecordFor(initialGeneration)

    init {
        check(currentTaskRecord.prepareLocked(initialGeneration))
    }

    internal fun requestLocked(dueNanos: Long): Boolean {
        check(parentGate.isHeldByCurrentThread)
        if (dueNanos < 0L || submissionDisposition != ControlWakeSubmissionDisposition.None) return false
        this.dueNanos = dueNanos
        submissionDisposition = ControlWakeSubmissionDisposition.Requested
        return true
    }

    internal fun claimSubmissionActionLocked(): ControlWakeScheduleAction? {
        check(parentGate.isHeldByCurrentThread)
        if (submissionDisposition != ControlWakeSubmissionDisposition.Requested) return null
        val action = scheduleActionFor(generation)
        if (!action.taskRecord.markSubmissionStarted(generation)) return null
        action.bindLocked(generation, dueNanos)
        currentTaskRecord = action.taskRecord
        submissionDisposition = ControlWakeSubmissionDisposition.Submitting
        return action
    }

    internal fun claimSubmissionAction(): ControlWakeScheduleAction? =
        parentGate.withLock { claimSubmissionActionLocked() }

    internal fun prepareBoundFiniteDeadlineEarlySuccessor(
        controlPoison: ControlPoisonAuthority,
    ): ControlWakeSuccessorResult =
        finiteDeadlineEarlySuccessor.get()?.prepare(controlPoison) ?: ControlWakeSuccessorResult.NotEligible

    internal fun claimRequestedSubmissionAction(action: ControlWakeRequestedAction): ControlWakeScheduleAction? {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            if (action.wakeLink !== this ||
                action.generation != generation ||
                action.identity !== identity ||
                action.dueNanos != dueNanos
            ) {
                return@withLock null
            }
            claimSubmissionActionLocked()
        }
    }

    internal fun publishSchedulingReturned(
        action: ControlWakeScheduleAction,
        future: Future<*>,
        outerWrapper: Runnable,
    ): ControlWakeScheduleReturnPublicationOutcome {
        val outcome = parentGate.withLock {
            if (action.invocationDisposition != ControlWakeScheduleInvocationDisposition.Invoked ||
                action.returnedFuture != null || action.returnedOuterWrapper != null ||
                action.taskRecord.observeSchedulerRejectionReceipt() != ControlSchedulerRejectionReceipt.None ||
                !action.taskRecord.matchesBoundOuter(action.generation, outerWrapper)
            ) {
                return@withLock ControlWakeScheduleReturnPublicationOutcome.NotEligible
            }
            if (!matchesSubmittingActionLocked(action)) {
                if (!action.taskRecord.markDetachedAcceptance(action.generation)) {
                    return@withLock ControlWakeScheduleReturnPublicationOutcome.NotEligible
                }
                action.recordReturnLocked(future, outerWrapper, ControlWakeScheduleReturnPublicationOutcome.Detached)
                return@withLock ControlWakeScheduleReturnPublicationOutcome.Detached
            }
            acceptedFuture = future
            acceptedOuterWrapper = outerWrapper
            submissionDisposition = ControlWakeSubmissionDisposition.Accepted
            action.recordReturnLocked(future, outerWrapper, ControlWakeScheduleReturnPublicationOutcome.Accepted)
            ControlWakeScheduleReturnPublicationOutcome.Accepted
        }
        if (outcome == ControlWakeScheduleReturnPublicationOutcome.Accepted) signal.signal()
        return outcome
    }

    internal fun publishScheduleInvocationFailure(
        action: ControlWakeScheduleAction,
        failure: Throwable,
    ): ControlWakeScheduleFailurePublicationOutcome {
        return parentGate.withLock {
            if (action.invocationDisposition != ControlWakeScheduleInvocationDisposition.Invoked ||
                action.invocationFailure != null || action.returnedFuture != null ||
                action.taskRecord.disposition(action.generation) == ControlWakePhysicalDisposition.Stale
            ) {
                return@withLock ControlWakeScheduleFailurePublicationOutcome.NotEligible
            }
            val receipt = action.taskRecord.observeSchedulerRejectionReceipt()
            action.recordInvocationFailureLocked(failure, receipt)
            if (matchesSubmittingActionLocked(action)) {
                schedulingFailure = failure
                schedulingThrowableDisposition = throwableDisposition(failure)
                schedulerRejectionReceipt = receipt
                if (receipt != ControlSchedulerRejectionReceipt.None) {
                    submissionDisposition = ControlWakeSubmissionDisposition.Rejected
                    cancellationDisposition = ControlWakeCancellationDisposition.NotNeeded
                }
            }
            if (receipt != ControlSchedulerRejectionReceipt.None) {
                ControlWakeScheduleFailurePublicationOutcome.Rejected
            } else {
                ControlWakeScheduleFailurePublicationOutcome.Ambiguous
            }
        }
    }

    internal fun publishSchedulePreInvocationFailure(
        action: ControlWakeScheduleAction,
        failure: Throwable,
        signalSettlement: Boolean,
    ): ControlWakeActionPublicationOutcome {
        val published = parentGate.withLock {
            if (!matchesSubmittingActionLocked(action) ||
                scheduleInvocationDisposition != ControlWakeScheduleInvocationDisposition.Claimed
            ) {
                return@withLock false
            }
            schedulingFailure = failure
            schedulingThrowableDisposition = throwableDisposition(failure)
            action.recordInvocationFailureLocked(failure, ControlSchedulerRejectionReceipt.None)
            scheduleInvocationDisposition = ControlWakeScheduleInvocationDisposition.NotInvoked
            action.markInvocationLocked(ControlWakeScheduleInvocationDisposition.NotInvoked)
            submissionDisposition = ControlWakeSubmissionDisposition.NotInvoked
            cancellationDisposition = ControlWakeCancellationDisposition.NotNeeded
            check(currentTaskRecord.markNotSubmitted(generation))
            true
        }
        if (published && signalSettlement) signal.signal()
        return if (published) {
            ControlWakeActionPublicationOutcome.Published
        } else {
            ControlWakeActionPublicationOutcome.NotEligible
        }
    }

    internal fun claimScheduleInvocation(
        action: ControlWakeScheduleAction,
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            check(matchesSubmittingActionLocked(action))
            check(scheduleInvocationDisposition == ControlWakeScheduleInvocationDisposition.None)
            when (val outcome = controlPoison.claim(ControlPoisonClaim.WakeScheduleInvocation)) {
                ControlPoisonClaimOutcome.Admitted -> {
                    scheduleInvocationDisposition = ControlWakeScheduleInvocationDisposition.Claimed
                    action.markInvocationLocked(ControlWakeScheduleInvocationDisposition.Claimed)
                    outcome
                }

                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    scheduleInvocationDisposition = ControlWakeScheduleInvocationDisposition.NotInvoked
                    action.markInvocationLocked(ControlWakeScheduleInvocationDisposition.NotInvoked)
                    submissionDisposition = ControlWakeSubmissionDisposition.PoisonFenced
                    cancellationDisposition = ControlWakeCancellationDisposition.NotNeeded
                    check(currentTaskRecord.markNotSubmitted(generation))
                    outcome
                }
            }
        }
    }

    internal fun markScheduleInvocationStarted(
        action: ControlWakeScheduleAction,
        runtimeIdentity: ControlSchedulerRuntimeIdentity,
    ): ControlWakeActionPublicationOutcome = parentGate.withLock {
        if (!matchesSubmittingActionLocked(action) ||
            scheduleInvocationDisposition != ControlWakeScheduleInvocationDisposition.Claimed
        ) {
            return@withLock ControlWakeActionPublicationOutcome.NotEligible
        }
        scheduleInvocationDisposition = ControlWakeScheduleInvocationDisposition.Invoked
        action.markInvocationLocked(ControlWakeScheduleInvocationDisposition.Invoked)
        action.bindRuntimeIdentityLocked(runtimeIdentity)
        schedulerRuntimeIdentity = runtimeIdentity
        ControlWakeActionPublicationOutcome.Published
    }

    internal fun claimDetachedCancelInvocation(
        action: ControlWakeScheduleAction,
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            check(action.detachedAcceptanceDisposition == ControlWakeDetachedAcceptanceDisposition.Returned)
            check(action.detachedCancelInvocationDisposition == ControlWakeInvocationDisposition.None)
            when (val outcome = controlPoison.claim(ControlPoisonClaim.WakeCancelInvocation)) {
                ControlPoisonClaimOutcome.Admitted -> {
                    action.markDetachedCancelInvocationLocked(ControlWakeInvocationDisposition.Admitted)
                    action.markDetachedSettlingLocked()
                    outcome
                }

                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    action.markDetachedCancelInvocationLocked(ControlWakeInvocationDisposition.NotInvoked)
                    action.markDetachedRemoveInvocationLocked(ControlWakeInvocationDisposition.NotInvoked)
                    action.publishDetachedLocked(
                        cancelReturned = null,
                        cancelFailure = null,
                        suppressionDisposition = ControlWakeSuppressionDisposition.NotAttempted,
                        removalDisposition = ControlWakeOuterRemovalDisposition.PoisonFenced,
                        removalReturned = null,
                        removalFailure = null,
                    )
                    check(action.taskRecord.markDetachedSettled(action.generation))
                    outcome
                }
            }
        }
    }

    internal fun claimDetachedRemoveInvocation(
        action: ControlWakeScheduleAction,
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            check(action.detachedAcceptanceDisposition == ControlWakeDetachedAcceptanceDisposition.Settling)
            check(action.detachedCancelInvocationDisposition == ControlWakeInvocationDisposition.Admitted)
            check(action.detachedRemoveInvocationDisposition == ControlWakeInvocationDisposition.None)
            when (val outcome = controlPoison.claim(ControlPoisonClaim.WakeRemoveInvocation)) {
                ControlPoisonClaimOutcome.Admitted -> {
                    action.markDetachedRemoveInvocationLocked(ControlWakeInvocationDisposition.Admitted)
                    outcome
                }

                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    action.markDetachedRemoveInvocationLocked(ControlWakeInvocationDisposition.NotInvoked)
                    outcome
                }
            }
        }
    }

    internal fun trySuppressDetached(action: ControlWakeScheduleAction): Boolean = parentGate.withLock {
        check(action.returnPublicationOutcome == ControlWakeScheduleReturnPublicationOutcome.Detached)
        check(action.returnedFuture != null && action.returnedOuterWrapper != null)
        check(action.detachedAcceptanceDisposition == ControlWakeDetachedAcceptanceDisposition.Settling)
        check(action.detachedCancelInvocationDisposition == ControlWakeInvocationDisposition.Admitted)
        trySuppress(action.generation)
    }

    internal fun publishDetachedSettlement(
        action: ControlWakeScheduleAction,
        cancelReturned: Boolean?,
        cancelFailure: Throwable?,
        suppressionDisposition: ControlWakeSuppressionDisposition,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ): ControlWakeActionPublicationOutcome {
        require((cancelReturned == null) != (cancelFailure == null))
        require(
            cancelReturned == true && suppressionDisposition != ControlWakeSuppressionDisposition.NotAttempted ||
                    cancelReturned != true && suppressionDisposition == ControlWakeSuppressionDisposition.NotAttempted,
        )
        val directFatalCancellation = cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)
        return parentGate.withLock {
            if (action.detachedAcceptanceDisposition != ControlWakeDetachedAcceptanceDisposition.Settling ||
                action.detachedCancelInvocationDisposition != ControlWakeInvocationDisposition.Admitted
            ) {
                return@withLock ControlWakeActionPublicationOutcome.NotEligible
            }
            validateDetachedRemovalPublicationLocked(
                action = action,
                directFatalCancellation = directFatalCancellation,
                removalDisposition = removalDisposition,
                removalReturned = removalReturned,
                removalFailure = removalFailure,
            )
            if (removalDisposition == ControlWakeOuterRemovalDisposition.Returned && removalReturned == true) {
                action.taskRecord.markOuterRemoved(action.generation)
            }
            action.publishDetachedLocked(
                cancelReturned = cancelReturned,
                cancelFailure = cancelFailure,
                suppressionDisposition = suppressionDisposition,
                removalDisposition = removalDisposition,
                removalReturned = removalReturned,
                removalFailure = removalFailure,
            )
            check(action.taskRecord.markDetachedSettled(action.generation))
            ControlWakeActionPublicationOutcome.Published
        }
    }

    internal fun requestCancellationLocked(): Boolean {
        check(parentGate.isHeldByCurrentThread)
        if (cancellationDisposition != ControlWakeCancellationDisposition.None) return false
        cancellationDisposition = when (submissionDisposition) {
            ControlWakeSubmissionDisposition.None -> ControlWakeCancellationDisposition.NotNeeded
            ControlWakeSubmissionDisposition.Requested -> {
                submissionDisposition = ControlWakeSubmissionDisposition.None
                ControlWakeCancellationDisposition.NotNeeded
            }

            ControlWakeSubmissionDisposition.Submitting -> ControlWakeCancellationDisposition.Requested
            ControlWakeSubmissionDisposition.Accepted ->
                if (fireDisposition == ControlWakeFireDisposition.Fired) {
                    ControlWakeCancellationDisposition.NotNeeded
                } else {
                    ControlWakeCancellationDisposition.Requested
                }

            ControlWakeSubmissionDisposition.Rejected -> ControlWakeCancellationDisposition.NotNeeded
            ControlWakeSubmissionDisposition.NotInvoked -> ControlWakeCancellationDisposition.NotNeeded
            ControlWakeSubmissionDisposition.PoisonFenced -> ControlWakeCancellationDisposition.NotNeeded
        }
        return true
    }

    internal fun claimCancellationActionLocked(): ControlWakeCancellationAction? {
        check(parentGate.isHeldByCurrentThread)
        if (submissionDisposition != ControlWakeSubmissionDisposition.Accepted ||
            cancellationDisposition != ControlWakeCancellationDisposition.Requested
        ) {
            return null
        }
        val future = acceptedFuture ?: return null
        val wrapper = acceptedOuterWrapper ?: return null
        cancellationAction.prepareLocked(generation, future, wrapper, currentTaskRecord)
        cancellationDisposition = ControlWakeCancellationDisposition.Cancelling
        return cancellationAction
    }

    internal fun claimCancellationAction(): ControlWakeCancellationAction? =
        parentGate.withLock { claimCancellationActionLocked() }

    internal fun claimCancelInvocation(
        action: ControlWakeCancellationAction,
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            check(matchesCancellingActionLocked(action))
            check(cancelInvocationDisposition == ControlWakeInvocationDisposition.None)
            when (val outcome = controlPoison.claim(ControlPoisonClaim.WakeCancelInvocation)) {
                ControlPoisonClaimOutcome.Admitted -> {
                    cancelInvocationDisposition = ControlWakeInvocationDisposition.Admitted
                    outcome
                }

                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    cancelInvocationDisposition = ControlWakeInvocationDisposition.NotInvoked
                    removeInvocationDisposition = ControlWakeInvocationDisposition.NotInvoked
                    cancellationDisposition = ControlWakeCancellationDisposition.PoisonFenced
                    outerRemovalDisposition = ControlWakeOuterRemovalDisposition.PoisonFenced
                    outcome
                }
            }
        }
    }

    internal fun claimRemoveInvocation(
        action: ControlWakeCancellationAction,
        controlPoison: ControlPoisonAuthority,
    ): ControlPoisonClaimOutcome {
        check(!parentGate.isHeldByCurrentThread)
        return parentGate.withLock {
            check(matchesCancellingActionLocked(action))
            check(cancelInvocationDisposition == ControlWakeInvocationDisposition.Admitted)
            check(removeInvocationDisposition == ControlWakeInvocationDisposition.None)
            when (val outcome = controlPoison.claim(ControlPoisonClaim.WakeRemoveInvocation)) {
                ControlPoisonClaimOutcome.Admitted -> {
                    removeInvocationDisposition = ControlWakeInvocationDisposition.Admitted
                    outcome
                }

                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    removeInvocationDisposition = ControlWakeInvocationDisposition.NotInvoked
                    outcome
                }
            }
        }
    }

    internal fun publishCancellation(
        action: ControlWakeCancellationAction,
        cancelReturned: Boolean?,
        cancelFailure: Throwable?,
        suppressionDisposition: ControlWakeSuppressionDisposition,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ): Boolean {
        require((cancelReturned == null) != (cancelFailure == null))
        require(
            cancelReturned == true && suppressionDisposition != ControlWakeSuppressionDisposition.NotAttempted ||
                    cancelReturned != true && suppressionDisposition == ControlWakeSuppressionDisposition.NotAttempted,
        )
        val directFatalCancellation = cancelFailure != null && FatalThrowablePolicy.isDirectFatal(cancelFailure)
        val published = parentGate.withLock {
            if (!matchesCancellingActionLocked(action)) return@withLock false
            require(cancelInvocationDisposition == ControlWakeInvocationDisposition.Admitted)
            validateRemovalPublicationLocked(
                directFatalCancellation = directFatalCancellation,
                removalDisposition = removalDisposition,
                removalReturned = removalReturned,
                removalFailure = removalFailure,
            )
            cancellationReturned = cancelReturned
            cancellationFailure = cancelFailure
            cancellationThrowableDisposition = throwableDisposition(cancelFailure)
            this.suppressionDisposition = suppressionDisposition
            outerRemovalReturned = removalReturned
            outerRemovalFailure = removalFailure
            outerRemovalDisposition = removalDisposition
            outerRemovalThrowableDisposition = throwableDisposition(removalFailure)
            outerRemovalFailureDisposition = removalFailureDisposition(removalFailure)
            if (removalDisposition == ControlWakeOuterRemovalDisposition.Returned && removalReturned == true) {
                currentTaskRecord.markOuterRemoved(generation)
            }
            cancellationDisposition = ControlWakeCancellationDisposition.Published
            true
        }
        if (published &&
            cancelReturned == true &&
            suppressionDisposition == ControlWakeSuppressionDisposition.Succeeded &&
            (removalDisposition == ControlWakeOuterRemovalDisposition.Returned ||
                    removalDisposition == ControlWakeOuterRemovalDisposition.Thrown &&
                    removalFailureDisposition(removalFailure) ==
                    ControlWakeRemovalFailureDisposition.OrdinaryException)
        ) {
            signal.signal()
        }
        return published
    }

    internal fun prepareSuccessorLocked(
        nextIdentity: ControlWakeIdentity,
        nextDueNanos: Long,
        controlPoison: ControlPoisonAuthority,
    ): ControlWakeSuccessorResult {
        check(parentGate.isHeldByCurrentThread)
        if (nextDueNanos < 0L ||
            submissionDisposition != ControlWakeSubmissionDisposition.Accepted ||
            schedulingFailure != null ||
            schedulingThrowableDisposition != ControlWakeThrowableDisposition.None ||
            cancellationFailure != null ||
            cancellationThrowableDisposition != ControlWakeThrowableDisposition.None ||
            outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.DirectFatal ||
            outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.OtherThrowable ||
            !isSuccessorOperationallyEligibleLocked()
        ) {
            return ControlWakeSuccessorResult.NotEligible
        }
        if (generation >= MAX_CONTROL_WAKE_GENERATION) return ControlWakeSuccessorResult.IdentityExhausted
        when (controlPoison.claim(ControlPoisonClaim.FiniteDeadlineSuccessorCommit)) {
            ControlPoisonClaimOutcome.Admitted -> Unit
            ControlPoisonClaimOutcome.PoisonFenced -> return ControlWakeSuccessorResult.PoisonFenced
            ControlPoisonClaimOutcome.ClaimExhausted -> return ControlWakeSuccessorResult.ClaimExhausted
        }

        val currentGeneration = generation
        val currentCallbackState = callbackState.get()
        val expectedRunning = controlWakeState(currentGeneration, CONTROL_WAKE_CALLBACK_RUNNING)
        val expectedSuppressed = controlWakeState(currentGeneration, CONTROL_WAKE_CALLBACK_SUPPRESSED)
        if (currentCallbackState != expectedRunning && currentCallbackState != expectedSuppressed) {
            return ControlWakeSuccessorResult.NotEligible
        }
        val nextGeneration = currentGeneration + 1L
        val nextRecord = taskRecordFor(nextGeneration)
        if (!nextRecord.prepareLocked(nextGeneration)) return ControlWakeSuccessorResult.NotEligible
        if (!callbackState.compareAndSet(
                currentCallbackState,
                controlWakeState(nextGeneration, CONTROL_WAKE_CALLBACK_QUEUED),
            )
        ) {
            return ControlWakeSuccessorResult.NotEligible
        }

        generation = nextGeneration
        identity = nextIdentity
        dueNanos = nextDueNanos
        currentTaskRecord = nextRecord
        submissionDisposition = ControlWakeSubmissionDisposition.Requested
        scheduleInvocationDisposition = ControlWakeScheduleInvocationDisposition.None
        acceptedFuture = null
        acceptedOuterWrapper = null
        schedulingFailure = null
        schedulingThrowableDisposition = ControlWakeThrowableDisposition.None
        schedulerRejectionReceipt = ControlSchedulerRejectionReceipt.None
        schedulerRuntimeIdentity = null
        schedulerTerminationReceipt = null
        fireDisposition = ControlWakeFireDisposition.Empty
        firedAtNanos = NO_TIME
        bodyDisposition = ControlWakeBodyDisposition.Empty
        bodyFailure = null
        bodyThrowableDisposition = ControlWakeThrowableDisposition.None
        cancellationDisposition = ControlWakeCancellationDisposition.None
        cancelInvocationDisposition = ControlWakeInvocationDisposition.None
        cancellationReturned = null
        cancellationFailure = null
        cancellationThrowableDisposition = ControlWakeThrowableDisposition.None
        suppressionDisposition = ControlWakeSuppressionDisposition.NotAttempted
        outerRemovalDisposition = ControlWakeOuterRemovalDisposition.NotAttempted
        removeInvocationDisposition = ControlWakeInvocationDisposition.None
        outerRemovalReturned = null
        outerRemovalFailure = null
        outerRemovalThrowableDisposition = ControlWakeThrowableDisposition.None
        outerRemovalFailureDisposition = ControlWakeRemovalFailureDisposition.None
        return ControlWakeSuccessorResult.Requested(
            ControlWakeRequestedAction(
                wakeLink = this,
                generation = nextGeneration,
                identity = nextIdentity,
                dueNanos = nextDueNanos,
            ),
        )
    }

    internal fun applySchedulerTermination(
        expectedGeneration: Long,
        receipt: ControlSchedulerTerminationEvidence,
    ): ControlWakeTerminationApplicationOutcome = parentGate.withLock {
        val action = scheduleActionFor(expectedGeneration)
        if (action.generation != expectedGeneration ||
            action.taskRecord.disposition(expectedGeneration) == ControlWakePhysicalDisposition.Stale
        ) {
            return@withLock ControlWakeTerminationApplicationOutcome.GenerationMismatch
        }
        if (action.runtimeIdentity !== receipt.runtimeIdentity) {
            return@withLock ControlWakeTerminationApplicationOutcome.IdentityMismatch
        }
        if (!receipt.isReleasePublished()) return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
        val accepted = action.returnPublicationOutcome == ControlWakeScheduleReturnPublicationOutcome.Accepted ||
                action.returnPublicationOutcome == ControlWakeScheduleReturnPublicationOutcome.Detached
        val acceptanceAmbiguous = action.invocationFailure != null &&
                action.invocationRejectionReceipt == ControlSchedulerRejectionReceipt.None
        if (!accepted && !acceptanceAmbiguous) {
            return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
        }
        if (action.terminationReceipt != null) {
            return@withLock if (action.terminationReceipt === receipt) {
                ControlWakeTerminationApplicationOutcome.Applied
            } else {
                ControlWakeTerminationApplicationOutcome.IdentityMismatch
            }
        }
        if (expectedGeneration != generation) {
            if (!action.taskRecord.applySchedulerTermination(expectedGeneration, receipt)) {
                return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
            }
            action.recordTerminationReceiptLocked(receipt)
            return@withLock ControlWakeTerminationApplicationOutcome.Applied
        }
        if (schedulerRuntimeIdentity !== receipt.runtimeIdentity) {
            return@withLock ControlWakeTerminationApplicationOutcome.IdentityMismatch
        }
        if (callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_SUPPRESSED)) {
            if (!action.taskRecord.applySchedulerTermination(generation, receipt)) {
                return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
            }
            action.recordTerminationReceiptLocked(receipt)
            schedulerTerminationReceipt = receipt
            return@withLock ControlWakeTerminationApplicationOutcome.Applied
        }
        val expectedQueued = controlWakeState(generation, CONTROL_WAKE_CALLBACK_QUEUED)
        val terminationSettled = controlWakeState(generation, CONTROL_WAKE_CALLBACK_TERMINATION_SETTLED)
        if (!callbackState.compareAndSet(expectedQueued, terminationSettled)) {
            return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
        }
        if (!currentTaskRecord.applySchedulerTermination(generation, receipt)) {
            check(callbackState.compareAndSet(terminationSettled, expectedQueued))
            return@withLock ControlWakeTerminationApplicationOutcome.NotEligible
        }
        action.recordTerminationReceiptLocked(receipt)
        schedulerTerminationReceipt = receipt
        ControlWakeTerminationApplicationOutcome.Applied
    }

    internal fun isEngineOperationallySettledLocked(): Boolean {
        check(parentGate.isHeldByCurrentThread)
        return when (submissionDisposition) {
            ControlWakeSubmissionDisposition.None -> true
            ControlWakeSubmissionDisposition.Rejected ->
                schedulerRejectionReceipt != ControlSchedulerRejectionReceipt.None &&
                        currentTaskRecord.disposition(generation) == ControlWakePhysicalDisposition.NotSubmitted

            ControlWakeSubmissionDisposition.NotInvoked ->
                scheduleInvocationDisposition == ControlWakeScheduleInvocationDisposition.NotInvoked &&
                        currentTaskRecord.disposition(generation) == ControlWakePhysicalDisposition.NotSubmitted
            ControlWakeSubmissionDisposition.PoisonFenced ->
                scheduleInvocationDisposition == ControlWakeScheduleInvocationDisposition.NotInvoked &&
                        currentTaskRecord.disposition(generation) == ControlWakePhysicalDisposition.NotSubmitted
            ControlWakeSubmissionDisposition.Accepted -> isRunningOperationallySettledLocked() ||
                    isSuppressedOperationallySettledLocked() ||
                    isSchedulerTerminationSettledLocked()

            ControlWakeSubmissionDisposition.Submitting -> schedulingFailure != null &&
                    (isRunningOperationallySettledLocked() || isSchedulerTerminationSettledLocked())

            ControlWakeSubmissionDisposition.Requested -> false
        }
    }

    internal fun isPhysicalWrapperSettledLocked(): Boolean {
        check(parentGate.isHeldByCurrentThread)
        return taskRecords[0].isPhysicallySettled() && taskRecords[1].isPhysicallySettled()
    }

    internal fun currentPhysicalDispositionLocked(): ControlWakePhysicalDisposition {
        check(parentGate.isHeldByCurrentThread)
        return currentTaskRecord.disposition(generation)
    }

    internal fun physicalGenerationAtLocked(slotIndex: Int): Long {
        check(parentGate.isHeldByCurrentThread)
        return taskRecords[slotIndex].generation()
    }

    internal fun physicalDispositionAtLocked(slotIndex: Int): ControlWakePhysicalDisposition {
        check(parentGate.isHeldByCurrentThread)
        return taskRecords[slotIndex].currentDisposition()
    }

    private fun matchesSubmittingActionLocked(action: ControlWakeScheduleAction): Boolean =
        parentGate.isHeldByCurrentThread &&
                action.generation == generation &&
                action.taskRecord === currentTaskRecord &&
                submissionDisposition == ControlWakeSubmissionDisposition.Submitting

    private fun matchesCancellingActionLocked(action: ControlWakeCancellationAction): Boolean =
        parentGate.isHeldByCurrentThread &&
                action.generation == generation &&
                cancellationDisposition == ControlWakeCancellationDisposition.Cancelling &&
                action.future === acceptedFuture &&
                action.outerWrapper === acceptedOuterWrapper &&
                action.taskRecord === currentTaskRecord

    private fun validateRemovalPublicationLocked(
        directFatalCancellation: Boolean,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ) {
        check(parentGate.isHeldByCurrentThread)
        when (removalDisposition) {
            ControlWakeOuterRemovalDisposition.NotAttempted -> require(
                directFatalCancellation &&
                        removeInvocationDisposition == ControlWakeInvocationDisposition.None &&
                        removalReturned == null &&
                        removalFailure == null,
            )

            ControlWakeOuterRemovalDisposition.Returned -> require(
                !directFatalCancellation &&
                        removeInvocationDisposition == ControlWakeInvocationDisposition.Admitted &&
                        removalReturned != null &&
                        removalFailure == null,
            )

            ControlWakeOuterRemovalDisposition.Thrown -> require(
                !directFatalCancellation &&
                        removeInvocationDisposition == ControlWakeInvocationDisposition.Admitted &&
                        removalReturned == null &&
                        removalFailure != null,
            )

            ControlWakeOuterRemovalDisposition.PoisonFenced -> require(
                !directFatalCancellation &&
                        removeInvocationDisposition == ControlWakeInvocationDisposition.NotInvoked &&
                        removalReturned == null &&
                        removalFailure == null,
            )
        }
    }

    private fun validateDetachedRemovalPublicationLocked(
        action: ControlWakeScheduleAction,
        directFatalCancellation: Boolean,
        removalDisposition: ControlWakeOuterRemovalDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ) {
        check(parentGate.isHeldByCurrentThread)
        when (removalDisposition) {
            ControlWakeOuterRemovalDisposition.NotAttempted -> require(
                directFatalCancellation &&
                        action.detachedRemoveInvocationDisposition == ControlWakeInvocationDisposition.None &&
                        removalReturned == null &&
                        removalFailure == null,
            )

            ControlWakeOuterRemovalDisposition.Returned -> require(
                !directFatalCancellation &&
                        action.detachedRemoveInvocationDisposition == ControlWakeInvocationDisposition.Admitted &&
                        removalReturned != null &&
                        removalFailure == null,
            )

            ControlWakeOuterRemovalDisposition.Thrown -> require(
                !directFatalCancellation &&
                        action.detachedRemoveInvocationDisposition == ControlWakeInvocationDisposition.Admitted &&
                        removalReturned == null &&
                        removalFailure != null,
            )

            ControlWakeOuterRemovalDisposition.PoisonFenced -> require(
                !directFatalCancellation &&
                        action.detachedRemoveInvocationDisposition == ControlWakeInvocationDisposition.NotInvoked &&
                        removalReturned == null &&
                        removalFailure == null,
            )
        }
    }

    internal fun trySuppress(actionGeneration: Long): Boolean =
        callbackState.compareAndSet(
            controlWakeState(actionGeneration, CONTROL_WAKE_CALLBACK_QUEUED),
            controlWakeState(actionGeneration, CONTROL_WAKE_CALLBACK_SUPPRESSED),
        )

    internal fun runGeneration(callbackGeneration: Long) {
        if (!callbackState.compareAndSet(
                controlWakeState(callbackGeneration, CONTROL_WAKE_CALLBACK_QUEUED),
                controlWakeState(callbackGeneration, CONTROL_WAKE_CALLBACK_RUNNING),
            )
        ) {
            return
        }

        val admitted = parentGate.withLock {
            if (generation != callbackGeneration ||
                fireDisposition != ControlWakeFireDisposition.Empty ||
                callbackState.get() != controlWakeState(callbackGeneration, CONTROL_WAKE_CALLBACK_RUNNING)
            ) {
                return@withLock false
            }
            firedAtNanos = if (sampleClockOnFire) clock.nowNanos() else NO_TIME
            fireDisposition = ControlWakeFireDisposition.Fired
            bodyDisposition = ControlWakeBodyDisposition.Running
            true
        }
        if (!admitted) return

        try {
            signal.signal()
            body?.run(callbackGeneration)
            publishBodyReturn(callbackGeneration, null)
        } catch (raw: Throwable) {
            publishBodyReturn(callbackGeneration, raw)
            throw raw
        }
    }

    private fun publishBodyReturn(callbackGeneration: Long, failure: Throwable?): Boolean {
        val published = parentGate.withLock {
            if (generation != callbackGeneration ||
                fireDisposition != ControlWakeFireDisposition.Fired ||
                bodyDisposition != ControlWakeBodyDisposition.Running
            ) {
                return@withLock false
            }
            bodyFailure = failure
            bodyThrowableDisposition = throwableDisposition(failure)
            bodyDisposition = if (failure == null) {
                ControlWakeBodyDisposition.Returned
            } else {
                ControlWakeBodyDisposition.Thrown
            }
            true
        }
        if (published && failure == null) signal.signal()
        return published
    }

    private fun isRunningOperationallySettledLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_RUNNING) &&
                fireDisposition == ControlWakeFireDisposition.Fired &&
                (bodyDisposition == ControlWakeBodyDisposition.Returned ||
                        bodyDisposition == ControlWakeBodyDisposition.Thrown)

    private fun isRunningSuccessorEligibleLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_RUNNING) &&
                fireDisposition == ControlWakeFireDisposition.Fired &&
                bodyDisposition == ControlWakeBodyDisposition.Returned &&
                bodyFailure == null &&
                bodyThrowableDisposition == ControlWakeThrowableDisposition.None

    private fun isSuppressedOperationallySettledLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_SUPPRESSED) &&
                cancellationDisposition == ControlWakeCancellationDisposition.Published &&
                cancellationReturned == true &&
                suppressionDisposition == ControlWakeSuppressionDisposition.Succeeded &&
                outerRemovalDisposition != ControlWakeOuterRemovalDisposition.NotAttempted

    private fun isSchedulerTerminationSettledLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_TERMINATION_SETTLED) &&
                schedulerTerminationReceipt != null &&
                schedulerRuntimeIdentity === schedulerTerminationReceipt?.runtimeIdentity

    private fun isSuppressedSuccessorEligibleLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_SUPPRESSED) &&
                cancellationDisposition == ControlWakeCancellationDisposition.Published &&
                cancellationReturned == true &&
                cancellationFailure == null &&
                cancellationThrowableDisposition == ControlWakeThrowableDisposition.None &&
                suppressionDisposition == ControlWakeSuppressionDisposition.Succeeded &&
                (outerRemovalDisposition == ControlWakeOuterRemovalDisposition.Returned &&
                        outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.None ||
                        outerRemovalDisposition == ControlWakeOuterRemovalDisposition.Thrown &&
                        outerRemovalFailureDisposition == ControlWakeRemovalFailureDisposition.OrdinaryException)

    private fun isSuccessorOperationallyEligibleLocked(): Boolean =
        isRunningSuccessorEligibleLocked() || isSuppressedSuccessorEligibleLocked()

    private fun taskRecordFor(generation: Long): ControlWakeTaskRecord =
        taskRecords[(generation and 1L).toInt()]

    private fun scheduleActionFor(generation: Long): ControlWakeScheduleAction =
        scheduleActions[(generation and 1L).toInt()]

    private fun throwableDisposition(raw: Throwable?): ControlWakeThrowableDisposition = when (raw) {
        null -> ControlWakeThrowableDisposition.None
        is Exception -> ControlWakeThrowableDisposition.NonfatalException
        else -> ControlWakeThrowableDisposition.FatalThrowable
    }

    private fun removalFailureDisposition(raw: Throwable?): ControlWakeRemovalFailureDisposition = when {
        raw == null -> ControlWakeRemovalFailureDisposition.None
        FatalThrowablePolicy.isDirectFatal(raw) -> ControlWakeRemovalFailureDisposition.DirectFatal
        raw is Exception -> ControlWakeRemovalFailureDisposition.OrdinaryException
        else -> ControlWakeRemovalFailureDisposition.OtherThrowable
    }

    internal companion object {
        internal fun createFiniteDeadline(
            initialGeneration: Long,
            initialIdentity: ControlWakeIdentity,
            parentGate: ReentrantLock,
            clock: EngineClock,
            signal: SettlementSignal,
            earlySuccessorHandle: FiniteDeadlineEarlySuccessorHandle,
            body: ControlWakeBody?,
            sampleClockOnFire: Boolean,
        ): ControlWakeLink {
            val link = ControlWakeLink(
                initialGeneration = initialGeneration,
                initialIdentity = initialIdentity,
                parentGate = parentGate,
                clock = clock,
                signal = signal,
                body = body,
                sampleClockOnFire = sampleClockOnFire,
            )
            check(link.finiteDeadlineEarlySuccessor.compareAndSet(null, earlySuccessorHandle))
            return link
        }

        private const val NO_TIME: Long = Long.MIN_VALUE
    }
}

internal class DeadlineOccurrence internal constructor(
    internal val identity: Long,
    internal val boundOccurrenceIdentity: Long,
    internal val durationNanos: Long,
    initialWakeGeneration: Long,
    internal val timeoutCause: Throwable,
    private val settlementGate: ReentrantLock,
    clock: EngineClock,
    signal: SettlementSignal,
) {
    private val wakeIdentity: ControlWakeIdentity = ControlWakeIdentity(ControlWakeKind.Deadline, identity)
    private val earlySuccessorHandle: FiniteDeadlineEarlySuccessorHandle =
        FiniteDeadlineEarlySuccessorHandle(this)

    internal val controlWakeLink: ControlWakeLink = ControlWakeLink.createFiniteDeadline(
        initialGeneration = initialWakeGeneration,
        initialIdentity = wakeIdentity,
        parentGate = settlementGate,
        clock = clock,
        signal = signal,
        earlySuccessorHandle = earlySuccessorHandle,
        body = null,
        sampleClockOnFire = true,
    )

    internal var disposition: DeadlineDisposition = DeadlineDisposition.Unarmed
        private set

    internal var startNanos: Long = NO_TIME
        private set

    internal var deadlineNanos: Long = NO_TIME
        private set

    internal fun armLocked(startNanos: Long): DeadlineArmResult {
        check(settlementGate.isHeldByCurrentThread)
        if (disposition != DeadlineDisposition.Unarmed) return DeadlineArmResult.AlreadySettled
        if (durationNanos <= 0L || startNanos < 0L || startNanos > Long.MAX_VALUE - durationNanos) {
            disposition = DeadlineDisposition.Retired
            controlWakeLink.requestCancellationLocked()
            return DeadlineArmResult.InvalidClockOrOverflow
        }
        val deadline = Math.addExact(startNanos, durationNanos)
        this.startNanos = startNanos
        deadlineNanos = deadline
        disposition = DeadlineDisposition.Armed
        check(controlWakeLink.requestLocked(deadline))
        return DeadlineArmResult.Armed
    }

    internal fun expireLocked() {
        check(settlementGate.isHeldByCurrentThread)
        if (disposition != DeadlineDisposition.Armed) return
        disposition = DeadlineDisposition.Expired
        controlWakeLink.requestCancellationLocked()
    }

    internal fun retireLocked() {
        check(settlementGate.isHeldByCurrentThread)
        if (disposition == DeadlineDisposition.Retired) return
        disposition = DeadlineDisposition.Retired
        controlWakeLink.requestCancellationLocked()
    }

    internal fun prepareEarlyWakeSuccessorLocked(
        controlPoison: ControlPoisonAuthority,
    ): ControlWakeSuccessorResult {
        check(settlementGate.isHeldByCurrentThread)
        if (disposition != DeadlineDisposition.Armed ||
            controlWakeLink.fireDisposition != ControlWakeFireDisposition.Fired ||
            controlWakeLink.firedAtNanos !in 0L..<deadlineNanos
        ) {
            return ControlWakeSuccessorResult.NotEligible
        }
        return controlWakeLink.prepareSuccessorLocked(
            nextIdentity = wakeIdentity,
            nextDueNanos = deadlineNanos,
            controlPoison = controlPoison,
        )
    }

    internal fun prepareEarlyWakeSuccessor(
        controlPoison: ControlPoisonAuthority,
    ): ControlWakeSuccessorResult {
        check(!settlementGate.isHeldByCurrentThread)
        return settlementGate.withLock { prepareEarlyWakeSuccessorLocked(controlPoison) }
    }

    private companion object {
        private const val NO_TIME: Long = Long.MIN_VALUE
    }
}
