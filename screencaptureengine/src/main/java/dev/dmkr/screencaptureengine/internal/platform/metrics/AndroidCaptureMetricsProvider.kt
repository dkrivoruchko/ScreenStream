package dev.dmkr.screencaptureengine.internal.platform.metrics

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.window.layout.WindowMetrics
import androidx.window.layout.WindowMetricsCalculator
import dev.dmkr.screencaptureengine.CaptureMetrics
import dev.dmkr.screencaptureengine.CaptureMetricsProvider
import dev.dmkr.screencaptureengine.CaptureMetricsState
import dev.dmkr.screencaptureengine.CaptureMetricsUnavailableReason
import dev.dmkr.screencaptureengine.EngineAttachableCaptureMetricsProvider
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

internal class AndroidCaptureMetricsProvider internal constructor(
    private val source: MetricsSource,
) : EngineAttachableCaptureMetricsProvider {
    private val lock = Any()
    private val callbacks = LinkedHashMap<Int, () -> Unit>()
    private val mutableMetrics = MutableStateFlow(source.read())
    private var nextCallbackId = 0
    private var sourceAttachment: DisposableHandle? = null
    private var sourceAttachmentState = SourceAttachmentState.Detached
    private var sourceAttachAttempt = 0
    private var failedSourceAttachAttempt = 0
    private var failedSourceAttachCause: Throwable? = null

    override val metrics: StateFlow<CaptureMetricsState> = mutableMetrics

    override fun attachSessionAttachment(onMetricsChanged: () -> Unit): DisposableHandle {
        val callbackId: Int
        val attachAttempt: Int
        var shouldAttachSource = false
        synchronized(lock) {
            callbackId = nextCallbackId++
            callbacks[callbackId] = onMetricsChanged
            when (sourceAttachmentState) {
                SourceAttachmentState.Detached -> {
                    sourceAttachmentState = SourceAttachmentState.Attaching
                    sourceAttachAttempt++
                    attachAttempt = sourceAttachAttempt
                    failedSourceAttachCause = null
                    shouldAttachSource = true
                }

                SourceAttachmentState.Attaching -> {
                    attachAttempt = sourceAttachAttempt
                }

                SourceAttachmentState.Attached -> {
                    attachAttempt = sourceAttachAttempt
                }
            }
        }
        try {
            if (shouldAttachSource) {
                attachSource(callbackId = callbackId, attachAttempt = attachAttempt)
            } else {
                awaitSourceAttached(callbackId = callbackId, attachAttempt = attachAttempt)
            }
            refresh()
            return DisposableHandle { detach(callbackId) }
        } catch (cause: Throwable) {
            rollbackCallback(callbackId = callbackId, cause = cause)
            throw cause
        }
    }

    private fun attachSource(callbackId: Int, attachAttempt: Int) {
        val attachment = try {
            source.attach(::refresh)
        } catch (cause: Throwable) {
            failSourceAttachAttempt(attachAttempt = attachAttempt, cause = cause)
            throw cause
        }
        var staleAttachment: DisposableHandle? = null
        synchronized(lock) {
            if (sourceAttachmentState == SourceAttachmentState.Attaching && sourceAttachAttempt == attachAttempt && callbacks.isNotEmpty()) {
                sourceAttachment = attachment
                sourceAttachmentState = SourceAttachmentState.Attached
                lock.notifyAllMonitor()
            } else {
                staleAttachment = attachment
                if (sourceAttachmentState == SourceAttachmentState.Attaching && sourceAttachAttempt == attachAttempt) {
                    sourceAttachmentState = SourceAttachmentState.Detached
                    lock.notifyAllMonitor()
                }
            }
        }
        disposeSuppressing(primary = null, handle = staleAttachment)
        awaitSourceAttached(callbackId = callbackId, attachAttempt = attachAttempt)
    }

    private fun awaitSourceAttached(callbackId: Int, attachAttempt: Int) {
        synchronized(lock) {
            while (
                callbacks.containsKey(callbackId) &&
                sourceAttachmentState == SourceAttachmentState.Attaching &&
                sourceAttachAttempt == attachAttempt
            ) {
                lock.waitOnMonitor()
            }
            if (!callbacks.containsKey(callbackId)) {
                failedSourceAttachCause?.takeIf { failedSourceAttachAttempt == attachAttempt }?.let { throw it }
                error("Metrics provider attachment was removed before source attachment completed.")
            }
            if (failedSourceAttachAttempt == attachAttempt && sourceAttachmentState == SourceAttachmentState.Detached) {
                throw checkNotNull(failedSourceAttachCause) { "Missing source attachment failure." }
            }
        }
    }

    private fun failSourceAttachAttempt(attachAttempt: Int, cause: Throwable) {
        synchronized(lock) {
            if (sourceAttachmentState == SourceAttachmentState.Attaching && sourceAttachAttempt == attachAttempt) {
                callbacks.clear()
                sourceAttachmentState = SourceAttachmentState.Detached
                failedSourceAttachAttempt = attachAttempt
                failedSourceAttachCause = cause
                lock.notifyAllMonitor()
            }
        }
    }

    private fun detach(callbackId: Int) {
        val attachment = synchronized(lock) {
            callbacks.remove(callbackId)
            if (callbacks.isEmpty() && sourceAttachmentState == SourceAttachmentState.Attached) {
                val detached = sourceAttachment
                sourceAttachment = null
                sourceAttachmentState = SourceAttachmentState.Detached
                detached
            } else {
                null
            }
        }
        attachment?.dispose()
    }

    private fun rollbackCallback(
        callbackId: Int,
        cause: Throwable,
    ) {
        val sharedAttachmentToDispose = synchronized(lock) {
            callbacks.remove(callbackId)
            if (callbacks.isEmpty() && sourceAttachmentState == SourceAttachmentState.Attached) {
                val detached = sourceAttachment
                sourceAttachment = null
                sourceAttachmentState = SourceAttachmentState.Detached
                detached
            } else {
                null
            }
        }
        disposeSuppressing(primary = cause, handle = sharedAttachmentToDispose)
    }

    private fun disposeSuppressing(primary: Throwable?, handle: DisposableHandle?) {
        if (handle == null) return
        try {
            handle.dispose()
        } catch (cleanupFailure: Throwable) {
            if (primary == null) {
                throw cleanupFailure
            }
            if (cleanupFailure !== primary) {
                primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private enum class SourceAttachmentState {
        Detached,
        Attaching,
        Attached,
    }

    private fun refresh() {
        val state = source.read()
        val callbacksSnapshot = synchronized(lock) {
            if (sameState(mutableMetrics.value, state)) {
                emptyList()
            } else {
                mutableMetrics.value = state
                callbacks.values.toList()
            }
        }
        callbacksSnapshot.forEach { callback -> callback() }
    }

    private fun sameState(left: CaptureMetricsState, right: CaptureMetricsState): Boolean {
        return when (left) {
            is CaptureMetricsState.Available -> right is CaptureMetricsState.Available && left.metrics == right.metrics
            is CaptureMetricsState.Unavailable -> {
                right is CaptureMetricsState.Unavailable &&
                        left.reason == right.reason &&
                        left.message == right.message
            }
        }
    }

    internal companion object {
        internal fun fromActivity(activity: Activity): CaptureMetricsProvider {
            return AndroidCaptureMetricsProvider(ActivityMetricsSource(activity))
        }

        internal fun fromUiContext(context: Context): CaptureMetricsProvider {
            val activity = context.findActivity()
            if (activity != null) {
                return fromActivity(activity)
            }
            if (!context.isConfidentUiContext()) {
                return bestEffort(context)
            }
            return AndroidCaptureMetricsProvider(UiContextMetricsSource(context))
        }

        internal fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
            return AndroidCaptureMetricsProvider(DisplayBoundMetricsSource(baseContext.applicationContext ?: baseContext, display))
        }

        internal fun bestEffort(context: Context): CaptureMetricsProvider {
            return AndroidCaptureMetricsProvider(BestEffortMetricsSource(context.applicationContext ?: context))
        }
    }
}

internal interface MetricsSource {
    fun read(): CaptureMetricsState

    fun attach(onChanged: () -> Unit): DisposableHandle
}

private class ActivityMetricsSource(
    private val activity: Activity,
) : MetricsSource {
    @Volatile
    private var destroyed = activity.isDestroyed

    override fun read(): CaptureMetricsState {
        if (destroyed || activity.isDestroyed) {
            return unavailable(CaptureMetricsUnavailableReason.SourceNoLongerAvailable, "Activity is destroyed.")
        }
        val calculatorMetrics = runCatching {
            WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).toCaptureMetrics()
        }.getOrNull()
        if (calculatorMetrics != null) {
            return CaptureMetricsState.Available(calculatorMetrics)
        }
        val decorMetrics = activity.window?.decorView?.attachedViewMetrics(activity)
        return if (decorMetrics != null) {
            CaptureMetricsState.Available(decorMetrics)
        } else {
            unavailable(CaptureMetricsUnavailableReason.SourceNotReady, "Activity window metrics are not ready.")
        }
    }

    override fun attach(onChanged: () -> Unit): DisposableHandle {
        return attachAll(
            {
                registerActivityDestroyedCallback(activity) {
                    destroyed = true
                    onChanged()
                }
            },
            { registerComponentCallbacks(activity, onChanged) },
            { registerDisplayListener(activity, activity.displayIdOrNull(), onChanged) },
        )
    }
}

private class UiContextMetricsSource(
    private val context: Context,
) : MetricsSource {
    override fun read(): CaptureMetricsState {
        val metrics = runCatching {
            WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context).toCaptureMetrics()
        }.getOrNull()
        return if (metrics != null) {
            CaptureMetricsState.Available(metrics)
        } else {
            unavailable(CaptureMetricsUnavailableReason.SourceNotReady, "UI context window metrics are not ready.")
        }
    }

    override fun attach(onChanged: () -> Unit): DisposableHandle {
        return attachAll(
            { registerComponentCallbacks(context, onChanged) },
            { registerDisplayListener(context, context.displayIdOrNull(), onChanged) },
        )
    }
}

