package io.screenstream.engine.internal.controller

import io.screenstream.engine.internal.settlement.ControlScheduledRunner
import io.screenstream.engine.internal.settlement.ControlScheduledTaskRecord
import io.screenstream.engine.internal.settlement.FatalThrowablePolicy
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal interface SessionControlSchedulerPort {
    fun onControlTaskPoison(raw: Throwable)
    fun onControlTaskPostStackPoison(raw: Throwable)
    fun onControlTaskDirectFatal(record: ControlScheduledTaskRecord, raw: Throwable)
    fun onControlSchedulerTerminated(receipt: SessionControlTerminationReceipt)
}

internal class SessionControlTerminationReceipt internal constructor()

internal enum class SessionControlStartupDisposition {
    Constructed,
    Starting,
    Ready,
    Failed,
}

internal class SessionControlDrainerTaskRecord internal constructor(
    private val port: SessionControlSchedulerPort,
    private val delegate: Runnable,
) : ControlScheduledTaskRecord {
    private val generation = AtomicLong(0L)
    private val physical = AtomicInteger(PHYSICAL_UNUSED)
    private val submission = AtomicInteger(SUBMISSION_NONE)
    private val taskReturn = AtomicInteger(TASK_EMPTY)
    private val directFatal = AtomicReference<Throwable?>(null)
    private val taskThrowable = AtomicReference<Throwable?>(null)
    private val bridge = AtomicInteger(BRIDGE_APPLIED)
    private val exactOuter = AtomicReference<Runnable?>(null)
    private val exactDelegate = AtomicReference<Runnable?>(null)

    internal val runner: ControlScheduledRunner = object : ControlScheduledRunner {
        override val scheduledTaskRecord: ControlScheduledTaskRecord
            get() = this@SessionControlDrainerTaskRecord

        override fun run() {
            var rawReturn: Throwable? = null
            try {
                delegate.run()
                taskReturn.compareAndSet(TASK_RUNNING, TASK_RETURNED)
            } catch (raw: Throwable) {
                rawReturn = raw
                taskThrowable.compareAndSet(null, raw)
                if (FatalThrowablePolicy.isDirectFatal(raw)) directFatal.compareAndSet(null, raw)
                taskReturn.compareAndSet(TASK_RUNNING, TASK_THROWN)
            } finally {
                val exactRaw = rawReturn
                if (exactRaw != null) {
                    port.onControlTaskPoison(exactRaw)
                    throw exactRaw
                }
            }
        }
    }

    internal fun prepare(nextGeneration: Long): Boolean {
        if (nextGeneration <= 0L) return false
        val priorGeneration = generation.get()
        if (priorGeneration != 0L &&
            (physical.get() != PHYSICAL_RETURNED && physical.get() != PHYSICAL_NOT_SUBMITTED ||
                    bridge.get() != BRIDGE_APPLIED)
        ) {
            return false
        }
        exactOuter.set(null)
        exactDelegate.set(null)
        directFatal.set(null)
        taskThrowable.set(null)
        taskReturn.set(TASK_EMPTY)
        bridge.set(BRIDGE_PENDING)
        generation.set(nextGeneration)
        submission.set(SUBMISSION_SUBMITTING)
        physical.set(PHYSICAL_PENDING)
        return true
    }

    internal fun publishSubmissionAccepted(expectedGeneration: Long): Boolean =
        generation.get() == expectedGeneration &&
                submission.compareAndSet(SUBMISSION_SUBMITTING, SUBMISSION_ACCEPTED)

    internal fun publishSubmissionFailure(expectedGeneration: Long, raw: Throwable): Boolean {
        if (generation.get() != expectedGeneration) return false
        if (FatalThrowablePolicy.isDirectFatal(raw)) directFatal.compareAndSet(null, raw)
        physical.compareAndSet(PHYSICAL_PENDING, PHYSICAL_NOT_SUBMITTED)
        return submission.compareAndSet(SUBMISSION_SUBMITTING, SUBMISSION_FAILED)
    }

    override fun bindDecoratedTask(outerWrapper: Runnable, delegate: Runnable): Boolean {
        if (physical.get() != PHYSICAL_PENDING || !exactDelegate.compareAndSet(null, delegate)) return false
        if (exactOuter.compareAndSet(null, outerWrapper)) return true
        exactDelegate.compareAndSet(delegate, null)
        return false
    }

    override fun markBoundOuterOnStack(outerWrapper: Runnable): Boolean {
        if (exactOuter.get() !== outerWrapper || !physical.compareAndSet(PHYSICAL_PENDING, PHYSICAL_ON_STACK)) return false
        taskReturn.compareAndSet(TASK_EMPTY, TASK_RUNNING)
        return true
    }

    override fun markBoundOuterReturned(outerWrapper: Runnable): Boolean =
        exactOuter.get() === outerWrapper && physical.compareAndSet(PHYSICAL_ON_STACK, PHYSICAL_RETURNED)

    override fun claimAfterExecuteFatal(outerWrapper: Runnable, delegate: Runnable): Throwable? {
        if (exactOuter.get() !== outerWrapper || exactDelegate.get() !== delegate ||
            !bridge.compareAndSet(BRIDGE_PENDING, BRIDGE_APPLIED)
        ) {
            return null
        }
        return directFatal.get()
    }

    internal fun claimAfterExecuteThrowable(outerWrapper: Runnable, delegate: Runnable): Throwable? {
        if (exactOuter.get() !== outerWrapper || exactDelegate.get() !== delegate ||
            !bridge.compareAndSet(BRIDGE_PENDING, BRIDGE_APPLIED)
        ) {
            return null
        }
        return taskThrowable.get()
    }

    private companion object {
        private const val PHYSICAL_UNUSED = 0
        private const val PHYSICAL_PENDING = 1
        private const val PHYSICAL_ON_STACK = 2
        private const val PHYSICAL_RETURNED = 3
        private const val PHYSICAL_NOT_SUBMITTED = 4
        private const val SUBMISSION_NONE = 0
        private const val SUBMISSION_SUBMITTING = 1
        private const val SUBMISSION_ACCEPTED = 2
        private const val SUBMISSION_FAILED = 3
        private const val TASK_EMPTY = 0
        private const val TASK_RUNNING = 1
        private const val TASK_RETURNED = 2
        private const val TASK_THROWN = 3
        private const val BRIDGE_PENDING = 0
        private const val BRIDGE_APPLIED = 1
    }
}

