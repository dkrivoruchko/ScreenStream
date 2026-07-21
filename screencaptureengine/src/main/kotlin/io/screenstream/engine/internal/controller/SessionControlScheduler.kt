package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.settlement.ControlAfterExecutePortDisposition
import io.screenstream.engine.internal.settlement.ControlPoisonAuthority
import io.screenstream.engine.internal.settlement.ControlPoisonClaim
import io.screenstream.engine.internal.settlement.ControlPoisonClaimOutcome
import io.screenstream.engine.internal.settlement.ControlSchedulerRejectionReceipt
import io.screenstream.engine.internal.settlement.ControlSchedulerRejectionReceiptPublicationOutcome
import io.screenstream.engine.internal.settlement.ControlSchedulerRuntimeIdentity
import io.screenstream.engine.internal.settlement.ControlSchedulerTerminationEvidence
import io.screenstream.engine.internal.settlement.ControlScheduledRunner
import io.screenstream.engine.internal.settlement.ControlScheduledTaskRecord
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal interface SessionControlSchedulerPort {
    fun onControlTaskOrdinaryEscaped(record: ControlScheduledTaskRecord, raw: Throwable)
    fun onControlTaskDirectFatal(record: ControlScheduledTaskRecord, raw: Throwable)
}

private object SessionControlRejectionHandler : RejectedExecutionHandler {
    override fun rejectedExecution(runnable: Runnable, @Suppress("UNUSED_PARAMETER") executor: ThreadPoolExecutor) {
        val typed = runnable as? SessionTypedScheduledTask<*>
        if (typed != null) {
            typed.record.publishExecutorRejected(typed, typed.delegateTask)
        }
        throw CONTROL_EXECUTOR_REJECTED
    }

    private val CONTROL_EXECUTOR_REJECTED: RejectedExecutionException =
        RejectedExecutionException("Control executor rejected an exact typed task")
}

internal class SessionControlTerminationReceipt internal constructor(
    override val runtimeIdentity: SessionControlRuntimeOwner,
    internal val exactRoot: SessionControlTerminationRoot,
) : ControlSchedulerTerminationEvidence {
    private val releaseThread = AtomicReference<Thread?>(null)

    internal val exactReleaseThread: Thread?
        get() = if (isReleasePublished()) releaseThread.get() else null

    internal fun bindReleaseThread(thread: Thread): Boolean = releaseThread.compareAndSet(null, thread)

    override fun isReleasePublished(): Boolean = exactRoot.observeReleasedReceipt() === this
}

internal class SessionControlTerminationRoot internal constructor(
    internal val runtimeOwner: SessionControlRuntimeOwner,
) {
    internal val exactReceipt: SessionControlTerminationReceipt = SessionControlTerminationReceipt(runtimeOwner, this)
    private val releasedReceipt = AtomicReference<SessionControlTerminationReceipt?>(null)

    internal fun publishReleaseInline(
        scheduler: SessionControlScheduler,
        receipt: SessionControlTerminationReceipt,
    ): Boolean {
        require(runtimeOwner.ownsScheduler(scheduler))
        require(receipt === exactReceipt && receipt.exactRoot === this && receipt.runtimeIdentity === runtimeOwner)
        check(receipt.bindReleaseThread(Thread.currentThread()))
        return releasedReceipt.compareAndSet(null, receipt)
    }

    internal fun observeReleasedReceipt(): SessionControlTerminationReceipt? = releasedReceipt.get()
}

internal enum class SessionControlRuntimeTransferOutcome {
    Transferred,
    NotEligible,
}

internal class SessionControlRuntimeOwner internal constructor() : ControlSchedulerRuntimeIdentity {
    internal val terminationRoot: SessionControlTerminationRoot = SessionControlTerminationRoot(this)
    internal val threadFactory: ThreadFactory = SessionControlThreadFactory(this)
    private val exactScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val state = AtomicInteger(OWNER_CANDIDATE)

    internal fun bindScheduler(scheduler: SessionControlScheduler): Boolean {
        if (!exactScheduler.compareAndSet(null, scheduler)) return false
        if (state.compareAndSet(OWNER_CANDIDATE, OWNER_BOUND)) return true
        exactScheduler.compareAndSet(scheduler, null)
        return false
    }

    internal fun ensureSchedulerBound(scheduler: SessionControlScheduler): Boolean =
        ownsScheduler(scheduler) || bindScheduler(scheduler)

    internal fun transferReadyScheduler(scheduler: SessionControlScheduler): SessionControlRuntimeTransferOutcome =
        if (ownsScheduler(scheduler) && state.compareAndSet(OWNER_BOUND, OWNER_ACCEPTED)) {
            SessionControlRuntimeTransferOutcome.Transferred
        } else {
            SessionControlRuntimeTransferOutcome.NotEligible
        }

    internal fun claimFinalShutdown(scheduler: SessionControlScheduler): Boolean =
        ownsScheduler(scheduler) && state.compareAndSet(OWNER_ACCEPTED, OWNER_FINAL_SHUTDOWN_CLAIMED)

    internal fun ownsScheduler(scheduler: SessionControlScheduler): Boolean = exactScheduler.get() === scheduler

    internal fun ownsThread(thread: Thread): Boolean =
        thread is SessionControlOwnedThread && thread.runtimeOwner === this

