package dev.dmkr.screencaptureengine.internal.session

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Admission-controlled dispatch target for engine delivery work and final public callback execution.
 *
 * The engine-owned target exposes queue saturation synchronously. Caller-owned dispatchers do not
 * expose portable saturation state, so exceptions and cancellation observed before callback
 * admission are classified best-effort. A successful dispatch handoff is not a successful
 * delivery; delivery succeeds only after callback admission and normal callback return.
 */
internal sealed interface ScreenCaptureFrameDeliveryDispatcher {
    val isCallerOwned: Boolean

    fun dispatchFailure(block: (dispatchCancelledBeforeStart: Boolean) -> Unit): Throwable?

    fun close()

    class Delegating(
        override val isCallerOwned: Boolean,
        private val dispatch: (block: (dispatchCancelledBeforeStart: Boolean) -> Unit) -> Throwable?,
        private val closeDispatcher: () -> Unit,
    ) : ScreenCaptureFrameDeliveryDispatcher {
        override fun dispatchFailure(block: (dispatchCancelledBeforeStart: Boolean) -> Unit): Throwable? =
            dispatch(block)

        override fun close() {
            closeDispatcher()
        }
    }

    class EngineOwned(threadNamePrefix: String, queueCapacity: Int, workerCount: Int) : ScreenCaptureFrameDeliveryDispatcher {
        override val isCallerOwned: Boolean = false

        private val threadIndex = AtomicInteger()
        private val executor = ThreadPoolExecutor(
            workerCount,
            workerCount,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
        ) { runnable ->
            Thread(runnable, "$threadNamePrefix-${threadIndex.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

        override fun dispatchFailure(block: (dispatchCancelledBeforeStart: Boolean) -> Unit): Throwable? =
            try {
                executor.execute {
                    ScreenCaptureEngineOwnedContext.run {
                        block(false)
                    }
                }
                null
            } catch (throwable: Throwable) {
                throwable
            }

        override fun close() {
            executor.shutdown()
        }
    }

    class CallerOwned(private val dispatcher: CoroutineDispatcher) : ScreenCaptureFrameDeliveryDispatcher {
        override val isCallerOwned: Boolean = true

        override fun dispatchFailure(block: (dispatchCancelledBeforeStart: Boolean) -> Unit): Throwable? {
            val dispatchJob = Job()
            return try {
                val runnable = Runnable {
                    ScreenCaptureEngineOwnedContext.run {
                        try {
                            block(dispatchJob.isCancelled)
                        } finally {
                            dispatchJob.complete()
                        }
                    }
                }
                if (dispatcher.isDispatchNeeded(dispatchJob)) {
                    dispatcher.dispatch(dispatchJob, runnable)
                } else {
                    runnable.run()
                }
                if (dispatchJob.isCancelled) {
                    CancellationException("Frame callback dispatch was cancelled before callback admission.")
                } else {
                    null
                }
            } catch (throwable: Throwable) {
                throwable
            }
        }

        override fun close() = Unit
    }
}

internal object ScreenCaptureEngineOwnedContext {
    private val current: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    val isCurrent: Boolean
        get() = current.get() == true

    fun run(block: () -> Unit) {
        val previous = current.get() == true
        current.set(true)
        try {
            block()
        } finally {
            current.set(previous)
        }
    }
}
