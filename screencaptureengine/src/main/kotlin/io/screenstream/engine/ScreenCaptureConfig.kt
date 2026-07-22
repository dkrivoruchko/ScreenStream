package io.screenstream.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import io.screenstream.engine.internal.android.subscribeToBuiltInCaptureMetrics

public class ScreenCaptureConfig(
    public val captureMetricsSource: CaptureMetricsSource? = null,
    public val jpegBackendPolicy: JpegBackendPolicy = JpegBackendPolicy.Auto,
)

public enum class JpegBackendPolicy {
    Auto,
    FrameworkOnly,
}

public fun interface CaptureMetricsSource {
    public fun subscribe(observer: CaptureMetricsObserver): CaptureMetricsSubscription
}

public interface CaptureMetricsObserver {
    public fun onMetricsChanged(metrics: CaptureMetrics?)

    public fun onComplete()

    public fun onFailure(cause: Throwable)
}

public fun interface CaptureMetricsSubscription {
    public fun close()
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

public object CaptureMetricsSources {
    public fun fromDisplay(context: Context, display: Display): CaptureMetricsSource =
        BuiltInCaptureMetricsDefinition(context = context, fixedDisplay = display)
}

internal class BuiltInCaptureMetricsDefinition(
    context: Context,
    internal val fixedDisplay: Display? = null,
) : CaptureMetricsSource {
    internal val applicationContext: Context =
        requireNotNull(context.applicationContext) { "context.applicationContext must be available" }
    internal val displayManager: DisplayManager =
        requireNotNull(applicationContext.getSystemService(DisplayManager::class.java)) {
            "DisplayManager must be available"
        }
    internal val selectedDisplayId: Int = fixedDisplay?.displayId ?: Display.DEFAULT_DISPLAY

    init {
        if (fixedDisplay != null) {
            require(fixedDisplay.isValid) { "display must be valid" }
            require(displayManager.getDisplay(selectedDisplayId) != null) {
                "display must be associated with the application DisplayManager"
            }
        }
    }

    override fun subscribe(observer: CaptureMetricsObserver): CaptureMetricsSubscription =
        subscribeToBuiltInCaptureMetrics(this, observer)
}