private class DisplayBoundMetricsSource(
    private val baseContext: Context,
    display: Display,
) : MetricsSource {
    private val displayId = display.displayId

    override fun read(): CaptureMetricsState {
        val currentDisplay = displayManager(baseContext)?.getDisplay(displayId)
            ?: return unavailable(CaptureMetricsUnavailableReason.SourceNoLongerAvailable, "Display is no longer available.")
        if (!currentDisplay.isValid) {
            return unavailable(CaptureMetricsUnavailableReason.SourceNoLongerAvailable, "Display is no longer valid.")
        }

        val displayContext = runCatching { baseContext.createDisplayContext(currentDisplay) }.getOrNull()
            ?: return unavailable(CaptureMetricsUnavailableReason.SourceNoLongerAvailable, "Display context is unavailable.")
        val windowMetrics = displayContext.displayWindowContextOrNull()?.let { windowContext ->
            runCatching {
                WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(windowContext).toCaptureMetrics()
            }.getOrNull()
        }
        if (windowMetrics != null) {
            return CaptureMetricsState.Available(windowMetrics)
        }
        val resourceMetrics = displayContext.resources.displayMetrics.toCaptureMetricsOrNull()
        return if (resourceMetrics != null) {
            CaptureMetricsState.Available(resourceMetrics)
        } else {
            unavailable(CaptureMetricsUnavailableReason.SourceInvalid, "Display-bound resource metrics are invalid.")
        }
    }

    override fun attach(onChanged: () -> Unit): DisposableHandle {
        return registerDisplayListener(baseContext, displayId, onChanged) ?: NoOpDisposableHandle
    }
}

