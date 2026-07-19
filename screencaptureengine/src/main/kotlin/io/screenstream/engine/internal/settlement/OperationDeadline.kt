package io.screenstream.engine.internal.settlement

import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val CONTROL_WAKE_STATE_BITS: Int = 3
private const val CONTROL_WAKE_CALLBACK_QUEUED: Long = 0L
private const val CONTROL_WAKE_CALLBACK_RUNNING: Long = 1L
private const val CONTROL_WAKE_CALLBACK_SUPPRESSED: Long = 2L
private const val CONTROL_WAKE_PHYSICAL_PENDING: Long = 0L
private const val CONTROL_WAKE_PHYSICAL_ON_STACK: Long = 1L
private const val CONTROL_WAKE_PHYSICAL_RETURNED: Long = 2L
private const val CONTROL_WAKE_PHYSICAL_REMOVED: Long = 3L
private const val CONTROL_WAKE_PHYSICAL_TERMINATED: Long = 4L
private const val CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED: Long = 5L
private const val CONTROL_WAKE_PHYSICAL_PREPARED: Long = 6L
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

internal interface ControlScheduledTaskRecord {
    fun bindDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean

    fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean

    fun markBoundOuterReturned(outerWrapper: Runnable): Boolean

    fun claimAfterExecuteFatal(outerWrapper: Runnable, delegate: Runnable): Throwable?
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
}

internal enum class ControlWakeSchedulerTerminationDisposition {
    Empty,
    Returned,
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
    Terminated,
    NotSubmitted,
    Stale,
}

internal enum class ControlWakeSuccessorResult {
    Requested,
    NotEligible,
    IdentityExhausted,
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
    private val directFatal = AtomicReference<Throwable?>(null)
    private val afterExecuteBridge = AtomicInteger(AFTER_EXECUTE_PENDING)
    private val exactOuterWrapper = AtomicReference<Runnable?>(null)
    private val exactDelegate = AtomicReference<Runnable?>(null)

    @Volatile
    private var scheduledGeneration: Long = 0L