    private companion object {
        private const val OWNER_CANDIDATE = 0
        private const val OWNER_BOUND = 1
        private const val OWNER_ACCEPTED = 2
        private const val OWNER_FINAL_SHUTDOWN_CLAIMED = 3
    }
}

private class SessionControlThreadFactory(
    private val runtimeOwner: SessionControlRuntimeOwner,
) : ThreadFactory {
    override fun newThread(runnable: Runnable): Thread = SessionControlOwnedThread(runtimeOwner, runnable)
}

private class SessionControlOwnedThread(
    internal val runtimeOwner: SessionControlRuntimeOwner,
    runnable: Runnable,
) : Thread(runnable, "ScreenCaptureEngine-Control")

internal enum class SessionControlStartupDisposition {
    Pending,
    Starting,
    Ready,
    Failed,
}

internal enum class SessionControlConstructionDisposition {
    Pending,
    Returned,
    Thrown,
}

internal enum class SessionControlShutdownDisposition {
    NotAttempted,
    Invoking,
    Returned,
    Thrown,
}

internal enum class SessionControlStartupClaimOutcome {
    Claimed,
    NotEligible,
}

internal enum class SessionControlShutdownClaimOutcome {
    Claimed,
    NotEligible,
}

internal enum class SessionControlStartupFactPublicationOutcome {
    Published,
    NotEligible,
}

internal enum class SessionControlPrestartOutcome {
    Ready,
    CleanupRequired,
    NotEligible,
}

internal class SessionControlStartupRecord internal constructor() {
    private val construction = AtomicInteger(CONSTRUCTION_PENDING)
    private val constructionRaw = AtomicReference<Throwable?>(null)
    private val constructionDirectFatal = AtomicReference<Throwable?>(null)
    private val startup = AtomicInteger(STARTUP_PENDING)
    private val startupRaw = AtomicReference<Throwable?>(null)
    private val startupDirectFatal = AtomicReference<Throwable?>(null)
    private val shutdown = AtomicInteger(SHUTDOWN_NOT_ATTEMPTED)
    private val shutdownRaw = AtomicReference<Throwable?>(null)
    private val shutdownDirectFatal = AtomicReference<Throwable?>(null)

    internal val constructionDisposition: SessionControlConstructionDisposition
        get() = when (construction.get()) {
            CONSTRUCTION_RETURNED -> SessionControlConstructionDisposition.Returned
            CONSTRUCTION_THROWN -> SessionControlConstructionDisposition.Thrown
            else -> SessionControlConstructionDisposition.Pending
        }

    internal val constructionFailure: Throwable?
        get() = if (construction.get() == CONSTRUCTION_THROWN) constructionRaw.get() else null

    internal val constructionDirectFatalFailure: Throwable?
        get() = if (construction.get() == CONSTRUCTION_THROWN) constructionDirectFatal.get() else null

    internal val startupDisposition: SessionControlStartupDisposition
        get() = when (startup.get()) {
            STARTUP_PENDING -> SessionControlStartupDisposition.Pending
            STARTUP_READY -> SessionControlStartupDisposition.Ready
            STARTUP_FAILED -> SessionControlStartupDisposition.Failed
            else -> SessionControlStartupDisposition.Starting
        }

    internal val startupFailure: Throwable?
        get() = if (startup.get() == STARTUP_FAILED) startupRaw.get() else null

    internal val startupDirectFatalFailure: Throwable?
        get() = if (startup.get() == STARTUP_FAILED) startupDirectFatal.get() else null

    internal val shutdownDisposition: SessionControlShutdownDisposition
        get() = when (shutdown.get()) {
            SHUTDOWN_RETURNED -> SessionControlShutdownDisposition.Returned
            SHUTDOWN_THROWN -> SessionControlShutdownDisposition.Thrown
            SHUTDOWN_NOT_ATTEMPTED -> SessionControlShutdownDisposition.NotAttempted
            else -> SessionControlShutdownDisposition.Invoking
        }

    internal val shutdownFailure: Throwable?
        get() = if (shutdown.get() == SHUTDOWN_THROWN) shutdownRaw.get() else null

    internal val shutdownDirectFatalFailure: Throwable?
        get() = if (shutdown.get() == SHUTDOWN_THROWN) shutdownDirectFatal.get() else null

    internal fun publishConstructionReturned(): SessionControlStartupFactPublicationOutcome =
        publicationOutcome(construction.compareAndSet(CONSTRUCTION_PENDING, CONSTRUCTION_RETURNED))

    internal fun publishConstructionFailure(raw: Throwable): SessionControlStartupFactPublicationOutcome {
        if (!construction.compareAndSet(CONSTRUCTION_PENDING, CONSTRUCTION_FAILURE_PUBLISHING)) {
            return SessionControlStartupFactPublicationOutcome.NotEligible
        }
        constructionRaw.set(raw)
        if (FatalThrowablePolicy.isDirectFatal(raw)) constructionDirectFatal.compareAndSet(null, raw)
        construction.set(CONSTRUCTION_THROWN)
        return SessionControlStartupFactPublicationOutcome.Published
    }

    internal fun claimPrestart(): SessionControlStartupClaimOutcome =
        if (construction.get() == CONSTRUCTION_RETURNED &&
            startup.compareAndSet(STARTUP_PENDING, STARTUP_STARTING)
        ) {
            SessionControlStartupClaimOutcome.Claimed
        } else {
            SessionControlStartupClaimOutcome.NotEligible
        }

