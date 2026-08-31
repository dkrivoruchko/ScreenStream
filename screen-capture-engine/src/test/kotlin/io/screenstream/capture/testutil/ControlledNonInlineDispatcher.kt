@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package io.screenstream.capture.testutil

import io.screenstream.capture.internal.runtime.NonInlineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

internal sealed class DispatchOutcome {
    internal object Accept : DispatchOutcome()

    internal object Reject : DispatchOutcome()

    internal class Throw(internal val failure: Exception) : DispatchOutcome()
}

internal enum class DispatchAttemptKind {
    Accepted,
    Rejected,
    Thrown,
}

internal enum class DispatchTaskState {
    Accepted,
    Queued,
    Entered,
    Completed,
    Failed,
}

internal class ControlledNonInlineDispatcher(
    initialOutcome: DispatchOutcome = DispatchOutcome.Accept,
    threadName: String = "ScreenCaptureEngine-Test-Worker",
    workerThreadCount: Int = 1,
) : NonInlineDispatcher, AutoCloseable {
    init {
        require(workerThreadCount > 0) { "workerThreadCount must be positive" }
    }

    internal class TaskHandle internal constructor(
        internal val task: Runnable,
    ) {
        @Volatile
        internal var state: DispatchTaskState = DispatchTaskState.Accepted

        @Volatile
        internal var entered: Boolean = false

        @Volatile
        internal var completed: Boolean = false

        @Volatile
        internal var failure: Throwable? = null

        @Volatile
        internal var enteredThread: Thread? = null

        private val stateMonitor = Object()

        internal fun awaitEntered(timeoutNanos: Long = DEFAULT_TIMEOUT_NANOS): Boolean =
            await(timeoutNanos) { entered }

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

        private fun notifyStateChanged() {
            synchronized(stateMonitor) {
                stateMonitor.notifyAll()
            }
        }

        internal companion object {
            private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
            private const val DEFAULT_TIMEOUT_NANOS: Long = 5_000_000_000L
        }

        internal fun stateChanged() {
            notifyStateChanged()
        }
    }

    internal class Submission internal constructor(
        internal val kind: DispatchAttemptKind,
    )

    private val monitor = Object()
    private val outcomeQueue = ArrayDeque<DispatchOutcome>()
    private val submissions = ArrayList<Submission>()
    private val acceptedTasks = ArrayDeque<TaskHandle>()
    private val worker: ExecutorService = Executors.newFixedThreadPool(workerThreadCount, WorkerThreadFactory(threadName))
    private var defaultOutcome: DispatchOutcome = initialOutcome
    private var closed: Boolean = false

    override fun tryDispatch(task: Runnable): Boolean {
        val outcome = synchronized(monitor) {
            if (closed) DispatchOutcome.Reject else outcomeQueue.removeFirstOrNull() ?: defaultOutcome
        }
        return when (outcome) {
            DispatchOutcome.Accept -> {
                synchronized(monitor) {
                    if (closed) {
                        submissions += Submission(DispatchAttemptKind.Rejected)
                        return@synchronized false
                    }
                    val handle = TaskHandle(task)
                    acceptedTasks.addLast(handle)
                    submissions += Submission(DispatchAttemptKind.Accepted)
                    monitor.notifyAll()
                    true
                }
            }

            DispatchOutcome.Reject -> {
                synchronized(monitor) {
                    submissions += Submission(DispatchAttemptKind.Rejected)
                    monitor.notifyAll()
                }
                false
            }

            is DispatchOutcome.Throw -> {
                synchronized(monitor) {
                    submissions += Submission(DispatchAttemptKind.Thrown)
                    monitor.notifyAll()
                }
                throw outcome.failure
            }
        }
    }

    internal fun enqueue(outcome: DispatchOutcome) = synchronized(monitor) {
        outcomeQueue.addLast(outcome)
    }

    internal fun enqueueReject() = enqueue(DispatchOutcome.Reject)

    internal fun enqueueThrow(failure: Exception) = enqueue(DispatchOutcome.Throw(failure))

    internal fun submissions(): List<Submission> = synchronized(monitor) { submissions.toList() }

    internal fun pendingCount(): Int = synchronized(monitor) { acceptedTasks.count { it.state == DispatchTaskState.Accepted } }

    internal fun enterNext(): TaskHandle? {
        val handle = synchronized(monitor) {
            val next = acceptedTasks.firstOrNull { it.state == DispatchTaskState.Accepted }
            if (next == null) return@synchronized null
            next.state = DispatchTaskState.Queued
            next
        }
        if (handle == null) return null
        submitToWorker(handle)
        return handle
    }

    private fun submitToWorker(handle: TaskHandle) {
        try {
            worker.execute {
                handle.state = DispatchTaskState.Entered
                handle.entered = true
                handle.enteredThread = Thread.currentThread()
                handle.stateChanged()
                try {
                    handle.task.run()
                    handle.state = DispatchTaskState.Completed
                } catch (failure: Throwable) {
                    handle.failure = failure
                    handle.state = DispatchTaskState.Failed
                } finally {
                    handle.completed = true
                    handle.stateChanged()
                }
            }
        } catch (failure: RuntimeException) {
            synchronized(monitor) {
                if (handle.state == DispatchTaskState.Queued) handle.state = DispatchTaskState.Accepted
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
