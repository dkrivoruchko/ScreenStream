package dev.dmkr.screencaptureengine

import android.app.Activity
import android.content.Context
import android.view.Display

/**
 * Factory methods for metric providers tied to Android UI surfaces.
 *
 * Caller-supplied [CaptureMetricsProvider] implementations can also be passed through
 * [ScreenCaptureConfig] and are observed per session.
 */
@Suppress("UNUSED_PARAMETER")
public object CaptureMetricsProviders {
    /**
     * Returns an activity/window-owned provider, or throws [UnsupportedOperationException] if
     * unavailable.
     */
    public fun fromActivity(activity: Activity): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /**
     * Returns a provider for metrics derived from a UI [Context], or throws
     * [UnsupportedOperationException] if unavailable.
     */
    public fun fromUiContext(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /**
     * Returns a provider for the given [Display], or throws [UnsupportedOperationException] if
     * unavailable.
     */
    public fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /**
     * Returns the best provider available for [context], or throws [UnsupportedOperationException] if
     * unavailable.
     */
    public fun bestEffort(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }
}
