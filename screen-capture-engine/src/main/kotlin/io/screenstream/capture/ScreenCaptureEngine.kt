package io.screenstream.capture

import android.content.Context
import android.os.Build
import io.screenstream.capture.internal.metrics.BuiltInCaptureMetricsSource
import io.screenstream.capture.internal.metrics.SessionMetricsSourceSelection
import io.screenstream.capture.internal.runtime.ProductionRuntime
import io.screenstream.capture.internal.session.SessionCoordinator

/**
 * Entry point for creating screen-capture sessions.
 *
 * Each session converts one caller-provided [android.media.projection.MediaProjection] authority into a best-effort
 * sequence of SDR JPEG frames. The host application remains responsible for obtaining fresh user consent, meeting
 * foreground-service and permission requirements, protecting copied frame data, and stopping capture when its own
 * lifecycle requires it. The engine declares and starts no application component.
 */
public object ScreenCaptureEngine {
    /**
     * Creates a fresh, initially idle capture session.
     *
     * This function may be called from any thread. It performs no capture operation and does not start capture;
     * observing any of the returned session's flows also does not start capture. The new session initially reports
     * [ScreenCaptureState.NotStarted] and zero-valued statistics.
     *
     * If [ScreenCaptureConfig.captureMetricsSource] is `null`, [context] is normalized to its application context
     * and used for a source that follows the current default display. If a source is supplied, its exact identity is
     * retained and [context] is not accessed, forwarded, or retained.
     *
     * @param context context used only to construct the default display metrics source when one is not configured.
     * @param config read-only session configuration. Its property references do not change, but a configured metrics
     * source may be stateful and is retained by identity. The default follows the default display and selects the
     * JPEG backend automatically.
     * @return a new identity-based session that can be started at most once.
     * @throws IllegalArgumentException if the default metrics source cannot obtain a usable application context or
     * display service.
     */
    public fun createSession(
        context: Context,
        config: ScreenCaptureConfig = ScreenCaptureConfig(),
    ): ScreenCaptureSession {
        val configuredMetricsSource = config.captureMetricsSource
        val metricsSourceSelection = if (configuredMetricsSource != null) {
            SessionMetricsSourceSelection.Explicit(configuredMetricsSource)
        } else {
            SessionMetricsSourceSelection.AbsentConfigDefault(
                BuiltInCaptureMetricsSource.forDefaultDisplay(context, ProductionRuntime.workerDispatcher),
            )
        }
        return ScreenCaptureSession.create(
            SessionCoordinator(
                metricsSourceSelection = metricsSourceSelection,
                jpegBackendPolicy = config.jpegBackendPolicy,
                workerDispatcher = ProductionRuntime.workerDispatcher,
                handlerThreadPlatform = ProductionRuntime.handlerThreadPlatform,
                handlerTaskPoster = ProductionRuntime.handlerTaskPoster,
                delayedEntryScheduler = ProductionRuntime.delayedEntryScheduler,
                executionClock = ProductionRuntime.elapsedRealtimeClock,
                currentEpochMillis = ProductionRuntime.currentEpochMillis,
                platformSdkInt = Build.VERSION.SDK_INT,
            ),
        )
    }
}
