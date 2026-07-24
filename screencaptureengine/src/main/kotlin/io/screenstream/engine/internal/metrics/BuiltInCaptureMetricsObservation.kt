package io.screenstream.engine.internal.metrics

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsSubscription
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Resource-local identity for one continuous-validity Display epoch. */
internal class BuiltInCaptureMetricsEpoch internal constructor()

internal interface BuiltInCaptureMetricsSink {
    fun onMetricsChanged(
        metrics: CaptureMetrics?,
        display: Display?,
        epoch: BuiltInCaptureMetricsEpoch?,
    )

    fun onObservationFailure(cause: Exception)

    fun onCloseCompleted()

    fun onCloseFailure(cause: Exception)
}

/**
 * One built-in attachment. The injected dispatcher supplies serial, non-direct execution; Main callbacks only
 * update the bounded refresh summary and request this observation's one reusable drain task.
 */
internal class BuiltInCaptureMetricsObservation(
    private val source: BuiltInCaptureMetricsSource,
    private val sink: BuiltInCaptureMetricsSink,
    private val dispatcher: BuiltInMetricsDispatcher,
) : CaptureMetricsSubscription {
    private enum class ListenerState {
        Prepared,
        RegistrationAttempted,
        Registered,
        Closed,
    }

    private enum class PublishedAvailability {
        None,
        Available,
        Unavailable,
    }

    private class DisplayEpoch(
        val identity: BuiltInCaptureMetricsEpoch,
        val display: Display,
        @Suppress("unused") val windowContext: Context?,
        val windowManager: WindowManager?,
    )

    private val started = AtomicBoolean()
    private val signals = AtomicInteger(OPEN or DIRTY)
    private val mainHandler = source.platform.mainHandler()
    private val drainTask = Runnable { drainOneTurn() }

    /** Dispatcher-confined state. */
    private var listenerState = ListenerState.Prepared
    private var epoch: DisplayEpoch? = null
    private var publishedAvailability = PublishedAvailability.None
    private var terminalCloseFailure: Exception? = null

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = true)
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = true)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == source.selectedDisplayId) requestRefresh(invalidateEpoch = false)
        }
    }

    internal fun start() {
        check(started.compareAndSet(false, true)) { "Built-in metrics observation already started" }
        scheduleDrain()
    }

    /** Initial attach path when the caller is already entered on the Session's Metrics role. */
    internal fun startOnMetricsRole() {
        check(started.compareAndSet(false, true)) { "Built-in metrics observation already started" }
        drainOneTurn()
    }

    /** Exact built-in close boundary when the caller is already entered on the Session's Metrics role. */
    internal fun closeOnMetricsRole(): Exception? {
        requestCloseFromDispatcher()
        return closePhysical()
    }

    override fun close() {
        while (true) {
            val current = signals.get()
            if (current and (CLOSE_REQUESTED or FINISHED) != 0) return
            val next = (current and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED
            if (signals.compareAndSet(current, next)) break
        }
        scheduleDrain()
    }

    private fun requestRefresh(invalidateEpoch: Boolean) {
        val requested = DIRTY or if (invalidateEpoch) EPOCH_INVALIDATED else 0
        while (true) {
            val current = signals.get()
            if (current and OPEN == 0) return
            val next = current or requested
            if (next == current || signals.compareAndSet(current, next)) break
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!started.get()) return
        while (true) {
            val current = signals.get()
            if (current and (SCHEDULED or FINISHED) != 0) return
            if (current and (DIRTY or CLOSE_REQUESTED) == 0) return
            if (!signals.compareAndSet(current, current or SCHEDULED)) continue
            try {
                dispatcher.dispatch(drainTask)
            } catch (failure: Throwable) {
                clearScheduled()
                throw failure
            }
            return
        }
    }

    private fun drainOneTurn() {
        var directFailure: Throwable? = null
        try {
            if (signals.get() and CLOSE_REQUESTED != 0) {
                closePhysical()
                return
            }
            if (listenerState === ListenerState.Prepared) {
                listenerState = ListenerState.RegistrationAttempted
                try {
                    source.platform.registerDisplayListener(source.displayManager, listener, mainHandler)
                    listenerState = ListenerState.Registered
                } catch (failure: Exception) {
                    failObservation(failure)
                    return
                }
            }
            if (signals.get() and CLOSE_REQUESTED != 0) {
                closePhysical()
                return
            }
            val refresh = claimRefresh()
            if (refresh != 0) {
                try {
                    performRefresh(refresh and EPOCH_INVALIDATED != 0)
                } catch (failure: Exception) {
                    failObservation(failure)
                }
            }
        } catch (failure: Throwable) {
            directFailure = closeAfterDirectFailure(failure)
        } finally {
            clearScheduled()
            scheduleDrain()
        }
        directFailure?.let { throw it }
    }

    private fun claimRefresh(): Int {
        while (true) {
            val current = signals.get()
            if (current and OPEN == 0 || current and DIRTY == 0) return 0
            val next = current and (DIRTY or EPOCH_INVALIDATED).inv()
            if (signals.compareAndSet(current, next)) return current
        }
    }

    private fun performRefresh(epochInvalidated: Boolean) {
        if (epochInvalidated) {
            val retired = retireEpoch()
            publishUnavailable(retired?.display ?: source.fixedDisplay, retired?.identity)
            requestRefresh(invalidateEpoch = false)
            return
        }

        val selectedDisplay = source.resolveSelectedDisplay()
        if (selectedDisplay == null || !source.selectionIsValid(selectedDisplay)) {
            val retired = retireEpoch()
            publishUnavailable(retired?.display ?: source.fixedDisplay, retired?.identity)
            return
        }

        if (epoch?.display !== selectedDisplay) {
            retireEpoch()
            epoch = createEpoch(selectedDisplay)
        }
        val readEpoch = checkNotNull(epoch)
        if (!source.selectionStillMatches(readEpoch.display)) {
            retireEpoch()
            publishUnavailable(readEpoch.display, readEpoch.identity)
            requestRefresh(invalidateEpoch = false)
            return
        }

        val metrics = readCompleteMetrics(readEpoch)
        val boundaryObserved = signals.get() and EPOCH_INVALIDATED != 0
        if (epoch !== readEpoch || boundaryObserved || !source.selectionStillMatches(readEpoch.display)) {
            if (epoch === readEpoch) retireEpoch()
            publishUnavailable(readEpoch.display, readEpoch.identity)
            requestRefresh(invalidateEpoch = false)
            return
        }
        publish(metrics, readEpoch.display, readEpoch.identity)
    }

    private fun createEpoch(display: Display): DisplayEpoch {
        if (source.platform.sdkInt < Build.VERSION_CODES.R) {
            return DisplayEpoch(BuiltInCaptureMetricsEpoch(), display, null, null)
        }
        val windowContext = if (source.platform.sdkInt == Build.VERSION_CODES.R) {
            val displayContext = source.platform.createDisplayContext(source.applicationContext, display)
            source.platform.createApi30WindowContext(displayContext)
        } else {
            source.platform.createApi31WindowContext(source.applicationContext, display)
        }
        return DisplayEpoch(
            identity = BuiltInCaptureMetricsEpoch(),
            display = display,
            windowContext = windowContext,
            windowManager = source.platform.windowManager(windowContext),
        )
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(readEpoch: DisplayEpoch): CaptureMetrics? {
        if (!source.platform.isValid(readEpoch.display)) return null
        val dimensions = if (source.platform.sdkInt < Build.VERSION_CODES.R) {
            val point = Point()
            source.platform.getRealSize(readEpoch.display, point)
            point.x to point.y
        } else {
            val bounds = source.platform.maximumWindowBounds(checkNotNull(readEpoch.windowManager))
            bounds.width() to bounds.height()
        }
        val densityDpi = source.platform
            .createDisplayContext(source.applicationContext, readEpoch.display)
            .resources.configuration.densityDpi
        if (!source.platform.isValid(readEpoch.display)) return null
        return if (dimensions.first > 0 && dimensions.second > 0 && densityDpi > 0) {
            CaptureMetrics(dimensions.first, dimensions.second, densityDpi)
        } else {
            null
        }
    }

    private fun publish(
        metrics: CaptureMetrics?,
        display: Display,
        epochIdentity: BuiltInCaptureMetricsEpoch,
    ) {
        if (metrics == null) {
            publishUnavailable(display, epochIdentity)
            return
        }
        if (!admitObserverCallback(closeIngress = false)) return
        try {
            publishedAvailability = PublishedAvailability.Available
            sink.onMetricsChanged(metrics, display, epochIdentity)
        } finally {
            leaveObserverCallback()
        }
    }

    private fun publishUnavailable(display: Display?, epochIdentity: BuiltInCaptureMetricsEpoch?) {
        if (publishedAvailability === PublishedAvailability.Unavailable) return
        if (!admitObserverCallback(closeIngress = false)) return
        try {
            publishedAvailability = PublishedAvailability.Unavailable
            sink.onMetricsChanged(null, display, epochIdentity)
        } finally {
            leaveObserverCallback()
        }
    }

    private fun retireEpoch(): DisplayEpoch? = epoch.also { epoch = null }

    private fun failObservation(cause: Exception) {
        if (!admitObserverCallback(closeIngress = true)) {
            closePhysical()
            return
        }
        try {
            sink.onObservationFailure(cause)
        } finally {
            leaveObserverCallback()
            closePhysical()
        }
    }

    /** Linearizes observer entry against close without invoking the observer under a lock. */
    private fun admitObserverCallback(closeIngress: Boolean): Boolean {
        while (true) {
            val current = signals.get()
            if (current and OPEN == 0 || current and OBSERVER_ENTERED != 0) return false
            var next = current or OBSERVER_ENTERED
            if (closeIngress) {
                next = (next and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED
            }
            if (signals.compareAndSet(current, next)) return true
        }
    }

    private fun leaveObserverCallback() {
        while (true) {
            val current = signals.get()
            check(current and OBSERVER_ENTERED != 0)
            if (signals.compareAndSet(current, current and OBSERVER_ENTERED.inv())) return
        }
    }

    private fun requestCloseFromDispatcher() {
        while (true) {
            val current = signals.get()
            if (current and (CLOSE_REQUESTED or FINISHED) != 0) return
            val next = (current and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED
            if (signals.compareAndSet(current, next)) return
        }
    }

    private fun closePhysical(): Exception? {
        if (listenerState === ListenerState.Closed) return terminalCloseFailure
        val mustUnregister = listenerState === ListenerState.RegistrationAttempted ||
                listenerState === ListenerState.Registered
        listenerState = ListenerState.Closed
        retireEpoch()
        var failure: Exception? = null
        try {
            if (mustUnregister) {
                source.platform.unregisterDisplayListener(source.displayManager, listener)
            }
        } catch (cause: Exception) {
            failure = cause
        } finally {
            terminalCloseFailure = failure
            markFinished()
        }
        if (failure == null) sink.onCloseCompleted() else sink.onCloseFailure(failure)
        return failure
    }

    private fun closeAfterDirectFailure(primary: Throwable): Throwable {
        requestCloseFromDispatcher()
        return try {
            closePhysical()
            primary
        } catch (closeFailure: Throwable) {
            if (closeFailure !== primary) {
                try {
                    primary.addSuppressed(closeFailure)
                } catch (_: Throwable) {
                    // The first direct failure remains authoritative.
                }
            }
            primary
        }
    }

    private fun markFinished() {
        while (true) {
            val current = signals.get()
            val next = (current and (OPEN or DIRTY or EPOCH_INVALIDATED).inv()) or CLOSE_REQUESTED or FINISHED
            if (signals.compareAndSet(current, next)) return
        }
    }

    private fun clearScheduled() {
        while (true) {
            val current = signals.get()
            if (current and SCHEDULED == 0) return
            if (signals.compareAndSet(current, current and SCHEDULED.inv())) return
        }
    }

    private companion object {
        const val OPEN = 1
        const val DIRTY = 1 shl 1
        const val EPOCH_INVALIDATED = 1 shl 2
        const val SCHEDULED = 1 shl 3
        const val CLOSE_REQUESTED = 1 shl 4
        const val FINISHED = 1 shl 5
        const val OBSERVER_ENTERED = 1 shl 6
    }
}

internal interface BuiltInCaptureMetricsPlatform {
    val sdkInt: Int

    fun mainHandler(): Handler

    fun displayId(display: Display): Int

    fun isValid(display: Display): Boolean

    fun getDisplay(displayManager: DisplayManager, displayId: Int): Display?

    fun registerDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
        handler: Handler,
    )

    fun unregisterDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
    )

    fun createDisplayContext(applicationContext: Context, display: Display): Context

    fun createApi30WindowContext(displayContext: Context): Context

    fun createApi31WindowContext(applicationContext: Context, display: Display): Context

    fun windowManager(windowContext: Context): WindowManager

    fun maximumWindowBounds(windowManager: WindowManager): Rect

    fun getRealSize(display: Display, point: Point)
}

internal object AndroidBuiltInCaptureMetricsPlatform : BuiltInCaptureMetricsPlatform {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun mainHandler(): Handler = Handler(Looper.getMainLooper())

    override fun displayId(display: Display): Int = display.displayId

    override fun isValid(display: Display): Boolean = display.isValid

    override fun getDisplay(displayManager: DisplayManager, displayId: Int): Display? =
        displayManager.getDisplay(displayId)

    override fun registerDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
        handler: Handler,
    ) {
        displayManager.registerDisplayListener(listener, handler)
    }

    override fun unregisterDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
    ) {
        displayManager.unregisterDisplayListener(listener)
    }

    override fun createDisplayContext(applicationContext: Context, display: Display): Context =
        applicationContext.createDisplayContext(display)

    @TargetApi(Build.VERSION_CODES.R)
    override fun createApi30WindowContext(displayContext: Context): Context =
        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)

    @TargetApi(Build.VERSION_CODES.S)
    override fun createApi31WindowContext(applicationContext: Context, display: Display): Context =
        applicationContext.createWindowContext(
            display,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            null,
        )

    @TargetApi(Build.VERSION_CODES.R)
    override fun windowManager(windowContext: Context): WindowManager =
        requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
            "WindowManager must be available for the selected display"
        }

    @TargetApi(Build.VERSION_CODES.R)
    override fun maximumWindowBounds(windowManager: WindowManager): Rect =
        windowManager.maximumWindowMetrics.bounds

    @Suppress("DEPRECATION")
    override fun getRealSize(display: Display, point: Point) {
        display.getRealSize(point)
    }
}
