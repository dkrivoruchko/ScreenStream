package io.screenstream.engine.internal.android

import android.view.Display
import io.screenstream.engine.CaptureMetrics

// Aggregate-owned leaf-facing seam. Metrics code may consume this contract but does not own it.
internal interface CaptureMetricsIngressPort {
    fun publishMetricsSample(
        expectedOwner: CaptureMetricsOwner,
        expectedObservationIdentity: Long,
        metrics: CaptureMetrics?,
        display: Display?,
        displayEpoch: Long,
    ): CaptureMetricsIngressResult

    fun publishMetricsTerminal(
        expectedOwner: CaptureMetricsOwner,
        expectedObservationIdentity: Long,
        kind: CaptureMetricsTerminalKind,
        cause: Throwable?,
    ): CaptureMetricsIngressResult
}

internal enum class CaptureMetricsIngressResult {
    Published,
    Duplicate,
    Closed,
    SequenceExhausted,
    RejectedAdmission,
    RejectedCurrentness,
}
