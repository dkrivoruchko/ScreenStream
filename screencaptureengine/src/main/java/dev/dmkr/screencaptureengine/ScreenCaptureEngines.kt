package dev.dmkr.screencaptureengine

import android.content.Context
import dev.dmkr.screencaptureengine.internal.DefaultScreenCaptureEngine

/** Factory API for creating screen-capture engines. */
public object ScreenCaptureEngines {
    /**
     * Creates a default [ScreenCaptureEngine] bound to [context]'s application context.
     *
     * Creation is lazy: capture startup, metrics observation, projection callback registration,
     * projection consumption, GL allocation, encoder allocation, and virtual-display creation only
     * happen when [ScreenCaptureEngine.startSession] is called.
     *
     * Session startup uses the caller-supplied [ScreenCaptureConfig.metricsProvider]. The built-in
     * [CaptureMetricsProviders] factory methods may throw when the requested metrics source is not
     * available. The returned engine has no public close API; lifecycle is controlled through
     * returned [ScreenCaptureSession] instances.
     */
    public fun create(context: Context): ScreenCaptureEngine {
        val applicationContext = requireNotNull(context.applicationContext) {
            "Context.applicationContext must be available."
        }
        return DefaultScreenCaptureEngine(applicationContext)
    }
}
