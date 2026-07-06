package dev.dmkr.screencaptureengine.internal.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Routes projection callbacks from startup ownership to runtime ownership.
 *
 * Startup receives callbacks until handoff. Terminal stop is replayed once to runtime if it was
 * observed before handoff. Pending non-terminal resize state is bounded to the latest value, and
 * the resize consumed as authoritative startup geometry is not replayed as an ordinary runtime
 * resize. Pending visibility is selected only as latest pre-commit state for
 * `capturedContentVisible` initialization; it must not become an extra public runtime visibility
 * update.
 */
internal class StartupProjectionCallbackRouter(
    startupSink: StartupSink,
    private val beforeNonTerminalDispatch: (() -> Unit)? = null,
) : MediaProjectionCallbackAdapter.Listener {
    private val lock = Any()
    private val terminalStopObserved = AtomicBoolean()
    private var isClosed = false
    private var activeStartupSink: StartupSink? = startupSink
    private var activeRuntimeListener: MediaProjectionCallbackAdapter.Listener? = null
    private var startupStopObserved = false
    private var runtimeStopDelivered = false
    private var latestPendingResize: StartupCapturedContentResize? = null
    private var consumedStartupResize: StartupCapturedContentResize? = null
    private var latestPendingVisibility: Boolean? = null

    fun markAuthoritativeStartupResizeConsumed(resize: StartupCapturedContentResize) {
        synchronized(lock) {
            check(!isClosed) { "Projection callback router is closed." }
            check(activeRuntimeListener == null) { "Projection callback router already handed off." }
            consumedStartupResize = resize
            if (latestPendingResize?.sameEventAs(resize) == true) {
                latestPendingResize = null
            }
        }
    }

    fun markProjectionStopObserved() {
        terminalStopObserved.set(true)
        synchronized(lock) {
            if (isClosed || startupStopObserved) return
            startupStopObserved = true
            latestPendingResize = null
            latestPendingVisibility = null
        }
    }

    fun handoffTo(listener: MediaProjectionCallbackAdapter.Listener) {
        val dispatch = synchronized(lock) {
            check(!isClosed) { "Projection callback router is closed." }
            check(activeRuntimeListener == null) { "Projection callback router already handed off." }
            activeStartupSink = null
            activeRuntimeListener = listener
            if (terminalStopObserved.get() && !runtimeStopDelivered) {
                runtimeStopDelivered = true
                { dispatchRuntimeStop() }
            } else {
                val resize = latestPendingResize
                val visibility = latestPendingVisibility
                latestPendingResize = null
                latestPendingVisibility = null
                {
                    resize?.let { dispatchRuntimeResize(it.toProjectionResize()) }
                    visibility?.let { dispatchRuntimeVisibility(it) }
                }
            }
        }
        dispatch()
    }

    fun replaceRuntimeListener(
        expectedCurrent: MediaProjectionCallbackAdapter.Listener,
        replacement: MediaProjectionCallbackAdapter.Listener,
    ) {
        synchronized(lock) {
            check(!isClosed) { "Projection callback router is closed." }
            check(activeRuntimeListener === expectedCurrent) { "Projection callback router has a different runtime listener." }
            activeRuntimeListener = replacement
        }
    }

    fun close() {
        synchronized(lock) {
            isClosed = true
            activeStartupSink = null
            activeRuntimeListener = null
        }
    }

    override fun onProjectionStopped() {
        terminalStopObserved.set(true)
        val dispatch = synchronized(lock) {
            if (isClosed) return
            val runtimeListener = activeRuntimeListener
            val startupSink = activeStartupSink
            when {
                (runtimeListener != null) && !runtimeStopDelivered -> {
                    runtimeStopDelivered = true
                    startupStopObserved = true
                    latestPendingResize = null
                    latestPendingVisibility = null
                    { dispatchRuntimeStop() }
                }

                (startupSink != null) && !startupStopObserved -> {
                    startupStopObserved = true
                    latestPendingResize = null
                    latestPendingVisibility = null
                    { dispatchStartupStop(startupSink) }
                }

                !startupStopObserved -> {
                    startupStopObserved = true
                    latestPendingResize = null
                    latestPendingVisibility = null
                    null
                }

                else -> null
            }
        }
        dispatch?.invoke()
    }

    override fun onCapturedContentResized(resize: ProjectionCapturedContentResize) {
        if ((resize.width <= 0) || (resize.height <= 0)) return
        if (terminalStopObserved.get()) return
        val dispatch = synchronized(lock) {
            if (isClosed || terminalStopObserved.get() || runtimeStopDelivered) return
            val startupResize = StartupCapturedContentResize.from(resize)
            if (consumedStartupResize?.sameEventAs(startupResize) == true) return
            val startupSink = activeStartupSink
            val runtimeListener = activeRuntimeListener
            when {
                startupSink != null -> {
                    latestPendingResize = startupResize
                    {
                        if (isStartupSinkCurrentForNonTerminal(startupSink)) {
                            startupSink.onCapturedContentResized(resize)
                        }
                    }
                }

                runtimeListener != null -> {
                    {
                        dispatchRuntimeResize(resize)
                    }
                }

                else -> {
                    latestPendingResize = startupResize
                    null
                }
            }
        }
        dispatchNonTerminal(dispatch)
    }

    override fun onCapturedContentResized(width: Int, height: Int) {
        onCapturedContentResized(ProjectionCapturedContentResize(id = 0L, width = width, height = height))
    }

    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
        if (terminalStopObserved.get()) return
        val dispatch = synchronized(lock) {
            if (isClosed || terminalStopObserved.get() || runtimeStopDelivered) return
            val startupSink = activeStartupSink
            val runtimeListener = activeRuntimeListener
            when {
                startupSink != null -> {
                    latestPendingVisibility = isVisible
                    {
                        if (isStartupSinkCurrentForNonTerminal(startupSink)) {
                            startupSink.onCapturedContentVisibilityChanged(isVisible = isVisible)
                        }
                    }
                }

                runtimeListener != null -> {
                    {
                        dispatchRuntimeVisibility(isVisible)
                    }
                }

                else -> {
                    latestPendingVisibility = isVisible
                    null
                }
            }
        }
        dispatchNonTerminal(dispatch)
    }

    private fun dispatchNonTerminal(dispatch: (() -> Unit)?) {
        if (dispatch == null) return
        beforeNonTerminalDispatch?.invoke()
        dispatch()
    }

    private fun dispatchRuntimeStop() {
        val listener = synchronized(lock) {
            if (isClosed || !runtimeStopDelivered) return
            activeRuntimeListener
        } ?: return
        listener.dispatchSelectedProjectionStopped()
    }

    private fun dispatchStartupStop(selectedSink: StartupSink) {
        val shouldDispatch = synchronized(lock) {
            !isClosed && activeStartupSink === selectedSink && startupStopObserved
        }
        if (shouldDispatch) selectedSink.onProjectionStopped()
    }

    private fun dispatchRuntimeResize(
        resize: ProjectionCapturedContentResize,
    ) {
        val listener = currentRuntimeListenerForNonTerminal() ?: return
        listener.dispatchSelectedCapturedContentResized(resize)
    }

    private fun dispatchRuntimeVisibility(
        isVisible: Boolean,
    ) {
        val listener = currentRuntimeListenerForNonTerminal() ?: return
        listener.dispatchSelectedCapturedContentVisibilityChanged(isVisible = isVisible)
    }

    private fun currentRuntimeListenerForNonTerminal(): MediaProjectionCallbackAdapter.Listener? =
        synchronized(lock) {
            if (isClosed || terminalStopObserved.get() || runtimeStopDelivered) return@synchronized null
            activeRuntimeListener
        }

    private fun isStartupSinkCurrentForNonTerminal(selectedSink: StartupSink): Boolean =
        synchronized(lock) {
            !isClosed && !terminalStopObserved.get() && !runtimeStopDelivered && activeStartupSink === selectedSink
        }

    interface StartupSink {
        fun onProjectionStopped()
        fun onCapturedContentResized(resize: ProjectionCapturedContentResize)
        fun onCapturedContentResized(width: Int, height: Int)
        fun onCapturedContentVisibilityChanged(isVisible: Boolean)
    }

    interface SelectedRuntimeListener : MediaProjectionCallbackAdapter.Listener {
        fun onRouterSelectedProjectionStopped()
        fun onRouterSelectedCapturedContentResized(resize: ProjectionCapturedContentResize)
        fun onRouterSelectedCapturedContentVisibilityChanged(isVisible: Boolean)
    }

    private fun MediaProjectionCallbackAdapter.Listener.dispatchSelectedProjectionStopped() {
        if (this is SelectedRuntimeListener) {
            onRouterSelectedProjectionStopped()
        } else {
            onProjectionStopped()
        }
    }

    private fun MediaProjectionCallbackAdapter.Listener.dispatchSelectedCapturedContentResized(
        resize: ProjectionCapturedContentResize,
    ) {
        if (this is SelectedRuntimeListener) {
            onRouterSelectedCapturedContentResized(resize)
        } else {
            onCapturedContentResized(resize)
        }
    }

    private fun MediaProjectionCallbackAdapter.Listener.dispatchSelectedCapturedContentVisibilityChanged(
        isVisible: Boolean,
    ) {
        if (this is SelectedRuntimeListener) {
            onRouterSelectedCapturedContentVisibilityChanged(isVisible = isVisible)
        } else {
            onCapturedContentVisibilityChanged(isVisible = isVisible)
        }
    }
}

internal class StartupCapturedContentResize(val id: Long, val width: Int, val height: Int) {
    fun sameEventAs(other: StartupCapturedContentResize): Boolean =
        if (id != UNIDENTIFIED_ID && other.id != UNIDENTIFIED_ID) {
            id == other.id
        } else {
            (width == other.width) && (height == other.height)
        }

    companion object {
        private const val UNIDENTIFIED_ID = 0L

        fun from(resize: ProjectionCapturedContentResize): StartupCapturedContentResize =
            StartupCapturedContentResize(id = resize.id, width = resize.width, height = resize.height)

        fun unidentified(width: Int, height: Int): StartupCapturedContentResize =
            StartupCapturedContentResize(id = UNIDENTIFIED_ID, width = width, height = height)
    }
}

internal fun StartupCapturedContentResize.toProjectionResize(): ProjectionCapturedContentResize =
    ProjectionCapturedContentResize(id = id, width = width, height = height)
