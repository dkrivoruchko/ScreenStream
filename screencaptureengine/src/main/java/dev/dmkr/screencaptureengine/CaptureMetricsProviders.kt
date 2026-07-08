package dev.dmkr.screencaptureengine

import android.app.Activity
import android.content.Context
import android.view.Display

/**
 * Metric-provider factory API for common Android integration points.
 *
 * These public factory slots are reserved for built-in Android metrics providers. The current
 * production implementation does not provide those built-in providers, so each factory throws
 * [UnsupportedOperationException]. Caller-supplied [CaptureMetricsProvider] implementations remain
 * supported and are observed per session.
 */
@Suppress("UNUSED_PARAMETER")
public object CaptureMetricsProviders {
    /** Placeholder for an activity/window-owned provider; currently throws [UnsupportedOperationException]. */
    public fun fromActivity(activity: Activity): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a UI-context provider; currently throws [UnsupportedOperationException]. */
    public fun fromUiContext(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a display-specific provider; currently throws [UnsupportedOperationException]. */
    public fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }

    /** Placeholder for a best-effort provider; currently throws [UnsupportedOperationException]. */
    public fun bestEffort(context: Context): CaptureMetricsProvider {
        throw UnsupportedOperationException("CaptureMetricsProviders runtime behavior is unavailable")
    }
}