private class SessionTypedScheduledTask<V>(
    internal val runner: ControlScheduledRunner,
    internal val record: ControlScheduledTaskRecord,
    internal val delegateTask: RunnableScheduledFuture<V>,
) : RunnableScheduledFuture<V> {
    init {
        if (!record.bindDecoratedTask(this, delegateTask)) throw CONTROL_TASK_BINDING_REJECTED
    }

    override fun run() {
        if (!record.markBoundOuterOnStack(this)) return
        try {
            delegateTask.run()
        } finally {
            record.markBoundOuterReturned(this)
        }
    }

    override fun isPeriodic(): Boolean = delegateTask.isPeriodic
    override fun getDelay(unit: TimeUnit): Long = delegateTask.getDelay(unit)
    override fun compareTo(other: Delayed): Int =
        delegateTask.compareTo((other as? SessionTypedScheduledTask<*>)?.delegateTask ?: other)

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = delegateTask.cancel(mayInterruptIfRunning)
    override fun isCancelled(): Boolean = delegateTask.isCancelled
    override fun isDone(): Boolean = delegateTask.isDone
    override fun get(): V = delegateTask.get()
    override fun get(timeout: Long, unit: TimeUnit): V = delegateTask.get(timeout, unit)

    private companion object {
        private val CONTROL_TASK_BINDING_REJECTED = RejectedExecutionException("Control task record binding rejected")
    }
}

internal class SessionControlScheduler internal constructor(
    private val port: SessionControlSchedulerPort,
) : ScheduledThreadPoolExecutor(
    1,
    ThreadFactory { runnable -> Thread(runnable, "ScreenCaptureEngine-Control") },
    AbortPolicy(),
) {
    private val terminatedOnce = AtomicBoolean(false)
    private val terminationReceipt = SessionControlTerminationReceipt()
    private val startup = AtomicReference(SessionControlStartupDisposition.Constructed)
    private val recordedStartupFailure = AtomicReference<Throwable?>(null)

    init {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }

    internal val startupFailure: Throwable?
        get() = recordedStartupFailure.get()

    internal fun prestart(): SessionControlStartupDisposition {
        if (!startup.compareAndSet(
                SessionControlStartupDisposition.Constructed,
                SessionControlStartupDisposition.Starting,
            )
        ) {
            return startup.get()
        }
        try {
            if (prestartAllCoreThreads() == 1) {
                startup.set(SessionControlStartupDisposition.Ready)
                return SessionControlStartupDisposition.Ready
            }
            recordedStartupFailure.compareAndSet(null, PRESTART_DID_NOT_START)
        } catch (raw: Throwable) {
            recordedStartupFailure.compareAndSet(null, raw)
            startup.set(SessionControlStartupDisposition.Failed)
            shutdown()
            if (FatalThrowablePolicy.isDirectFatal(raw)) FatalThrowablePolicy.rethrow(raw)
            return SessionControlStartupDisposition.Failed
        }
        startup.set(SessionControlStartupDisposition.Failed)
        shutdown()
        return SessionControlStartupDisposition.Failed
    }

    override fun <V> decorateTask(
        runnable: Runnable,
        task: RunnableScheduledFuture<V>,
    ): RunnableScheduledFuture<V> {
        val typed = runnable as? ControlScheduledRunner
            ?: throw RejectedExecutionException("Control accepts typed tasks only")
        return SessionTypedScheduledTask(typed, typed.scheduledTaskRecord, task)
    }

    override fun afterExecute(runnable: Runnable, @Suppress("UNUSED_PARAMETER") thrown: Throwable?) {
        val typed = runnable as? SessionTypedScheduledTask<*> ?: return
        val record = typed.record
        if (record is SessionControlDrainerTaskRecord) {
            val raw = record.claimAfterExecuteThrowable(typed, typed.delegateTask) ?: return
            if (!FatalThrowablePolicy.isDirectFatal(raw)) {
                port.onControlTaskPostStackPoison(raw)
                return
            }
            try {
                port.onControlTaskDirectFatal(record, raw)
            } finally {
                FatalThrowablePolicy.rethrow(raw)
            }
        }
        val directFatal = record.claimAfterExecuteFatal(typed, typed.delegateTask)
        if (directFatal != null) {
            try {
                port.onControlTaskDirectFatal(record, directFatal)
            } finally {
                FatalThrowablePolicy.rethrow(directFatal)
            }
        }
    }

    override fun terminated() {
        if (terminatedOnce.compareAndSet(false, true)) port.onControlSchedulerTerminated(terminationReceipt)
    }

    private companion object {
        private val PRESTART_DID_NOT_START = IllegalStateException("Control worker did not prestart")
    }
}
