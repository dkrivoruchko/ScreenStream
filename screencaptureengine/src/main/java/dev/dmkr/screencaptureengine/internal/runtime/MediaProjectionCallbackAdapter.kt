package dev.dmkr.screencaptureengine.internal.runtime

import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MediaProjection callback registration with raw-event observation and serialized listener delivery.
 *
 * Platform callbacks first pass through the close/terminal gate on the callback handler thread. The
 * optional synchronous observer sees accepted raw events immediately for startup arbitration, then
 * the registered listener is delivered on a single listener executor. Projection stop is
 * terminal for this adapter and suppresses later resize or visibility delivery.
 */
internal class MediaProjectionCallbackAdapter internal constructor(
    private val listener: Listener,
    private val synchronousEventObserver: ((ProjectionCallbackRawEvent) -> Unit)? = null,
    callbackThreadName: String = "ScreenCaptureMediaProjectionCallback",
    listenerThreadName: String = "ScreenCaptureMediaProjectionEvents",
    private val listenerExecutor: ExecutorService = createListenerExecutor(listenerThreadName),
) : ProjectionCallbackRegistration {
    private val stateLock = Any()
    private val callbackThread = HandlerThread(callbackThreadName, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val isClosed = AtomicBoolean()
    private val eventSequence = java.util.concurrent.atomic.AtomicLong()
    private var registeredProjection: ProjectionHandle? = null

    private val handler: Handler = Handler(callbackThread.looper)
    override val callbackHandler: ProjectionCallbackHandlerHandle = AndroidProjectionCallbackHandlerHandle(handler)
    override val projectionStopArbiter: ProjectionStopArbiter = ProjectionStopArbiter()
    override val projectionStopObserved: Boolean
        get() = projectionStopArbiter.projectionStopObserved

    internal val callback: MediaProjection.Callback = object : MediaProjection.Callback() {
        override fun onStop() {
            dispatchObservedStop()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            dispatchObservedEvent(
                ProjectionCallbackRawEvent.Resize(
                    ProjectionCapturedContentResize(
                        id = eventSequence.incrementAndGet(),
                        width = width,
                        height = height,
                    ),
                ),
            )
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            dispatchObservedEvent(ProjectionCallbackRawEvent.Visibility(isVisible))
        }
    }

    override fun register(projection: ProjectionHandle) {
        synchronized(stateLock) {
            check(!isClosed.get()) { "MediaProjectionCallbackAdapter is closed." }
            check(registeredProjection == null) { "MediaProjectionCallbackAdapter is already registered." }
            projection.registerCallback(callback, callbackHandler)
            registeredProjection = projection
        }
    }

    override fun close() {
        val projection = synchronized(stateLock) {
            if (!isClosed.compareAndSet(false, true)) return
            registeredProjection.also { registeredProjection = null }
        }
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collect { projection?.unregisterCallback(callback) }
        cleanupFailures.collect { handler.removeCallbacksAndMessages(null) }
        cleanupFailures.collect { callbackThread.quitSafely() }
        cleanupFailures.collect { listenerExecutor.shutdownNow() }
        cleanupFailures.throwIfAny()
    }

    private fun dispatchObservedEvent(event: ProjectionCallbackRawEvent) {
        synchronized(stateLock) {
            if (isClosed.get()) return
            when (event) {
                ProjectionCallbackRawEvent.Stop -> error("Stop must be dispatched through dispatchObservedStop.")
                is ProjectionCallbackRawEvent.Resize,
                is ProjectionCallbackRawEvent.Visibility -> if (projectionStopArbiter.projectionStopObserved) return
            }
        }
        deliverObservedEvent(event)
    }

    private fun dispatchObservedStop() {
        if (isClosed.get()) return
        if (!projectionStopArbiter.markRawStopObserved()) return
        if (isClosed.get()) return
        deliverObservedEvent(ProjectionCallbackRawEvent.Stop)
    }

    private fun deliverObservedEvent(event: ProjectionCallbackRawEvent) {
        if (isClosed.get()) return
        if (event != ProjectionCallbackRawEvent.Stop && projectionStopArbiter.projectionStopObserved) return
        synchronousEventObserver?.invoke(event)
        try {
            listenerExecutor.execute {
                val dispatch = synchronized(stateLock) {
                    if (isClosed.get()) {
                        null
                    } else when (event) {
                        ProjectionCallbackRawEvent.Stop -> listener::onProjectionStopped
                        is ProjectionCallbackRawEvent.Resize -> if (!projectionStopArbiter.projectionStopObserved) {
                            { listener.onCapturedContentResized(event.resize) }
                        } else {
                            null
                        }

                        is ProjectionCallbackRawEvent.Visibility -> if (!projectionStopArbiter.projectionStopObserved) {
                            { listener.onCapturedContentVisibilityChanged(event.isVisible) }
                        } else {
                            null
                        }
                    }
                }
                if (!isClosed.get()) dispatch?.invoke()
            }
        } catch (_: RejectedExecutionException) {
            // Close may race with platform callback delivery.
        }
    }

    internal interface Listener {
        fun onProjectionStopped()

        fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
            onCapturedContentResized(width = resize.width, height = resize.height)
        }

        fun onCapturedContentResized(width: Int, height: Int)
        fun onCapturedContentVisibilityChanged(isVisible: Boolean)
    }

    private companion object {
        private fun createListenerExecutor(threadName: String): ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, threadName).apply { isDaemon = true }
            }
    }
}

internal data class ProjectionCapturedContentResize(
    val id: Long,
    val width: Int,
    val height: Int,
)

internal sealed interface ProjectionCallbackRawEvent {
    data object Stop : ProjectionCallbackRawEvent
    class Resize(val resize: ProjectionCapturedContentResize) : ProjectionCallbackRawEvent
    class Visibility(val isVisible: Boolean) : ProjectionCallbackRawEvent
}
