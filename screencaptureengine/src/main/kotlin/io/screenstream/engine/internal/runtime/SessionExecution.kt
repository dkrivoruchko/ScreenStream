package io.screenstream.engine.internal.runtime

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun interface ElapsedRealtimeClock {
    fun nanos(): Long
}

internal fun interface WallClock {
    fun epochMillis(): Long
}

internal fun interface NonDirectDispatcher {
    /** Enqueues [task]; an implementation must never invoke it before this call returns. */
    fun dispatch(task: Runnable): Boolean
}

/** Construction seam for the two affinity-bound Handler lanes. */
internal interface HandlerLaneConstruction {
    fun newThread(name: String): HandlerThread

    fun start(thread: HandlerThread)

    fun looper(thread: HandlerThread): Looper?

    fun handler(looper: Looper): Handler
}

internal object AndroidHandlerLaneConstruction : HandlerLaneConstruction {
    override fun newThread(name: String): HandlerThread = HandlerThread(name)

    override fun start(thread: HandlerThread) {
        thread.start()
    }

    override fun looper(thread: HandlerThread): Looper? = thread.looper

    override fun handler(looper: Looper): Handler = Handler(looper)
}

/** Immutable runtime composition; fakes can control every outward boundary independently. */
internal class SessionExecutionComposition internal constructor(
    internal val bootstrapDispatcher: NonDirectDispatcher,
    internal val cpuDispatcher: NonDirectDispatcher,
    internal val handlerLanes: HandlerLaneConstruction,
    internal val elapsedRealtimeClock: ElapsedRealtimeClock,
    internal val wallClock: WallClock,
    internal val platformSdkInt: Int = Build.VERSION.SDK_INT,
) {
    internal companion object {
        internal fun production(): SessionExecutionComposition = SessionExecutionComposition(
            bootstrapDispatcher = ProcessExecutors.dispatcher,
            cpuDispatcher = ProcessExecutors.dispatcher,
            handlerLanes = AndroidHandlerLaneConstruction,
            elapsedRealtimeClock = ElapsedRealtimeClock { SystemClock.elapsedRealtimeNanos() },
            wallClock = WallClock { System.currentTimeMillis() },
            platformSdkInt = Build.VERSION.SDK_INT,
        )
    }
}

/**
 * A one-slot asynchronous serial view. It deliberately has no waiting queue: callers retain
 * rejected work in its concrete role capsule and decide the typed outcome themselves.
 */
internal class AsyncSerialView internal constructor(
    private val dispatcher: NonDirectDispatcher,
) {
    private val occupied = AtomicBoolean(false)

    internal fun submit(
        task: () -> Unit,
        afterPermitReleased: (() -> Unit)? = null,
    ): Boolean {
        if (!occupied.compareAndSet(false, true)) return false

        val accepted = dispatcher.dispatch(
            Runnable {
                var taskFailure: Throwable? = null
                try {
                    task()
                } catch (failure: Throwable) {
                    taskFailure = failure
                } finally {
                    occupied.set(false)
                }

                taskFailure?.let { throw it }
                afterPermitReleased?.invoke()
            },
        )
        // A false return is the only definite non-enqueue outcome. A thrown dispatcher result is
        // ambiguous: retain occupancy so a task that was enqueued before the throw remains unique.
        if (!accepted) occupied.set(false)
        return accepted
    }
}

/** Three distinct permits over the injected shared process executor. */
internal class SessionSerialRoles internal constructor(dispatcher: NonDirectDispatcher) {
    internal val metrics: AsyncSerialView = AsyncSerialView(dispatcher)
    internal val jpeg: AsyncSerialView = AsyncSerialView(dispatcher)
    internal val delivery: AsyncSerialView = AsyncSerialView(dispatcher)
}

private object ProcessExecutors {
    private val threadNumber = AtomicLong(0L)
    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "ScreenCaptureEngine-Worker-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    private val executor = Executors.newCachedThreadPool(threadFactory)

    val dispatcher: NonDirectDispatcher = NonDirectDispatcher { task ->
        try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }
}
