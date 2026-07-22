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
import java.util.concurrent.atomic.AtomicInteger
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
    private val mechanics = AtomicInteger(OPEN or DIRTY)
    private val firstDirectFatal = AtomicReference<Throwable?>(null)
    private val closeComplete = CountDownLatch(1)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reusableRealSize = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null
    private val drainRunnable = Runnable { drainOnWorker() }
    private val executor = object : ThreadPoolExecutor(
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
        AbortPolicy(),
    ) {
        override fun terminated() {
            try {
                super.terminated()
            } finally {
                closeComplete.countDown()
            }
        }
    }

    private enum class ListenerProgress {
        Prepared,
        RegistrationAttempted,
        Registered,
        Closed,
    }

    private var listenerProgress = ListenerProgress.Prepared
    private var epochDisplay: Display? = null
    private var epochWindowContext: Context? = null
    private var epochWindowManager: WindowManager? = null

    private val listener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayBoundary(displayId)

        override fun onDisplayRemoved(displayId: Int) = onDisplayBoundary(displayId)

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == definition.selectedDisplayId) requestRefresh()
        }
    }

    internal fun start() {
        executor.prestartCoreThread()
        requestDrain()
    }

    override fun close() {
        requestClose()
        if (Thread.currentThread() === workerThread.get()) {
            if (mechanics.get() and SEALED == 0) closeOnWorker()
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

    private fun requestClose() {
        while (true) {
            val current = mechanics.get()
            if (current and CLOSE != 0 || current and SEALED != 0) break
            val next = (current and OPEN.inv()) or CLOSE
            if (mechanics.compareAndSet(current, next)) break
        }
        requestDrain()
    }

    private fun requestRefresh(additional: Int = 0) {
        while (true) {
            val current = mechanics.get()
            if (current and OPEN == 0 || current and SEALED != 0) return
            val next = current or DIRTY or additional
            if (next == current || mechanics.compareAndSet(current, next)) break
        }
        requestDrain()
    }

    private fun requestDrain() {
        while (true) {
            val current = mechanics.get()
            if (current and SEALED != 0 || current and ADMITTED != 0) return
            if (!mechanics.compareAndSet(current, current or ADMITTED)) continue
            break
        }
        try {
            executor.execute(drainRunnable)
        } catch (failure: RejectedExecutionException) {
            clearAdmitted()
            val state = mechanics.get()
            if (state and SEALED != 0 || executor.isShutdown) return
            // Queue capacity plus ADMITTED make this unreachable; terminate mechanically without caller callback.
            terminateOnWorker(failure)
        }
    }

    private fun drainOnWorker() {
        try {
            if (listenerProgress == ListenerProgress.Prepared && mechanics.get() and CLOSE == 0) {
                listenerProgress = ListenerProgress.RegistrationAttempted
                definition.displayManager.registerDisplayListener(listener, mainHandler)
                listenerProgress = ListenerProgress.Registered
            }
            while (mechanics.get() and CLOSE == 0) {
                val claimed = claimRefresh()
                if (claimed == 0) break
                refreshOnWorker(epochAtStart = claimed and EPOCH != 0)
            }
            if (mechanics.get() and CLOSE != 0) closeOnWorker()
        } catch (failure: Exception) {
            terminateOnWorker(failure)?.let { throw it }
        } catch (raw: Throwable) {
            failDirectly(raw)
        } finally {
            clearAdmitted()
            val state = mechanics.get()
            if (state and SEALED == 0 && state and (CLOSE or DIRTY) != 0) {
                requestDrain()
            }
        }
    }

    private fun claimRefresh(): Int {
        while (true) {
            val current = mechanics.get()
            if (current and (CLOSE or SEALED) != 0 || current and DIRTY == 0) return 0
            val claimed = current and (DIRTY or EPOCH)
            val next = current and (DIRTY or EPOCH).inv()
            if (mechanics.compareAndSet(current, next)) return claimed
        }
    }

    private fun clearAdmitted() {
        while (true) {
            val current = mechanics.get()
            if (current and ADMITTED == 0) return
            if (mechanics.compareAndSet(current, current and ADMITTED.inv())) return
        }
    }

    private fun refreshOnWorker(epochAtStart: Boolean) {
        if (epochAtStart) {
            retireEpoch()
            observer.onMetricsChanged(null)
            requestRefresh()
            return
        }

        val selected = selectedDisplay()
        if (selected == null || !selectionAvailable(selected)) {
            retireEpoch()
            observer.onMetricsChanged(null)
            return
        }
        if (epochDisplay !== selected) {
            retireEpoch()
            installEpoch(selected)
        }
        if (!selectionStillMatches(selected)) {
            retireEpoch()
            observer.onMetricsChanged(null)
            return
        }

        val candidate = readCompleteMetrics(selected)
        if (!selectionStillMatches(selected) || mechanics.get() and EPOCH != 0) {
            retireEpoch()
            observer.onMetricsChanged(null)
            requestRefresh()
            return
        }
        observer.onMetricsChanged(candidate)
    }

    private fun onDisplayBoundary(displayId: Int) {
        if (displayId == definition.selectedDisplayId) requestRefresh(EPOCH)
    }

    private fun selectedDisplay(): Display? =
        definition.fixedDisplay ?: definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionAvailable(display: Display): Boolean =
        definition.fixedDisplay?.let { display === it && fixedSelectionValid(it) } ?: display.isValid

    private fun selectionStillMatches(display: Display): Boolean =
        definition.fixedDisplay?.let { it === display && fixedSelectionValid(it) }
            ?: (display.isValid && definition.displayManager.getDisplay(Display.DEFAULT_DISPLAY) === display)

    /** The manager wrapper is association evidence only; all reads stay on the caller-supplied Display. */
    private fun fixedSelectionValid(fixed: Display): Boolean {
        if (fixed.displayId != definition.selectedDisplayId || !fixed.isValid) return false
        val managerEvidence = definition.displayManager.getDisplay(definition.selectedDisplayId) ?: return false
        return managerEvidence.displayId == definition.selectedDisplayId && managerEvidence.isValid
    }

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
        terminateOnWorker(primary = null)?.let { throw it }
    }

    /** One terminal order for normal close, ordinary failure, and direct fatal. */
    private fun terminateOnWorker(primary: Throwable?): Throwable? {
        if (!sealTerminal()) return primary
        val mustUnregister = claimUnregisterAttempt()

        var authoritative = primary
        try {
            executor.shutdown()
        } catch (raw: Throwable) {
            authoritative = retainFirst(authoritative, raw)
        }

        try {
            if (mustUnregister) definition.displayManager.unregisterDisplayListener(listener)
        } catch (raw: Throwable) {
            authoritative = retainFirst(authoritative, raw)
        }
        retireEpoch()

        var ordinaryReported = false
        if (authoritative is Exception && Thread.currentThread() === workerThread.get()) {
            authoritative = reportOrdinary(authoritative)
            ordinaryReported = true
        }
        return if (ordinaryReported && authoritative is Exception) null else authoritative
    }

    private fun sealTerminal(): Boolean {
        while (true) {
            val current = mechanics.get()
            if (current and SEALED != 0) return false
            val next = (current and (OPEN or DIRTY or EPOCH).inv()) or CLOSE or SEALED
            if (mechanics.compareAndSet(current, next)) return true
        }
    }

    private fun claimUnregisterAttempt(): Boolean = when (listenerProgress) {
        ListenerProgress.Prepared -> {
            listenerProgress = ListenerProgress.Closed
            false
        }

        ListenerProgress.RegistrationAttempted,
        ListenerProgress.Registered,
            -> {
                listenerProgress = ListenerProgress.Closed
                true
            }

        ListenerProgress.Closed -> false
    }

    private fun failDirectly(raw: Throwable): Nothing {
        val first = retainFirst(firstDirectFatal.get(), raw)
        terminateOnWorker(first)
        throw first
    }

    private fun reportOrdinary(failure: Exception): Throwable = try {
        observer.onFailure(failure)
        failure
    } catch (raw: Throwable) {
        retainFirst(failure, raw)
    }

    private fun retainFirst(first: Throwable?, next: Throwable): Throwable {
        if (first != null && first !is Exception) firstDirectFatal.compareAndSet(null, first)
        if (next !is Exception) firstDirectFatal.compareAndSet(null, next)
        val authoritative = firstDirectFatal.get() ?: first ?: next
        suppressDistinct(authoritative, first)
        suppressDistinct(authoritative, next)
        return authoritative
    }

    private fun suppressDistinct(authoritative: Throwable, candidate: Throwable?) {
        if (candidate == null || candidate === authoritative) return
        try {
            for (suppressed in authoritative.suppressed) {
                if (suppressed === candidate) return
            }
            authoritative.addSuppressed(candidate)
        } catch (_: Throwable) {
            // Retention failure cannot replace the authoritative throwable.
        }
    }

    private companion object {
        const val OPEN = 1 shl 0
        const val DIRTY = 1 shl 1
        const val EPOCH = 1 shl 2
        const val ADMITTED = 1 shl 3
        const val CLOSE = 1 shl 4
        const val SEALED = 1 shl 5
    }
}
