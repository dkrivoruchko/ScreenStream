package io.screenstream.capture.internal.runtime

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal object ProductionRuntime {
    private val workerNumber = AtomicLong(0L)
    private val workerThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "$WORKER_THREAD_NAME_PREFIX${workerNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val deadlineThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, DEADLINE_THREAD_NAME).apply {
            isDaemon = true
        }
    }
    private val workerExecutor: Executor = Executors.newCachedThreadPool(workerThreadFactory)
    private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor(deadlineThreadFactory)

    internal val workerDispatcher = NonInlineDispatcher { task ->
        try {
            workerExecutor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private object AndroidHandlerTaskPoster : HandlerTaskPoster {
        override fun post(handler: Handler, task: Runnable): Boolean = handler.post(task)

        override fun postDelayed(handler: Handler, task: Runnable, delayMillis: Long): Boolean =
            handler.postDelayed(task, delayMillis)

        override fun removeCallbacks(handler: Handler, task: Runnable) {
            handler.removeCallbacks(task)
        }
    }

    private object AndroidHandlerThreadPlatform : HandlerThreadPlatform {
        override fun newThread(name: String): HandlerThread = HandlerThread(name)

        override fun start(thread: HandlerThread) {
            thread.start()
        }

        override fun looper(thread: HandlerThread): Looper? = thread.looper

        override fun handler(looper: Looper): Handler = Handler(looper)
    }

    internal val handlerThreadPlatform: HandlerThreadPlatform = AndroidHandlerThreadPlatform

    internal val handlerTaskPoster: HandlerTaskPoster = AndroidHandlerTaskPoster

    internal val delayedEntryScheduler = DelayedEntryScheduler { task, delayNanos ->
        require(delayNanos >= 0L)
        try {
            deadlineExecutor.schedule(task, delayNanos, TimeUnit.NANOSECONDS)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    internal val elapsedRealtimeClock: ElapsedRealtimeClock = ElapsedRealtimeClock { SystemClock.elapsedRealtimeNanos() }

    internal val currentEpochMillis: () -> Long = { System.currentTimeMillis() }

    internal const val THREAD_NAME_PREFIX: String = "ScreenCaptureEngine-"

    private const val WORKER_THREAD_NAME_PREFIX: String = THREAD_NAME_PREFIX + "Worker-"
    private const val DEADLINE_THREAD_NAME: String = THREAD_NAME_PREFIX + "Deadline"
}