    internal fun publishReady(): SessionControlStartupFactPublicationOutcome =
        publicationOutcome(startup.compareAndSet(STARTUP_STARTING, STARTUP_READY))

    internal fun publishStartupFailure(raw: Throwable): SessionControlStartupFactPublicationOutcome {
        if (!startup.compareAndSet(STARTUP_STARTING, STARTUP_FAILURE_PUBLISHING)) {
            return SessionControlStartupFactPublicationOutcome.NotEligible
        }
        startupRaw.set(raw)
        if (FatalThrowablePolicy.isDirectFatal(raw)) startupDirectFatal.compareAndSet(null, raw)
        startup.set(STARTUP_FAILED)
        return SessionControlStartupFactPublicationOutcome.Published
    }

    internal fun claimShutdown(): SessionControlShutdownClaimOutcome =
        if ((startup.get() == STARTUP_FAILED || construction.get() == CONSTRUCTION_THROWN) &&
            shutdown.compareAndSet(SHUTDOWN_NOT_ATTEMPTED, SHUTDOWN_INVOKING)
        ) {
            SessionControlShutdownClaimOutcome.Claimed
        } else {
            SessionControlShutdownClaimOutcome.NotEligible
        }

    internal fun publishShutdownReturned(): SessionControlStartupFactPublicationOutcome =
        publicationOutcome(shutdown.compareAndSet(SHUTDOWN_INVOKING, SHUTDOWN_RETURNED))

    internal fun publishShutdownFailure(raw: Throwable): SessionControlStartupFactPublicationOutcome {
        if (!shutdown.compareAndSet(SHUTDOWN_INVOKING, SHUTDOWN_FAILURE_PUBLISHING)) {
            return SessionControlStartupFactPublicationOutcome.NotEligible
        }
        shutdownRaw.set(raw)
        if (FatalThrowablePolicy.isDirectFatal(raw)) shutdownDirectFatal.compareAndSet(null, raw)
        shutdown.set(SHUTDOWN_THROWN)
        return SessionControlStartupFactPublicationOutcome.Published
    }

    private fun publicationOutcome(published: Boolean): SessionControlStartupFactPublicationOutcome =
        if (published) {
            SessionControlStartupFactPublicationOutcome.Published
        } else {
            SessionControlStartupFactPublicationOutcome.NotEligible
        }

    private companion object {
        private const val CONSTRUCTION_PENDING = 0
        private const val CONSTRUCTION_RETURNED = 1
        private const val CONSTRUCTION_THROWN = 2
        private const val CONSTRUCTION_FAILURE_PUBLISHING = 3
        private const val STARTUP_PENDING = 0
        private const val STARTUP_STARTING = 1
        private const val STARTUP_READY = 2
        private const val STARTUP_FAILED = 3
        private const val STARTUP_FAILURE_PUBLISHING = 4
        private const val SHUTDOWN_NOT_ATTEMPTED = 0
        private const val SHUTDOWN_INVOKING = 1
        private const val SHUTDOWN_RETURNED = 2
        private const val SHUTDOWN_THROWN = 3
        private const val SHUTDOWN_FAILURE_PUBLISHING = 4
    }
}

internal enum class SessionControlDrainerInvocationClaimOutcome {
    Admitted,
    PoisonFenced,
    ClaimExhausted,
    NotEligible,
}

internal enum class SessionControlDrainerSubmissionFailurePublicationOutcome {
    Rejected,
    Ambiguous,
    NotEligible,
}

internal enum class SessionControlDrainerSubmissionAcceptancePublicationOutcome {
    Published,
    NotEligible,
}

internal enum class SessionControlDrainerPhysicalDisposition {
    Unused,
    Pending,
    OnStack,
    Returned,
    NotSubmitted,
}

internal enum class SessionControlDrainerSubmissionDisposition {
    None,
    Submitting,
    Admitted,
    Accepted,
    AcceptanceAmbiguous,
    PoisonFenced,
    ClaimExhausted,
    RejectionReceipted,
    Rejected,
}

