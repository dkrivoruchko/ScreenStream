package io.screenstream.engine

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

public class ScreenCaptureConfig(
    public val captureMetricsProvider: CaptureMetricsProvider? = null,
    public val frameCallbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
    public val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
)

public enum class JpegBackendPolicy {
    Auto,
    FrameworkOnly,
}

public fun interface CaptureMetricsProvider {
    public fun observe(): Flow<CaptureMetrics?>
}

public class CaptureMetrics(
    public val widthPx: Int,
    public val heightPx: Int,
    public val densityDpi: Int,
) {
    init {
        require(widthPx > 0) { "widthPx must be positive" }
        require(heightPx > 0) { "heightPx must be positive" }
        require(densityDpi > 0) { "densityDpi must be positive" }
    }

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptureMetrics) return false

        return widthPx == other.widthPx && heightPx == other.heightPx && densityDpi == other.densityDpi
    }

    public override fun hashCode(): Int {
        var result: Int = widthPx.hashCode()
        result = 31 * result + heightPx.hashCode()
        result = 31 * result + densityDpi.hashCode()
        return result
    }

    public override fun toString(): String =
        "CaptureMetrics(widthPx=$widthPx, heightPx=$heightPx, densityDpi=$densityDpi)"
}

public object CaptureMetricsProviders {
    public fun fromDisplay(context: Context, display: Display): CaptureMetricsProvider =
        BuiltInCaptureMetricsDefinition(context = context, fixedDisplay = display)
}

internal class BuiltInCaptureMetricsDefinition(
    context: Context,
    private val fixedDisplay: Display? = null,
) : CaptureMetricsProvider {
    private val applicationContext: Context =
        requireNotNull(context.applicationContext) { "context.applicationContext must be available" }
    private val displayManager: DisplayManager =
        requireNotNull(applicationContext.getSystemService(DisplayManager::class.java)) {
            "DisplayManager must be available"
        }
    private val selectedDisplayId: Int = fixedDisplay?.displayId ?: Display.DEFAULT_DISPLAY

    init {
        if (fixedDisplay != null) {
            require(fixedDisplay.isValid) { "display must be valid" }
            require(displayManager.getDisplay(selectedDisplayId) != null) { "display must be associated with the application DisplayManager" }
        }
    }

    override fun observe(): Flow<CaptureMetrics?> = flow {
        val collectionState = BuiltInCaptureMetricsCollectionState(
            applicationContext = applicationContext,
            displayManager = displayManager,
            fixedDisplay = fixedDisplay,
            selectedDisplayId = selectedDisplayId,
        )
        var collectionFailure: Throwable? = null

        try {
            displayManager.registerDisplayListener(collectionState.listener, processMainCallbackHandler)
            collectionState.refreshSignals.trySend(Unit)

            while (collectionState.refreshSignals.receiveCatching().isSuccess) {
                collectionState.processRefresh { metrics -> emit(metrics) }
            }
        } catch (failure: Throwable) {
            collectionFailure = failure
            throw failure
        } finally {
            collectionState.closeForCleanup()

            try {
                displayManager.unregisterDisplayListener(collectionState.listener)
            } catch (unregisterFailure: Throwable) {
                val primaryFailure = collectionFailure ?: throw unregisterFailure
                if (unregisterFailure !== primaryFailure) {
                    primaryFailure.addSuppressed(unregisterFailure)
                }
            }
        }
    }
}

