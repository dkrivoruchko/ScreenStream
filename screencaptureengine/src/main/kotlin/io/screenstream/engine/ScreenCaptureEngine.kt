package io.screenstream.engine

import android.content.Context
import io.screenstream.engine.internal.metrics.defaultDisplayCaptureMetricsSource
import io.screenstream.engine.internal.runtime.SessionExecutionComposition
import io.screenstream.engine.internal.session.SessionFrontDoor

public object ScreenCaptureEngine {
    public fun create(
        context: Context,
        config: ScreenCaptureConfig = ScreenCaptureConfig(),
    ): ScreenCaptureSession = createWithComposition(
        context = context,
        config = config,
        composition = SessionExecutionComposition.production(),
    )

    @JvmSynthetic
    internal fun createWithComposition(
        context: Context,
        config: ScreenCaptureConfig,
        composition: SessionExecutionComposition,
    ): ScreenCaptureSession {
        val applicationContext = requireNotNull(context.applicationContext) {
            "context.applicationContext must be available"
        }
        val metricsSource = config.captureMetricsSource ?: defaultDisplayCaptureMetricsSource(applicationContext)
        return ScreenCaptureSession.create(
            SessionFrontDoor(
                applicationContext = applicationContext,
                metricsSource = metricsSource,
                jpegBackendPolicy = config.jpegBackendPolicy,
                execution = composition,
            ),
        )
    }
}
