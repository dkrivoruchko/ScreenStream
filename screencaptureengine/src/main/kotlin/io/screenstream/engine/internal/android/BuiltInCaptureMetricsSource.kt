package io.screenstream.engine.internal.android

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import io.screenstream.engine.BuiltInCaptureMetricsDefinition
import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.CaptureMetricsObserver
import io.screenstream.engine.CaptureMetricsSubscription
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Public built-in sources remain genuinely reusable outside a Session. Engine-owned observations use
 * [BuiltInCaptureMetricsAttachment] so their reads and close run on the Session's sole Metrics endpoint;
 * a direct public subscription owns this isolated source-side endpoint instead.
 */
internal fun subscribeToBuiltInCaptureMetrics(
    definition: BuiltInCaptureMetricsDefinition,
    observer: CaptureMetricsObserver,
): CaptureMetricsSubscription = StandaloneBuiltInMetricsObservation(definition, observer).also { it.start() }

private class StandaloneBuiltInMetricsObservation(
    private val definition: BuiltInCaptureMetricsDefinition,
    private val observer: CaptureMetricsObserver,
) : CaptureMetricsSubscription {
    private val workerThread = AtomicReference<Thread?>(null)
    private val callbacksOpen = AtomicBoolean(true)
    private val refreshDirty = AtomicBoolean(true)
    private val epochInvalidated = AtomicBoolean(false)
    private val drainQueued = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)
    private val closeComplete = CountDownLatch(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reusableRealSize = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        ThreadFactory { runnable ->
            Thread(runnable, "ScreenCaptureEngine-MetricsSource").also {
                workerThread.set(it)
                it.isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private var listenerRegistered = false
    private var epochDisplay: Display? = null
    private var epochWindowContext: Context? = null
    private var epochWindowManager: WindowManager? = null

    private val listener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayBoundary(displayId)

        override fun onDisplayRemoved(displayId: Int) = onDisplayBoundary(displayId)

        override fun onDisplayChanged(displayId: Int) {
            if (callbacksOpen.get() && displayId == definition.selectedDisplayId) requestRefresh()
        }
    }

    internal fun start() {
        executor.prestartCoreThread()
        requestDrain()
    }

    override fun close() {
        if (closeRequested.compareAndSet(false, true)) {
            callbacksOpen.set(false)
            requestDrain()
        }
        if (Thread.currentThread() === workerThread.get()) {
            closeOnWorker()
            return
        }
        var interrupted = false
        while (true) {
            try {
                closeComplete.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun requestRefresh() {
        if (!callbacksOpen.get()) return
        refreshDirty.set(true)
        requestDrain()
    }

    private fun requestDrain() {
        if (!drainQueued.compareAndSet(false, true)) return
        try {
            executor.execute(::drainOnWorker)
        } catch (failure: RejectedExecutionException) {
            drainQueued.set(false)
            if (closeRequested.get()) {
                closeComplete.countDown()
            } else {
                observer.onFailure(failure)
            }
        }
    }

    private fun drainOnWorker() {
        try {
            if (!listenerRegistered && !closeRequested.get()) {
                definition.displayManager.registerDisplayListener(listener, mainHandler)
                listenerRegistered = true
            }
            while (!closeRequested.get() && refreshDirty.getAndSet(false)) {
                refreshOnWorker()
            }
            if (closeRequested.get()) closeOnWorker()
        } catch (failure: Exception) {
            callbacksOpen.set(false)
            closeRequested.set(true)
            try {
                observer.onFailure(failure)
            } finally {
                closeOnWorker()
            }
        } finally {
            drainQueued.set(false)
            if ((closeRequested.get() || refreshDirty.get()) && closeComplete.count != 0L) requestDrain()
        }
    }

    private fun refreshOnWorker() {
        if (epochInvalidated.getAndSet(false)) {
            retireEpoch()
            observer.onMetricsChanged(null)
            refreshDirty.set(true)
            return
        }

        val selected = selectedDisplay()
        if (selected == null || !selected.isValid) {
            retireEpoch()
            observer.onMetricsChanged(null)
            return
        }
        if (epochDisplay !== selected) {
            retireEpoch()
            installEpoch(selected)
        }
        if (!selectionStillMatches(selected) || !selected.isValid) {
            retireEpoch()
            observer.onMetricsChanged(null)
            return
        }

        val candidate = readCompleteMetrics(selected)
        if (!selectionStillMatches(selected) || !selected.isValid || epochInvalidated.get()) {
            retireEpoch()
            observer.onMetricsChanged(null)
            refreshDirty.set(true)
            return
        }
        observer.onMetricsChanged(candidate)
    }

    private fun onDisplayBoundary(displayId: Int) {
        if (!callbacksOpen.get() || displayId != definition.selectedDisplayId) return
        epochInvalidated.set(true)
        requestRefresh()
    }

    private fun selectedDisplay(): Display? =
        definition.fixedDisplay ?: definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionStillMatches(display: Display): Boolean =
        definition.fixedDisplay?.let { it === display }
            ?: (definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY) === display)

    private fun installEpoch(display: Display) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                definition.applicationContext.createWindowContext(
                    display,
                    WindowManager.LayoutParams.TYPE_APPLICATION,
                    null,
                )
            } else {
                definition.applicationContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
            }
            epochWindowContext = context
            epochWindowManager = requireNotNull(context.getSystemService(WindowManager::class.java))
        }
        epochDisplay = display
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(display: Display): CaptureMetrics? {
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = requireNotNull(epochWindowManager).maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val point = requireNotNull(reusableRealSize)
            display.getRealSize(point)
            width = point.x
            height = point.y
        }
        val density = definition.applicationContext
            .createDisplayContext(display)
            .resources.configuration.densityDpi
        return if (width > 0 && height > 0 && density > 0) CaptureMetrics(width, height, density) else null
    }

    private fun retireEpoch() {
        epochDisplay = null
        epochWindowContext = null
        epochWindowManager = null
    }

    private fun closeOnWorker() {
        if (closeComplete.count == 0L) return
        callbacksOpen.set(false)
        refreshDirty.set(false)
        retireEpoch()
        try {
            if (listenerRegistered) {
                definition.displayManager.unregisterDisplayListener(listener)
                listenerRegistered = false
            }
        } finally {
            closeComplete.countDown()
            executor.shutdown()
        }
    }
}