private class BuiltInCaptureMetricsCollectionState(
    private val applicationContext: Context,
    private val displayManager: DisplayManager,
    private val fixedDisplay: Display?,
    private val selectedDisplayId: Int,
) {
    val refreshSignals: Channel<Unit> = Channel(Channel.CONFLATED)
    val listener: DisplayManager.DisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            signalBoundary(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (callbacksAccepted.get() && displayId == selectedDisplayId) {
                refreshSignals.trySend(Unit)
            }
        }
    }

    private val callbacksAccepted = AtomicBoolean(true)
    private val epochInvalidated = AtomicBoolean(false)
    private val reusableRealSizePoint = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) Point() else null

    private var currentEpochDisplay: Display? = null
    private var currentEpochWindowContext: Context? = null
    private var currentEpochWindowManager: WindowManager? = null
    private var hasPublished = false
    private var latestMetrics: CaptureMetrics? = null

    suspend fun processRefresh(publishMetrics: suspend (CaptureMetrics?) -> Unit) {
        if (epochInvalidated.getAndSet(false)) {
            retireCurrentEpoch()
            publishIfChanged(metrics = null, publishMetrics = publishMetrics)
            refreshSignals.trySend(Unit)
            return
        }

        val selectedDisplay = resolveSelectedDisplay()
        if (selectedDisplay == null || !selectedDisplay.isValid) {
            val hadEpoch = currentEpochDisplay != null
            retireCurrentEpoch()
            val unavailableChanged = publishIfChanged(metrics = null, publishMetrics = publishMetrics)
            if (hadEpoch || unavailableChanged) {
                refreshSignals.trySend(Unit)
            }
            return
        }

        val existingEpochDisplay = currentEpochDisplay
        val existingEpochMatchesSelection = when {
            existingEpochDisplay == null -> false
            !existingEpochDisplay.isValid -> false
            fixedDisplay != null -> existingEpochDisplay === selectedDisplay
            else -> existingEpochDisplay.displayId == selectedDisplay.displayId
        }

        if (!existingEpochMatchesSelection) {
            retireCurrentEpoch()
            installDisplayEpoch(selectedDisplay)
        }

        val epochDisplay = checkNotNull(currentEpochDisplay)
        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid) {
            val hadEpoch = currentEpochDisplay != null
            retireCurrentEpoch()
            val unavailableChanged = publishIfChanged(metrics = null, publishMetrics = publishMetrics)
            if (hadEpoch || unavailableChanged) {
                refreshSignals.trySend(Unit)
            }
            return
        }

        val metricsCandidate = readCompleteMetrics(epochDisplay)

        if (!selectionStillMatches(epochDisplay) || !epochDisplay.isValid || epochInvalidated.get()) {
            retireCurrentEpoch()
            publishIfChanged(metrics = null, publishMetrics = publishMetrics)
            refreshSignals.trySend(Unit)
            return
        }

        publishIfChanged(metrics = metricsCandidate, publishMetrics = publishMetrics)
    }

    fun closeForCleanup() {
        callbacksAccepted.set(false)
        refreshSignals.close()
        retireCurrentEpoch()
    }

    private fun signalBoundary(displayId: Int) {
        if (callbacksAccepted.get() && displayId == selectedDisplayId) {
            epochInvalidated.set(true)
            refreshSignals.trySend(Unit)
        }
    }

    private fun resolveSelectedDisplay(): Display? = fixedDisplay ?: displayManager.getDisplay(Display.DEFAULT_DISPLAY)

    private fun selectionStillMatches(epochDisplay: Display): Boolean =
        if (fixedDisplay != null) {
            epochDisplay === fixedDisplay
        } else {
            val currentDefaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            currentDefaultDisplay != null &&
                    currentDefaultDisplay.isValid &&
                    currentDefaultDisplay.displayId == epochDisplay.displayId
        }

    private fun installDisplayEpoch(epochDisplay: Display) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowContext =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    applicationContext
                        .createWindowContext(epochDisplay, WindowManager.LayoutParams.TYPE_APPLICATION, null)
                } else {
                    applicationContext.createDisplayContext(epochDisplay)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
                }
            val windowManager = requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
                "WindowManager must be available for the selected display"
            }

            currentEpochWindowContext = windowContext
            currentEpochWindowManager = windowManager
        }

        currentEpochDisplay = epochDisplay
    }

    @Suppress("DEPRECATION")
    private fun readCompleteMetrics(epochDisplay: Display): CaptureMetrics? {
        val widthPx: Int
        val heightPx: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = checkNotNull(currentEpochWindowManager).maximumWindowMetrics.bounds
            widthPx = bounds.width()
            heightPx = bounds.height()
        } else {
            val realSizePoint = checkNotNull(reusableRealSizePoint)
            epochDisplay.getRealSize(realSizePoint)
            widthPx = realSizePoint.x
            heightPx = realSizePoint.y
        }

        val densityDpi = applicationContext.createDisplayContext(epochDisplay).resources.configuration.densityDpi

        return if (widthPx > 0 && heightPx > 0 && densityDpi > 0) {
            CaptureMetrics(widthPx = widthPx, heightPx = heightPx, densityDpi = densityDpi)
        } else {
            null
        }
    }

    private suspend fun publishIfChanged(
        metrics: CaptureMetrics?,
        publishMetrics: suspend (CaptureMetrics?) -> Unit,
    ): Boolean {
        if (hasPublished && latestMetrics == metrics) return false

        publishMetrics(metrics)
        latestMetrics = metrics
        hasPublished = true
        return true
    }

    private fun retireCurrentEpoch() {
        currentEpochDisplay = null
        currentEpochWindowContext = null
        currentEpochWindowManager = null
    }
}

private val processMainCallbackHandler = Handler(Looper.getMainLooper())