private class BestEffortMetricsSource(
    private val context: Context,
) : MetricsSource {
    override fun read(): CaptureMetricsState {
        val metrics = runCatching {
            WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).toCaptureMetrics()
        }.getOrNull()
        if (metrics != null) {
            return CaptureMetricsState.Available(metrics)
        }
        val resourceMetrics = context.resources.displayMetrics.toCaptureMetricsOrNull()
        return if (resourceMetrics != null) {
            CaptureMetricsState.Available(resourceMetrics)
        } else {
            unavailable(CaptureMetricsUnavailableReason.SourceNotReady, "Best-effort resource metrics are not ready.")
        }
    }

    override fun attach(onChanged: () -> Unit): DisposableHandle {
        return attachAll(
            { registerComponentCallbacks(context, onChanged) },
            { registerDisplayListener(context, context.displayIdOrNull(), onChanged) },
        )
    }
}

internal fun attachAll(
    vararg registrations: () -> DisposableHandle?,
): DisposableHandle {
    val handles = mutableListOf<DisposableHandle>()
    try {
        registrations.forEach { registration ->
            registration()?.let(handles::add)
        }
        return CompositeDisposableHandle(handles)
    } catch (cause: Throwable) {
        disposeHandles(handles.asReversed(), primary = cause)
        throw cause
    }
}

internal class CompositeDisposableHandle(
    private val handles: List<DisposableHandle>,
) : DisposableHandle {
    private var disposed = false

    override fun dispose() {
        if (disposed) return
        disposed = true
        disposeHandles(handles, primary = null)
    }
}

private fun disposeHandles(handles: List<DisposableHandle>, primary: Throwable?) {
    var failure = primary
    handles.forEach { handle ->
        try {
            handle.dispose()
        } catch (disposeFailure: Throwable) {
            val currentFailure = failure
            if (currentFailure == null) {
                failure = disposeFailure
            } else if (disposeFailure !== currentFailure) {
                currentFailure.addSuppressed(disposeFailure)
            }
        }
    }
    if (primary == null) {
        failure?.let { throw it }
    }
}