internal class SessionControlDrainerTaskRecord internal constructor(
    private val controlPoison: ControlPoisonAuthority,
    private val delegate: Runnable,
) : ControlScheduledTaskRecord {
    private val generation = AtomicLong(0L)
    private val physical = AtomicInteger(PHYSICAL_UNUSED)
    private val submission = AtomicInteger(SUBMISSION_NONE)
    private val submissionFailureRaw = AtomicReference<Throwable?>(null)
    private val submissionDirectFatalRaw = AtomicReference<Throwable?>(null)
    private val taskReturn = AtomicInteger(TASK_EMPTY)
    private val directFatal = AtomicReference<Throwable?>(null)
    private val taskThrowable = AtomicReference<Throwable?>(null)
    private val bridge = AtomicInteger(BRIDGE_APPLIED)
    private val portInvocation = AtomicInteger(PORT_PENDING)
    private val portFailure = AtomicReference<Throwable?>(null)
    private val exactOuter = AtomicReference<Runnable?>(null)
    private val exactDelegate = AtomicReference<Runnable?>(null)
    private val rejectedDelegate = AtomicReference<Runnable?>(null)
    private val inertBeforeDelegate = AtomicInteger(INERT_OPEN)
    private val rejectionReceipt = AtomicInteger(REJECTION_NONE)
    private val submissionFence: Any = Any()

    internal val runner: ControlScheduledRunner = object : ControlScheduledRunner {
        override val scheduledTaskRecord: ControlScheduledTaskRecord
            get() = this@SessionControlDrainerTaskRecord

        override fun run() {
            if (inertBeforeDelegate.get() == INERT_MARKED) return
            try {
                delegate.run()
                taskReturn.compareAndSet(TASK_RUNNING, TASK_RETURNED)
            } catch (raw: Throwable) {
                taskThrowable.compareAndSet(null, raw)
                if (FatalThrowablePolicy.isDirectFatal(raw)) directFatal.compareAndSet(null, raw)
                taskReturn.compareAndSet(TASK_RUNNING, TASK_THROWN)
                throw raw
            }
        }
    }

    internal val submissionFailure: Throwable?
        get() = submissionFailureRaw.get()

    internal val submissionDirectFatal: Throwable?
        get() = submissionDirectFatalRaw.get()

    internal val schedulerRejectionReceipt: ControlSchedulerRejectionReceipt
        get() = rejectionReceipt(rejectionReceipt.get())

    internal val physicalDisposition: SessionControlDrainerPhysicalDisposition
        get() = physicalDisposition(physical.get())

    internal val submissionDisposition: SessionControlDrainerSubmissionDisposition
        get() = submissionDisposition(submission.get())

    internal fun prepare(nextGeneration: Long): Boolean = synchronized(submissionFence) {
        if (nextGeneration <= 0L) return@synchronized false
        val priorGeneration = generation.get()
        if (priorGeneration != 0L &&
            (physical.get() != PHYSICAL_RETURNED && physical.get() != PHYSICAL_NOT_SUBMITTED ||
                    bridge.get() != BRIDGE_APPLIED)
        ) {
            return@synchronized false
        }
        exactOuter.set(null)
        exactDelegate.set(null)
        rejectedDelegate.set(null)
        directFatal.set(null)
        taskThrowable.set(null)
        submissionFailureRaw.set(null)
        submissionDirectFatalRaw.set(null)
        taskReturn.set(TASK_EMPTY)
        bridge.set(BRIDGE_PENDING)
        portInvocation.set(PORT_PENDING)
        portFailure.set(null)
        inertBeforeDelegate.set(INERT_OPEN)
        rejectionReceipt.set(REJECTION_NONE)
        generation.set(nextGeneration)
        submission.set(SUBMISSION_SUBMITTING)
        physical.set(PHYSICAL_PENDING)
        true
    }

    internal fun publishSubmissionAccepted(
        expectedGeneration: Long,
    ): SessionControlDrainerSubmissionAcceptancePublicationOutcome = synchronized(submissionFence) {
        if (generation.get() == expectedGeneration &&
            rejectionReceipt.get() == REJECTION_NONE &&
            submission.compareAndSet(SUBMISSION_ADMITTED, SUBMISSION_ACCEPTED)
        ) {
            SessionControlDrainerSubmissionAcceptancePublicationOutcome.Published
        } else {
            SessionControlDrainerSubmissionAcceptancePublicationOutcome.NotEligible
        }
    }

    internal fun claimSubmissionInvocation(
        expectedGeneration: Long,
    ): SessionControlDrainerInvocationClaimOutcome = synchronized(submissionFence) {
        if (generation.get() != expectedGeneration ||
            submission.get() != SUBMISSION_SUBMITTING ||
            physical.get() != PHYSICAL_PENDING ||
            exactOuter.get() != null ||
            exactDelegate.get() != null
        ) {
            return@synchronized SessionControlDrainerInvocationClaimOutcome.NotEligible
        }
        when (controlPoison.claim(ControlPoisonClaim.DrainerExecuteInvocation)) {
            ControlPoisonClaimOutcome.Admitted -> {
                submission.set(SUBMISSION_ADMITTED)
                SessionControlDrainerInvocationClaimOutcome.Admitted
            }

            ControlPoisonClaimOutcome.PoisonFenced -> {
                submission.set(SUBMISSION_POISON_FENCED)
                physical.set(PHYSICAL_NOT_SUBMITTED)
                bridge.set(BRIDGE_APPLIED)
                SessionControlDrainerInvocationClaimOutcome.PoisonFenced
            }

            ControlPoisonClaimOutcome.ClaimExhausted -> {
                submission.set(SUBMISSION_CLAIM_EXHAUSTED)
                physical.set(PHYSICAL_NOT_SUBMITTED)
                bridge.set(BRIDGE_APPLIED)
                SessionControlDrainerInvocationClaimOutcome.ClaimExhausted
            }
        }
    }

    internal fun publishSubmissionFailure(
        expectedGeneration: Long,
        raw: Throwable,
    ): SessionControlDrainerSubmissionFailurePublicationOutcome =
        synchronized(submissionFence) {
            if (generation.get() != expectedGeneration ||
                (submission.get() != SUBMISSION_ADMITTED &&
                        submission.get() != SUBMISSION_REJECTION_RECEIPTED) ||
                !submissionFailureRaw.compareAndSet(null, raw)
            ) {
                return@synchronized SessionControlDrainerSubmissionFailurePublicationOutcome.NotEligible
            }
            if (FatalThrowablePolicy.isDirectFatal(raw)) submissionDirectFatalRaw.compareAndSet(null, raw)
            if (rejectionReceipt.get() != REJECTION_NONE) {
                submission.set(SUBMISSION_REJECTED)
                SessionControlDrainerSubmissionFailurePublicationOutcome.Rejected
            } else {
                submission.set(SUBMISSION_ACCEPTANCE_AMBIGUOUS)
                SessionControlDrainerSubmissionFailurePublicationOutcome.Ambiguous
            }
        }

    override fun bindDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean =
        synchronized(submissionFence) {
            if (submission.get() != SUBMISSION_ADMITTED ||
                physical.get() != PHYSICAL_PENDING ||
                !exactDelegate.compareAndSet(null, delegate)
            ) {
                return@synchronized false
            }
            if (exactOuter.compareAndSet(null, outerWrapper)) return@synchronized true
            exactDelegate.compareAndSet(delegate, null)
            false
        }

    override fun publishBindingRejected(
        delegate: Runnable,
    ): ControlSchedulerRejectionReceiptPublicationOutcome = synchronized(submissionFence) {
        if (submission.get() != SUBMISSION_ADMITTED || physical.get() != PHYSICAL_PENDING ||
            exactOuter.get() != null || exactDelegate.get() != null ||
            rejectionReceipt.get() != REJECTION_NONE || !rejectedDelegate.compareAndSet(null, delegate)
        ) {
            return@synchronized ControlSchedulerRejectionReceiptPublicationOutcome.NotEligible
        }
        physical.set(PHYSICAL_NOT_SUBMITTED)
        rejectionReceipt.set(REJECTION_BINDING)
        bridge.set(BRIDGE_APPLIED)
        submission.set(SUBMISSION_REJECTION_RECEIPTED)
        ControlSchedulerRejectionReceiptPublicationOutcome.Published
    }

    override fun publishExecutorRejected(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlSchedulerRejectionReceiptPublicationOutcome = synchronized(submissionFence) {
        if (submission.get() != SUBMISSION_ADMITTED || !matchesDecoratedTask(outerWrapper, delegate) ||
            rejectionReceipt.get() != REJECTION_NONE ||
            !physical.compareAndSet(PHYSICAL_PENDING, PHYSICAL_NOT_SUBMITTED)
        ) {
            return@synchronized ControlSchedulerRejectionReceiptPublicationOutcome.NotEligible
        }
        rejectionReceipt.set(REJECTION_EXECUTOR)
        bridge.set(BRIDGE_APPLIED)
        submission.set(SUBMISSION_REJECTION_RECEIPTED)
        ControlSchedulerRejectionReceiptPublicationOutcome.Published
    }

    override fun observeSchedulerRejectionReceipt(): ControlSchedulerRejectionReceipt =
        rejectionReceipt(rejectionReceipt.get())

    override fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean {
        if (exactOuter.get() !== outerWrapper || !physical.compareAndSet(PHYSICAL_PENDING, PHYSICAL_ON_STACK)) return false
        taskReturn.compareAndSet(TASK_EMPTY, TASK_RUNNING)
        return true
    }

    override fun markBoundOuterReturned(outerWrapper: Runnable): Boolean =
        exactOuter.get() === outerWrapper && physical.compareAndSet(PHYSICAL_ON_STACK, PHYSICAL_RETURNED)

    override fun markBoundTaskInert(outerWrapper: Runnable, delegate: Runnable): Boolean {
        if (!matchesDecoratedTask(outerWrapper, delegate) || physical.get() != PHYSICAL_ON_STACK) return false
        if (!inertBeforeDelegate.compareAndSet(INERT_OPEN, INERT_MARKED)) return false
        taskReturn.compareAndSet(TASK_RUNNING, TASK_INERT)
        return true
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
        return portInvocation.compareAndSet(PORT_PENDING, PORT_INVOKING)
    }

    override fun observeAfterExecutePortDisposition(
        outerWrapper: Runnable,
        delegate: Runnable,
    ): ControlAfterExecutePortDisposition? {
        if (!matchesDecoratedTask(outerWrapper, delegate)) return null
        return portDisposition(portInvocation.get())
    }

    override fun publishAfterExecutePortReturned(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
    ): Boolean {
        if (!matchesPendingThrowable(outerWrapper, delegate, expectedRaw)) return false
        return portInvocation.compareAndSet(PORT_INVOKING, PORT_RETURNED)
    }

    override fun publishAfterExecutePortThrown(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable,
        portRaw: Throwable,
    ): Boolean {
        if (!matchesPendingThrowable(outerWrapper, delegate, expectedRaw) ||
            portInvocation.get() != PORT_INVOKING ||
            !portFailure.compareAndSet(null, portRaw)
        ) {
            return false
        }
        return portInvocation.compareAndSet(PORT_INVOKING, PORT_THROWN)
    }

    override fun publishAfterExecuteApplied(
        outerWrapper: Runnable,
        delegate: Runnable,
        expectedRaw: Throwable?,
    ): Boolean {
        if (!matchesDecoratedTask(outerWrapper, delegate) || taskThrowable.get() !== expectedRaw) return false
        if (expectedRaw != null && portInvocation.get() != PORT_RETURNED) return false
        return bridge.compareAndSet(BRIDGE_PENDING, BRIDGE_APPLIED)
    }

    private fun matchesDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean =
        exactOuter.get() === outerWrapper && exactDelegate.get() === delegate

    private fun matchesPendingAfterExecute(outerWrapper: Runnable, delegate: Runnable): Boolean =
        matchesDecoratedTask(outerWrapper, delegate) && bridge.get() == BRIDGE_PENDING

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

    private fun physicalDisposition(value: Int): SessionControlDrainerPhysicalDisposition = when (value) {
        PHYSICAL_PENDING -> SessionControlDrainerPhysicalDisposition.Pending
        PHYSICAL_ON_STACK -> SessionControlDrainerPhysicalDisposition.OnStack
        PHYSICAL_RETURNED -> SessionControlDrainerPhysicalDisposition.Returned
        PHYSICAL_NOT_SUBMITTED -> SessionControlDrainerPhysicalDisposition.NotSubmitted
        else -> SessionControlDrainerPhysicalDisposition.Unused
    }

    private fun submissionDisposition(value: Int): SessionControlDrainerSubmissionDisposition = when (value) {
        SUBMISSION_SUBMITTING -> SessionControlDrainerSubmissionDisposition.Submitting
        SUBMISSION_ADMITTED -> SessionControlDrainerSubmissionDisposition.Admitted
        SUBMISSION_ACCEPTED -> SessionControlDrainerSubmissionDisposition.Accepted
        SUBMISSION_ACCEPTANCE_AMBIGUOUS -> SessionControlDrainerSubmissionDisposition.AcceptanceAmbiguous
        SUBMISSION_POISON_FENCED -> SessionControlDrainerSubmissionDisposition.PoisonFenced
        SUBMISSION_CLAIM_EXHAUSTED -> SessionControlDrainerSubmissionDisposition.ClaimExhausted
        SUBMISSION_REJECTION_RECEIPTED -> SessionControlDrainerSubmissionDisposition.RejectionReceipted
        SUBMISSION_REJECTED -> SessionControlDrainerSubmissionDisposition.Rejected
        else -> SessionControlDrainerSubmissionDisposition.None
    }

    private companion object {
        private const val PHYSICAL_UNUSED = 0
        private const val PHYSICAL_PENDING = 1
        private const val PHYSICAL_ON_STACK = 2
        private const val PHYSICAL_RETURNED = 3
        private const val PHYSICAL_NOT_SUBMITTED = 4
        private const val SUBMISSION_NONE = 0
        private const val SUBMISSION_SUBMITTING = 1
        private const val SUBMISSION_ADMITTED = 2
        private const val SUBMISSION_ACCEPTED = 3
        private const val SUBMISSION_ACCEPTANCE_AMBIGUOUS = 4
        private const val SUBMISSION_POISON_FENCED = 5
        private const val SUBMISSION_CLAIM_EXHAUSTED = 6
        private const val SUBMISSION_REJECTION_RECEIPTED = 7
        private const val SUBMISSION_REJECTED = 8
        private const val TASK_EMPTY = 0
        private const val TASK_RUNNING = 1
        private const val TASK_RETURNED = 2
        private const val TASK_THROWN = 3
        private const val TASK_INERT = 4
        private const val BRIDGE_PENDING = 0
        private const val BRIDGE_APPLIED = 1
        private const val PORT_PENDING = 0
        private const val PORT_INVOKING = 1
        private const val PORT_RETURNED = 2
        private const val PORT_THROWN = 3
        private const val INERT_OPEN = 0
        private const val INERT_MARKED = 1
        private const val REJECTION_NONE = 0
        private const val REJECTION_BINDING = 1
        private const val REJECTION_EXECUTOR = 2
    }
}

private class SessionTypedScheduledTask<V>(
    internal val runner: ControlScheduledRunner,
    internal val record: ControlScheduledTaskRecord,
    internal val delegateTask: RunnableScheduledFuture<V>,
    private val controlPoison: ControlPoisonAuthority,
) : RunnableScheduledFuture<V> {
    init {
        if (!record.bindDecoratedTask(this, delegateTask)) {
            record.publishBindingRejected(delegateTask)
            throw CONTROL_TASK_BINDING_REJECTED
        }
    }

    override fun run() {
        if (!record.markBoundOuterOnStack(this)) return
        try {
            when (controlPoison.claim(ControlPoisonClaim.ReplacementWorkerEngineEntry)) {
                ControlPoisonClaimOutcome.Admitted -> delegateTask.run()
                ControlPoisonClaimOutcome.PoisonFenced,
                ControlPoisonClaimOutcome.ClaimExhausted,
                    -> {
                    check(record.markBoundTaskInert(this, delegateTask))
                    return
                }
            }
        } finally {
            record.markBoundOuterReturned(this)
        }
    }

    override fun isPeriodic(): Boolean = delegateTask.isPeriodic
    override fun getDelay(unit: TimeUnit): Long = delegateTask.getDelay(unit)
    override fun compareTo(other: Delayed): Int =
        delegateTask.compareTo((other as? SessionTypedScheduledTask<*>)?.delegateTask ?: other)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = try {
        delegateTask.cancel(mayInterruptIfRunning)
    } catch (raw: Throwable) {
        throw raw
    }

    override fun isCancelled(): Boolean = delegateTask.isCancelled
    override fun isDone(): Boolean = delegateTask.isDone
    override fun get(): V = delegateTask.get()
    override fun get(timeout: Long, unit: TimeUnit): V = delegateTask.get(timeout, unit)

    private companion object {
        private val CONTROL_TASK_BINDING_REJECTED = RejectedExecutionException("Control task record binding rejected")
    }
}

internal enum class SessionControlStartupCleanupExecutionOutcome {
    Released,
    AwaitingReceipt,
    NotEligible,
}

internal class SessionControlStartupCleanupAction internal constructor(
    private val startupRecord: SessionControlStartupRecord,
) {
    private val exactScheduler = AtomicReference<SessionControlScheduler?>(null)
    private val state = AtomicInteger(STARTUP_CLEANUP_UNBOUND)

    internal val releasedReceipt: SessionControlTerminationReceipt?
        get() = exactScheduler.get()?.terminationRoot?.observeReleasedReceipt()

    internal fun bindScheduler(scheduler: SessionControlScheduler): Boolean {
        if (!exactScheduler.compareAndSet(null, scheduler)) return false
        if (state.compareAndSet(STARTUP_CLEANUP_UNBOUND, STARTUP_CLEANUP_BOUND)) return true
        exactScheduler.compareAndSet(scheduler, null)
        return false
    }

    internal fun execute(): SessionControlStartupCleanupExecutionOutcome {
        val scheduler = exactScheduler.get() ?: return SessionControlStartupCleanupExecutionOutcome.NotEligible
        if (state.compareAndSet(STARTUP_CLEANUP_BOUND, STARTUP_CLEANUP_INVOKING)) {
            try {
                scheduler.executeStartupCleanup(this, startupRecord)
            } finally {
                state.set(STARTUP_CLEANUP_EXECUTED)
            }
        }
        return when {
            releasedReceipt != null -> SessionControlStartupCleanupExecutionOutcome.Released
            state.get() == STARTUP_CLEANUP_EXECUTED -> SessionControlStartupCleanupExecutionOutcome.AwaitingReceipt
            else -> SessionControlStartupCleanupExecutionOutcome.NotEligible
        }
    }

    private companion object {
        private const val STARTUP_CLEANUP_UNBOUND = 0
        private const val STARTUP_CLEANUP_BOUND = 1
        private const val STARTUP_CLEANUP_INVOKING = 2
        private const val STARTUP_CLEANUP_EXECUTED = 3
    }
}

internal enum class SessionControlFinalShutdownClaimOutcome {
    Claimed,
    NotEligible,
}

internal enum class SessionControlFinalShutdownDisposition {
    NotClaimed,
    Claimed,
    Invoking,
    Returned,
    Thrown,
}

internal class SessionControlFinalShutdownAction internal constructor(
    private val owner: SessionControlScheduler,
    private val runtimeOwner: SessionControlRuntimeOwner,
) {
    private val state = AtomicInteger(FINAL_NOT_CLAIMED)
    private val failureRaw = AtomicReference<Throwable?>(null)
    private val directFatalRaw = AtomicReference<Throwable?>(null)

    internal val disposition: SessionControlFinalShutdownDisposition
        get() = when (state.get()) {
            FINAL_CLAIMED -> SessionControlFinalShutdownDisposition.Claimed
            FINAL_INVOKING -> SessionControlFinalShutdownDisposition.Invoking
            FINAL_RETURNED -> SessionControlFinalShutdownDisposition.Returned
            FINAL_THROWN -> SessionControlFinalShutdownDisposition.Thrown
            else -> SessionControlFinalShutdownDisposition.NotClaimed
        }

    internal val failure: Throwable?
        get() = if (state.get() == FINAL_THROWN) failureRaw.get() else null

    internal val directFatalFailure: Throwable?
        get() = if (state.get() == FINAL_THROWN) directFatalRaw.get() else null

    internal fun claim(): SessionControlFinalShutdownClaimOutcome {
        if (!state.compareAndSet(FINAL_NOT_CLAIMED, FINAL_CLAIMED)) {
            return SessionControlFinalShutdownClaimOutcome.NotEligible
        }
        if (runtimeOwner.claimFinalShutdown(owner)) return SessionControlFinalShutdownClaimOutcome.Claimed
        check(state.compareAndSet(FINAL_CLAIMED, FINAL_NOT_CLAIMED))
        return SessionControlFinalShutdownClaimOutcome.NotEligible
    }

    internal fun execute(): SessionControlFinalShutdownDisposition {
        if (!state.compareAndSet(FINAL_CLAIMED, FINAL_INVOKING)) return disposition
        val raw: Throwable? = try {
            owner.shutdown()
            null
        } catch (failure: Throwable) {
            failure
        }
        if (raw == null) {
            state.set(FINAL_RETURNED)
        } else {
            failureRaw.set(raw)
            if (FatalThrowablePolicy.isDirectFatal(raw)) directFatalRaw.set(raw)
            state.set(FINAL_THROWN)
        }
        return disposition
    }

    private companion object {
        private const val FINAL_NOT_CLAIMED = 0
        private const val FINAL_CLAIMED = 1
        private const val FINAL_INVOKING = 2
        private const val FINAL_RETURNED = 3
        private const val FINAL_THROWN = 4
    }
}

internal class SessionControlScheduler internal constructor(
    internal val runtimeOwner: SessionControlRuntimeOwner,
    private val controlPoison: ControlPoisonAuthority,
    private val startupRecord: SessionControlStartupRecord,
    internal val startupCleanupAction: SessionControlStartupCleanupAction,
    private val port: SessionControlSchedulerPort,
) : ScheduledThreadPoolExecutor(
    1,
    runtimeOwner.threadFactory,
    SessionControlRejectionHandler,
) {
    private val startupCleanupBound: Boolean = startupCleanupAction.bindScheduler(this)
    internal val terminationRoot: SessionControlTerminationRoot
        get() = runtimeOwner.terminationRoot
    internal val finalShutdownAction: SessionControlFinalShutdownAction =
        SessionControlFinalShutdownAction(this, runtimeOwner)

    init {
        check(startupCleanupBound)
        check(runtimeOwner.bindScheduler(this))
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        check(
            startupRecord.publishConstructionReturned() ==
                    SessionControlStartupFactPublicationOutcome.Published,
        )
    }

    internal fun prestart(): SessionControlPrestartOutcome {
        if (startupRecord.claimPrestart() != SessionControlStartupClaimOutcome.Claimed) {
            return when (startupRecord.startupDisposition) {
                SessionControlStartupDisposition.Ready -> SessionControlPrestartOutcome.Ready
                SessionControlStartupDisposition.Failed -> SessionControlPrestartOutcome.CleanupRequired
                else -> SessionControlPrestartOutcome.NotEligible
            }
        }
        val failure: Throwable = try {
            if (prestartAllCoreThreads() == 1) {
                check(
                    startupRecord.publishReady() ==
                            SessionControlStartupFactPublicationOutcome.Published,
                )
                return SessionControlPrestartOutcome.Ready
            }
            PRESTART_DID_NOT_START
        } catch (raw: Throwable) {
            raw
        }
        check(
            startupRecord.publishStartupFailure(failure) ==
                    SessionControlStartupFactPublicationOutcome.Published,
        )
        return SessionControlPrestartOutcome.CleanupRequired
    }

    internal fun transferReadyRuntimeOwner(): SessionControlRuntimeTransferOutcome =
        if (startupRecord.startupDisposition == SessionControlStartupDisposition.Ready) {
            runtimeOwner.transferReadyScheduler(this)
        } else {
            SessionControlRuntimeTransferOutcome.NotEligible
        }

    internal fun executeStartupCleanup(
        action: SessionControlStartupCleanupAction,
        exactStartupRecord: SessionControlStartupRecord,
    ): SessionControlShutdownDisposition {
        check(action === startupCleanupAction)
        check(exactStartupRecord === startupRecord)
        check(runtimeOwner.ensureSchedulerBound(this))
        if (exactStartupRecord.claimShutdown() != SessionControlShutdownClaimOutcome.Claimed) {
            return exactStartupRecord.shutdownDisposition
        }
        val failure: Throwable? = try {
            shutdown()
            null
        } catch (raw: Throwable) {
            raw
        }
        if (failure == null) {
            check(
                exactStartupRecord.publishShutdownReturned() ==
                        SessionControlStartupFactPublicationOutcome.Published,
            )
        } else {
            check(
                exactStartupRecord.publishShutdownFailure(failure) ==
                        SessionControlStartupFactPublicationOutcome.Published,
            )
        }
        return exactStartupRecord.shutdownDisposition
    }

    override fun <V> decorateTask(
        runnable: Runnable,
        task: RunnableScheduledFuture<V>,
    ): RunnableScheduledFuture<V> {
        val typed = runnable as? ControlScheduledRunner ?: run {
            throw CONTROL_REJECTED_UNTYPED_TASK
        }
        return SessionTypedScheduledTask(typed, typed.scheduledTaskRecord, task, controlPoison)
    }

    override fun afterExecute(runnable: Runnable, @Suppress("UNUSED_PARAMETER") thrown: Throwable?) {
        val typed = runnable as? SessionTypedScheduledTask<*> ?: return
        val record = typed.record
        val directFatal = record.observeAfterExecuteDirectFatal(typed, typed.delegateTask)
        if (directFatal != null) {
            if (!record.claimAfterExecutePortInvocation(typed, typed.delegateTask, directFatal)) {
                FatalThrowablePolicy.rethrow(directFatal)
            }
            try {
                port.onControlTaskDirectFatal(record, directFatal)
                record.publishAfterExecutePortReturned(typed, typed.delegateTask, directFatal)
                record.publishAfterExecuteApplied(typed, typed.delegateTask, directFatal)
            } catch (portRaw: Throwable) {
                record.publishAfterExecutePortThrown(typed, typed.delegateTask, directFatal, portRaw)
            } finally {
                FatalThrowablePolicy.rethrow(directFatal)
            }
        }
        val taskThrowable = record.observeAfterExecuteThrowable(typed, typed.delegateTask)
        if (taskThrowable != null) {
            if (!record.claimAfterExecutePortInvocation(typed, typed.delegateTask, taskThrowable)) return
            try {
                port.onControlTaskOrdinaryEscaped(record, taskThrowable)
                record.publishAfterExecutePortReturned(typed, typed.delegateTask, taskThrowable)
                record.publishAfterExecuteApplied(typed, typed.delegateTask, taskThrowable)
            } catch (portRaw: Throwable) {
                record.publishAfterExecutePortThrown(typed, typed.delegateTask, taskThrowable, portRaw)
                throw portRaw
            }
            return
        }
        record.publishAfterExecuteApplied(typed, typed.delegateTask, null)
    }

    override fun terminated() {
        val exactRoot = runtimeOwner.terminationRoot
        exactRoot.publishReleaseInline(this, exactRoot.exactReceipt)
    }

    private companion object {
        private val PRESTART_DID_NOT_START = IllegalStateException("Control worker did not prestart")
        private val CONTROL_REJECTED_UNTYPED_TASK = RejectedExecutionException("Control accepts typed tasks only")
    }
}
