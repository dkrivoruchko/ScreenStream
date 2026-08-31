@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.screenstream.capture.testutil

import io.screenstream.capture.internal.runtime.DelayedEntryScheduler
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal sealed class ScheduleOutcome {
    internal object Accept : ScheduleOutcome()

    internal object Reject : ScheduleOutcome()

    internal class Throw(internal val failure: Exception) : ScheduleOutcome()
}

internal enum class ScheduleAttemptKind {
    Accepted,
    Rejected,
    Thrown,
}

internal enum class ScheduledTaskState {
    Accepted,
    Queued,
    Entered,
    Completed,
    Failed,
}

internal class ManualDelayedEntryScheduler(
    initialOutcome: ScheduleOutcome = ScheduleOutcome.Accept,
    threadName: String = "ScreenCaptureEngine-Test-Deadline",
) : DelayedEntryScheduler, AutoCloseable {
    internal class TaskHandle internal constructor(
        internal val task: Runnable,
        internal val delayNanos: Long,
    ) {
        @Volatile
        internal var state: ScheduledTaskState = ScheduledTaskState.Accepted

        @Volatile
        internal var completed: Boolean = false

        @Volatile
        internal var failure: Throwable? = null

        private val stateMonitor = Object()

        internal fun awaitCompleted(timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS): Boolean =
            await(timeoutNanos) { completed }

        internal fun awaitCompletion(timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS): Throwable? {
            check(awaitCompleted(timeoutNanos)) { "worker task did not complete before the bounded wait expired" }
            return failure
        }

        internal fun awaitSuccessfulCompletion(timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS) {
            awaitCompletion(timeoutNanos)?.let { throw it }
        }

        private fun await(timeoutNanos: Long, condition: () -> Boolean): Boolean {
            require(timeoutNanos > 0L) { "timeoutNanos must be positive" }
            val deadline = System.nanoTime() + timeoutNanos
            synchronized(stateMonitor) {
                while (!condition()) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return false
                    val millis = remaining / NANOS_PER_MILLISECOND
                    val nanos = (remaining % NANOS_PER_MILLISECOND).toInt()
                    try {
                        stateMonitor.wait(millis, nanos)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return true
            }
        }

        internal fun stateChanged() {
            synchronized(stateMonitor) {
                stateMonitor.notifyAll()
            }
        }

        internal companion object {
            private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
            private const val DEFAULT_TIMEOUT_NANOS: Long = 5_000_000_000L
        }
    }

    internal class Submission internal constructor(
        internal val kind: ScheduleAttemptKind,
    )

    private val monitor = Object()
    private val outcomeQueue = ArrayDeque<ScheduleOutcome>()
    private val submissions = ArrayList<Submission>()
    private val scheduledTasks = ArrayDeque<TaskHandle>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(WorkerThreadFactory(threadName))
    private var defaultOutcome: ScheduleOutcome = initialOutcome
    private var closed: Boolean = false

    override fun trySchedule(task: Runnable, delayNanos: Long): Boolean {
        require(delayNanos >= 0L) { "delayNanos must be non-negative" }
        val outcome = synchronized(monitor) {
            if (closed) ScheduleOutcome.Reject else outcomeQueue.removeFirstOrNull() ?: defaultOutcome
        }
        return when (outcome) {
            ScheduleOutcome.Accept -> {
                synchronized(monitor) {
                    if (closed) {
                        submissions += Submission(ScheduleAttemptKind.Rejected)
                        return@synchronized false
                    }
                    val handle = TaskHandle(task, delayNanos)
                    scheduledTasks.addLast(handle)
                    submissions += Submission(ScheduleAttemptKind.Accepted)
                    monitor.notifyAll()
                    true
                }
            }

            ScheduleOutcome.Reject -> {
                synchronized(monitor) {
                    submissions += Submission(ScheduleAttemptKind.Rejected)
                    monitor.notifyAll()
                }
                false
            }

            is ScheduleOutcome.Throw -> {
                synchronized(monitor) {
                    submissions += Submission(ScheduleAttemptKind.Thrown)
                    monitor.notifyAll()
                }
                throw outcome.failure
            }
        }
    }

    internal fun enqueue(outcome: ScheduleOutcome) = synchronized(monitor) {
        outcomeQueue.addLast(outcome)
    }

    internal fun enqueueReject() = enqueue(ScheduleOutcome.Reject)

    internal fun enqueueThrow(failure: Exception) = enqueue(ScheduleOutcome.Throw(failure))

    internal fun submissions(): List<Submission> = synchronized(monitor) { submissions.toList() }

    internal fun scheduledTasks(): List<TaskHandle> = synchronized(monitor) { scheduledTasks.toList() }

    internal fun pendingCount(): Int = synchronized(monitor) {
        scheduledTasks.count { it.state == ScheduledTaskState.Accepted }
    }

    internal fun enter(handle: TaskHandle): Boolean {
        synchronized(monitor) {
            if (!scheduledTasks.contains(handle)) return false
            if (handle.state != ScheduledTaskState.Accepted) return false
            handle.state = ScheduledTaskState.Queued
        }
        submitToWorker(handle)
        return true
    }

    private fun submitToWorker(handle: TaskHandle) {
        try {
            worker.execute {
                handle.state = ScheduledTaskState.Entered
                handle.stateChanged()
                try {
                    handle.task.run()
                    handle.state = ScheduledTaskState.Completed
                } catch (failure: Throwable) {
                    handle.failure = failure
                    handle.state = ScheduledTaskState.Failed
                } finally {
                    handle.completed = true
                    handle.stateChanged()
                }
            }
        } catch (failure: RuntimeException) {
            synchronized(monitor) {
                if (handle.state == ScheduledTaskState.Queued) handle.state = ScheduledTaskState.Accepted
            }
            throw failure
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            monitor.notifyAll()
        }
        worker.shutdownNow()
        try {
            worker.awaitTermination(5L, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private class WorkerThreadFactory(
        private val threadName: String,
    ) : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread = Thread(runnable, threadName).apply {
            isDaemon = true
        }
    }
}