private object NoOpDisposableHandle : DisposableHandle {
    override fun dispose() = Unit
}

private fun WindowMetrics.toCaptureMetrics(): CaptureMetrics? {
    val bounds = bounds
    val densityDpi = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
    return captureMetricsOrNull(widthPx = bounds.width(), heightPx = bounds.height(), densityDpi = densityDpi)
}

private fun DisplayMetrics.toCaptureMetricsOrNull(): CaptureMetrics? {
    val density = if (densityDpi > 0) densityDpi else (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
    return captureMetricsOrNull(widthPx = widthPixels, heightPx = heightPixels, densityDpi = density)
}

private fun captureMetricsOrNull(widthPx: Int, heightPx: Int, densityDpi: Int): CaptureMetrics? {
    return if (widthPx > 0 && heightPx > 0 && densityDpi > 0) {
        CaptureMetrics(widthPx = widthPx, heightPx = heightPx, densityDpi = densityDpi)
    } else {
        null
    }
}

private fun View.attachedViewMetrics(context: Context): CaptureMetrics? {
    if (!isAttachedToWindow || width <= 0 || height <= 0) {
        return null
    }
    return captureMetricsOrNull(widthPx = width, heightPx = height, densityDpi = context.resources.displayMetrics.densityDpi)
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) {
            return current
        }
        current = current.baseContext
    }
    return null
}

private fun Context.isConfidentUiContext(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return isUiContext
    }
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasApi30WindowContextSignals()
}

private fun Context.hasApi30WindowContextSignals(): Boolean {
    if (isApplicationContextOrDirectWrapper()) {
        return false
    }
    if (displayIdOrNull() == null || windowManagerOrNull() == null) {
        return false
    }
    return runCatching {
        WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).toCaptureMetrics() != null
    }.getOrDefault(false)
}

private fun Context.windowManagerOrNull(): WindowManager? {
    return runCatching { getSystemService(Context.WINDOW_SERVICE) as? WindowManager }.getOrNull()
}

private fun Context.isApplicationContextOrDirectWrapper(): Boolean {
    val applicationContext = applicationContextOrNull() ?: return false
    return this === applicationContext || (this is ContextWrapper && baseContext === applicationContext)
}

private fun Context.applicationContextOrNull(): Context? {
    return runCatching { applicationContext }.getOrNull()
}

private fun Context.displayIdOrNull(): Int? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { display.displayId }.getOrNull()
    } else {
        null
    }
}

private fun Activity.displayIdOrNull(): Int? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { display.displayId }.getOrNull()
    } else {
        null
    }
}

private fun Context.displayWindowContextOrNull(): Context? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null) }.getOrNull()
    } else {
        null
    }
}

private fun registerComponentCallbacks(context: Context, onChanged: () -> Unit): DisposableHandle {
    val callback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            onChanged()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onLowMemory() = Unit
    }
    context.registerComponentCallbacks(callback)
    return DisposableHandle { context.unregisterComponentCallbacks(callback) }
}

private fun registerActivityDestroyedCallback(activity: Activity, onDestroyed: () -> Unit): DisposableHandle {
    val application = activity.application
    val callback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(destroyedActivity: Activity) {
            if (destroyedActivity === activity) {
                onDestroyed()
            }
        }
    }
    application.registerActivityLifecycleCallbacks(callback)
    return DisposableHandle { application.unregisterActivityLifecycleCallbacks(callback) }
}

private fun registerDisplayListener(context: Context, displayId: Int?, onChanged: () -> Unit): DisposableHandle? {
    val manager = displayManager(context) ?: return null
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(addedDisplayId: Int) {
            if (displayId == null || addedDisplayId == displayId) {
                onChanged()
            }
        }

        override fun onDisplayRemoved(removedDisplayId: Int) {
            if (displayId == null || removedDisplayId == displayId) {
                onChanged()
            }
        }

        override fun onDisplayChanged(changedDisplayId: Int) {
            if (displayId == null || changedDisplayId == displayId) {
                onChanged()
            }
        }
    }
    manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    return DisposableHandle { manager.unregisterDisplayListener(listener) }
}

private fun displayManager(context: Context): DisplayManager? {
    return context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
}

private fun unavailable(reason: CaptureMetricsUnavailableReason, message: String): CaptureMetricsState {
    return CaptureMetricsState.Unavailable(reason = reason, message = message)
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
private fun Any.waitOnMonitor() {
    (this as java.lang.Object).wait()
}

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "RemoveRedundantQualifierName")
private fun Any.notifyAllMonitor() {
    (this as java.lang.Object).notifyAll()
}
