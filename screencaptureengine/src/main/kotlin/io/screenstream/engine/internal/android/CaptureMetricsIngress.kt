package io.screenstream.engine.internal.android

import io.screenstream.engine.CaptureMetrics
import io.screenstream.engine.internal.settlement.EngineClock

/** Session synchronously consumes raw callback or polling evidence through the exact attachment gate. */
internal interface CaptureMetricsIngressPort {
    fun publishMetricsSample(
        attachment: CaptureMetricsAttachmentAccess,
        metrics: CaptureMetrics?,
        displayAssociation: CaptureMetricsDisplayAssociation?,
        clock: EngineClock,
    ): CaptureMetricsIngressResult

    fun publishMetricsCompleted(
        attachment: CaptureMetricsAttachmentAccess,
        clock: EngineClock,
    ): CaptureMetricsIngressResult

    fun publishMetricsFailed(
        attachment: CaptureMetricsAttachmentAccess,
        cause: Throwable,
        clock: EngineClock,
    ): CaptureMetricsIngressResult

    fun pollMetricsPhysical(
        attachment: CaptureMetricsAttachmentAccess,
        clock: EngineClock,
        endpointFailure: Throwable?,
        refreshFailure: Throwable?,
        closeFailure: Throwable?,
    ): CaptureMetricsIngressResult
}

internal enum class CaptureMetricsSourceProvenance { Custom, BuiltInDefaultDisplay, BuiltInFixedDisplay }

internal class CaptureMetricsDisplayAssociation internal constructor(
    internal val displayId: Int,
    internal val associationIdentity: Long,
    internal val validityEpoch: Long,
) {
    init {
        require(associationIdentity > 0L)
        require(validityEpoch >= 0L)
    }
}

internal enum class CaptureMetricsIngressResult {
    Published,
    Duplicate,
    Closed,
    SequenceExhausted,
    RejectedAdmission,
    RejectedCurrentness,
}
