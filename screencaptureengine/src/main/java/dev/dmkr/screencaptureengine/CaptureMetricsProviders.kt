package dev.dmkr.screencaptureengine

import android.app.Activity
import android.content.Context
import android.view.Display
import dev.dmkr.screencaptureengine.CaptureMetricsProviders.bestEffort
import dev.dmkr.screencaptureengine.internal.platform.metrics.AndroidCaptureMetricsProvider

/**
 * Factory methods for metrics providers tied to Android UI surfaces.
 *
 * Built-in providers are lazy, non-throwing convenience sources for bootstrap size, density, and explicit metrics availability through
 * [CaptureMetricsState]. They emit latest-state metrics only; unavailable startup metrics fail startup when possible, while unavailable running metrics are
 * ignored by the engine and the last valid metrics remain in use.
 *
 * Provider construction performs only a synchronous best-current snapshot read. Platform listeners are attached only while a session observes the provider and
 * are detached after the last observation is disposed.
 */
public object CaptureMetricsProviders {
    /**
     * Returns an Activity/window-owned provider using the current Activity window bounds when available.
     *
     * This provider may strongly retain [activity]. Do not keep it as an application-lifetime object. Fallbacks are limited to the same Activity/window, such as
     * attached decor-view bounds; it does not fall back to application or default-display metrics.
     */
    public fun fromActivity(activity: Activity): CaptureMetricsProvider {
        return AndroidCaptureMetricsProvider.fromActivity(activity)
    }

    /**
     * Returns a provider for metrics derived from a UI or window-associated [Context].
     *
     * UI/window-associated contexts use current window-context bounds when available. Non-UI contexts delegate to [bestEffort] and may have lower precision,
     * especially in multi-window, desktop, or external-display environments.
     */
    public fun fromUiContext(context: Context): CaptureMetricsProvider {
        return AndroidCaptureMetricsProvider.fromUiContext(context)
    }

    /**
     * Returns a provider bound to [display].
     *
     * The provider uses maximum/display-area bounds for the requested display when available and may fall back to resources from a context bound to that same
     * display. Removed or unusable displays become unavailable instead of falling back to an unrelated default display.
     */
    public fun fromDisplay(baseContext: Context, display: Display): CaptureMetricsProvider {
        return AndroidCaptureMetricsProvider.fromDisplay(baseContext, display)
    }

    /**
     * Returns a best-effort provider for service, application, or default integration.
     *
     * This source prefers maximum/default-display window metrics and falls back to application/resource metrics. It is less precise than Activity or explicit
     * display sources in multi-window, desktop, foldable, or external-display scenarios.
     */
    public fun bestEffort(context: Context): CaptureMetricsProvider {
        return AndroidCaptureMetricsProvider.bestEffort(context)
    }
}