    internal val runner: ControlScheduledRunner = object : ControlScheduledRunner {
        override val scheduledTaskRecord: ControlScheduledTaskRecord
            get() = this@ControlWakeTaskRecord

        override fun run() {
            val generation = scheduledGeneration
            try {
                owner.runGeneration(generation)
            } catch (raw: Throwable) {
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
                priorPhase != CONTROL_WAKE_PHYSICAL_TERMINATED &&
                priorPhase != CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED
            ) {
                return false
            }
        }
        directFatal.set(null)
        afterExecuteBridge.set(AFTER_EXECUTE_PENDING)
        exactOuterWrapper.set(null)
        exactDelegate.set(null)
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

    override fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean {
        if (exactOuterWrapper.get() !== outerWrapper) return false
        return markOuterOnStack(scheduledGeneration)
    }

    override fun markBoundOuterReturned(outerWrapper: Runnable): Boolean {
        if (exactOuterWrapper.get() !== outerWrapper) return false
        return markOuterReturned(scheduledGeneration)
    }

    override fun claimAfterExecuteFatal(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): Throwable? {
        if (exactOuterWrapper.get() !== outerWrapper || exactDelegate.get() !== delegate) return null
        if (!afterExecuteBridge.compareAndSet(AFTER_EXECUTE_PENDING, AFTER_EXECUTE_APPLIED)) return null
        return directFatal.get()
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

    internal fun markSchedulerTerminatedIfLive(): Boolean {
        while (true) {
            val state = physicalState.get()
            if (state == 0L) return false
            val phase = state and CONTROL_WAKE_PHASE_MASK
            if (phase != CONTROL_WAKE_PHYSICAL_PENDING && phase != CONTROL_WAKE_PHYSICAL_ON_STACK) return false
            if (physicalState.compareAndSet(
                    state,
                    controlWakeState(
                        state ushr CONTROL_WAKE_STATE_BITS,
                        CONTROL_WAKE_PHYSICAL_TERMINATED,
                    ),
                )
            ) {
                return true
            }
        }
    }

    internal fun generation(): Long = physicalState.get() ushr CONTROL_WAKE_STATE_BITS

    internal fun currentDisposition(): ControlWakePhysicalDisposition = physicalDisposition(physicalState.get())

    internal fun disposition(generation: Long): ControlWakePhysicalDisposition {
        val state = physicalState.get()
        if (state == 0L) return ControlWakePhysicalDisposition.Unused
        if (state ushr CONTROL_WAKE_STATE_BITS != generation) return ControlWakePhysicalDisposition.Stale
        return physicalDisposition(state)
    }

    internal fun isPhysicallySettled(): Boolean = when (currentDisposition()) {
        ControlWakePhysicalDisposition.Unused,
        ControlWakePhysicalDisposition.Prepared,
        ControlWakePhysicalDisposition.Returned,
        ControlWakePhysicalDisposition.Removed,
        ControlWakePhysicalDisposition.Terminated,
        ControlWakePhysicalDisposition.NotSubmitted,
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
            CONTROL_WAKE_PHYSICAL_TERMINATED -> ControlWakePhysicalDisposition.Terminated
            CONTROL_WAKE_PHYSICAL_NOT_SUBMITTED -> ControlWakePhysicalDisposition.NotSubmitted
            else -> ControlWakePhysicalDisposition.Stale
        }
    }

    internal companion object {
        private const val CONTROL_WAKE_PHASE_MASK: Long = (1L shl CONTROL_WAKE_STATE_BITS) - 1L
        private const val AFTER_EXECUTE_PENDING: Int = 0
        private const val AFTER_EXECUTE_APPLIED: Int = 1

        internal fun create(owner: ControlWakeLink): ControlWakeTaskRecord = ControlWakeTaskRecord(owner)
    }
}

internal class ControlWakeScheduleAction internal constructor(
    internal val taskRecord: ControlWakeTaskRecord,
) {
    internal var generation: Long = 0L
        private set

    internal var dueNanos: Long = 0L
        private set

    internal val runner: Runnable
        get() = taskRecord.runner

    internal fun bindLocked(generation: Long, dueNanos: Long) {
        this.generation = generation
        this.dueNanos = dueNanos
    }
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

    internal var acceptedFuture: Future<*>? = null
        private set

    internal var acceptedOuterWrapper: Runnable? = null
        private set

    internal var schedulingFailure: Throwable? = null
        private set

    internal var schedulingThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
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

    internal var outerRemovalReturned: Boolean? = null
        private set

    internal var outerRemovalFailure: Throwable? = null
        private set

    internal var outerRemovalThrowableDisposition: ControlWakeThrowableDisposition = ControlWakeThrowableDisposition.None
        private set

    internal var schedulerTerminationDisposition: ControlWakeSchedulerTerminationDisposition =
        ControlWakeSchedulerTerminationDisposition.Empty
        private set

    private val callbackState: AtomicLong = AtomicLong(
        controlWakeState(initialGeneration, CONTROL_WAKE_CALLBACK_QUEUED),
    )

    private val taskRecords: Array<ControlWakeTaskRecord> = arrayOf(
        ControlWakeTaskRecord.create(this),
        ControlWakeTaskRecord.create(this),
    )
    private val scheduleActions: Array<ControlWakeScheduleAction> = arrayOf(
        ControlWakeScheduleAction(taskRecords[0]),
        ControlWakeScheduleAction(taskRecords[1]),
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

    internal fun publishSchedulingAccepted(
        action: ControlWakeScheduleAction,
        future: Future<*>,
        outerWrapper: Runnable,
    ): Boolean {
        val published = parentGate.withLock {
            if (!matchesSubmittingActionLocked(action)) return@withLock false
            acceptedFuture = future
            acceptedOuterWrapper = outerWrapper
            submissionDisposition = ControlWakeSubmissionDisposition.Accepted
            true
        }
        if (published) signal.signal()
        return published
    }

    internal fun publishSchedulingFailure(
        action: ControlWakeScheduleAction,
        failure: Throwable,
    ): Boolean {
        val published = parentGate.withLock {
            if (!matchesSubmittingActionLocked(action)) return@withLock false
            schedulingFailure = failure
            schedulingThrowableDisposition = throwableDisposition(failure)
            val definitelyRejected = failure is RejectedExecutionException &&
                    callbackState.compareAndSet(
                        controlWakeState(generation, CONTROL_WAKE_CALLBACK_QUEUED),
                        controlWakeState(generation, CONTROL_WAKE_CALLBACK_SUPPRESSED),
                    )
            if (definitelyRejected) {
                submissionDisposition = ControlWakeSubmissionDisposition.Rejected
                cancellationDisposition = ControlWakeCancellationDisposition.NotNeeded
                currentTaskRecord.markNotSubmitted(generation)
            }
            true
        }
        if (published) signal.signal()
        return published
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

    internal fun publishCancellation(
        action: ControlWakeCancellationAction,
        cancelReturned: Boolean?,
        cancelFailure: Throwable?,
        suppressionDisposition: ControlWakeSuppressionDisposition,
        removalReturned: Boolean?,
        removalFailure: Throwable?,
    ): Boolean {
        require((cancelReturned == null) != (cancelFailure == null))
        require((removalReturned == null) != (removalFailure == null))
        require(
            cancelReturned == true && suppressionDisposition != ControlWakeSuppressionDisposition.NotAttempted ||
                    cancelReturned != true && suppressionDisposition == ControlWakeSuppressionDisposition.NotAttempted,
        )
        val published = parentGate.withLock {
            if (action.generation != generation ||
                cancellationDisposition != ControlWakeCancellationDisposition.Cancelling ||
                action.future !== acceptedFuture ||
                action.outerWrapper !== acceptedOuterWrapper ||
                action.taskRecord !== currentTaskRecord
            ) {
                return@withLock false
            }
            cancellationReturned = cancelReturned
            cancellationFailure = cancelFailure
            cancellationThrowableDisposition = throwableDisposition(cancelFailure)
            this.suppressionDisposition = suppressionDisposition
            outerRemovalReturned = removalReturned
            outerRemovalFailure = removalFailure
            outerRemovalDisposition = if (removalFailure == null) {
                ControlWakeOuterRemovalDisposition.Returned
            } else {
                ControlWakeOuterRemovalDisposition.Thrown
            }
            outerRemovalThrowableDisposition = throwableDisposition(removalFailure)
            if (removalReturned == true) currentTaskRecord.markOuterRemoved(generation)
            cancellationDisposition = ControlWakeCancellationDisposition.Published
            true
        }
        if (published) signal.signal()
        return published
    }

    internal fun prepareSuccessorLocked(
        nextIdentity: ControlWakeIdentity,
        nextDueNanos: Long,
    ): ControlWakeSuccessorResult {
        check(parentGate.isHeldByCurrentThread)
        if (nextDueNanos < 0L ||
            schedulerTerminationDisposition != ControlWakeSchedulerTerminationDisposition.Empty ||
            !hasAcceptedOrAmbiguousSubmissionLocked() ||
            !isSuccessorOperationallyEligibleLocked()
        ) {
            return ControlWakeSuccessorResult.NotEligible
        }
        if (generation >= MAX_CONTROL_WAKE_GENERATION) return ControlWakeSuccessorResult.IdentityExhausted

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
        acceptedFuture = null
        acceptedOuterWrapper = null
        schedulingFailure = null
        schedulingThrowableDisposition = ControlWakeThrowableDisposition.None
        fireDisposition = ControlWakeFireDisposition.Empty
        firedAtNanos = NO_TIME
        bodyDisposition = ControlWakeBodyDisposition.Empty
        bodyFailure = null
        bodyThrowableDisposition = ControlWakeThrowableDisposition.None
        cancellationDisposition = ControlWakeCancellationDisposition.None
        cancellationReturned = null
        cancellationFailure = null
        cancellationThrowableDisposition = ControlWakeThrowableDisposition.None
        suppressionDisposition = ControlWakeSuppressionDisposition.NotAttempted
        outerRemovalDisposition = ControlWakeOuterRemovalDisposition.NotAttempted
        outerRemovalReturned = null
        outerRemovalFailure = null
        outerRemovalThrowableDisposition = ControlWakeThrowableDisposition.None
        return ControlWakeSuccessorResult.Requested
    }

    internal fun publishSchedulerTermination(): Boolean {
        val published = parentGate.withLock {
            if (schedulerTerminationDisposition == ControlWakeSchedulerTerminationDisposition.Returned) {
                return@withLock false
            }
            schedulerTerminationDisposition = ControlWakeSchedulerTerminationDisposition.Returned
            taskRecords[0].markSchedulerTerminatedIfLive()
            taskRecords[1].markSchedulerTerminatedIfLive()
            true
        }
        if (published) signal.signal()
        return published
    }

    internal fun isEngineOperationallySettledLocked(): Boolean {
        check(parentGate.isHeldByCurrentThread)
        if (schedulerTerminationDisposition == ControlWakeSchedulerTerminationDisposition.Returned &&
            hasAcceptedOrAmbiguousSubmissionLocked()
        ) {
            return true
        }
        return when (submissionDisposition) {
            ControlWakeSubmissionDisposition.None -> true
            ControlWakeSubmissionDisposition.Rejected -> true
            ControlWakeSubmissionDisposition.Accepted -> isRunningOperationallySettledLocked() ||
                    isSuppressedOperationallySettledLocked()

            ControlWakeSubmissionDisposition.Submitting -> schedulingFailure != null &&
                    isRunningOperationallySettledLocked()

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
        if (published) signal.signal()
        return published
    }

    private fun isRunningOperationallySettledLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_RUNNING) &&
                fireDisposition == ControlWakeFireDisposition.Fired &&
                (bodyDisposition == ControlWakeBodyDisposition.Returned ||
                        bodyDisposition == ControlWakeBodyDisposition.Thrown)

    private fun isSuppressedOperationallySettledLocked(): Boolean =
        callbackState.get() == controlWakeState(generation, CONTROL_WAKE_CALLBACK_SUPPRESSED) &&
                cancellationDisposition == ControlWakeCancellationDisposition.Published &&
                cancellationReturned == true &&
                suppressionDisposition == ControlWakeSuppressionDisposition.Succeeded &&
                outerRemovalDisposition != ControlWakeOuterRemovalDisposition.NotAttempted

    private fun isSuccessorOperationallyEligibleLocked(): Boolean =
        isRunningOperationallySettledLocked() || isSuppressedOperationallySettledLocked()

    private fun hasAcceptedOrAmbiguousSubmissionLocked(): Boolean =
        submissionDisposition == ControlWakeSubmissionDisposition.Accepted ||
                submissionDisposition == ControlWakeSubmissionDisposition.Submitting && schedulingFailure != null

    private fun taskRecordFor(generation: Long): ControlWakeTaskRecord =
        taskRecords[(generation and 1L).toInt()]

    private fun scheduleActionFor(generation: Long): ControlWakeScheduleAction =
        scheduleActions[(generation and 1L).toInt()]

    private fun throwableDisposition(raw: Throwable?): ControlWakeThrowableDisposition = when (raw) {
        null -> ControlWakeThrowableDisposition.None
        is Exception -> ControlWakeThrowableDisposition.NonfatalException
        else -> ControlWakeThrowableDisposition.FatalThrowable
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
    internal val timeoutCause: Throwable,
    private val settlementGate: ReentrantLock,
    clock: EngineClock,
    signal: SettlementSignal,
) {
    private val wakeIdentity: ControlWakeIdentity = ControlWakeIdentity(ControlWakeKind.Deadline, identity)

    internal val controlWakeLink: ControlWakeLink = ControlWakeLink(
        initialGeneration = initialWakeGeneration,
        initialIdentity = wakeIdentity,
        parentGate = settlementGate,
        clock = clock,
        signal = signal,
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

    internal fun prepareEarlyWakeSuccessorLocked(): ControlWakeSuccessorResult {
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
        )
    }

    private companion object {
        private const val NO_TIME: Long = Long.MIN_VALUE
    }
}
