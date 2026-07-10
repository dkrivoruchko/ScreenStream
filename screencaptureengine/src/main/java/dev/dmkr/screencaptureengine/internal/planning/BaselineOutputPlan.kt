@file:Suppress("unused") // Dormant until the v41 clean-spine cutover.

package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Size

/** Strictly derived geometry, sampling, and raw-allocation facts for the mandatory baseline path. */
internal data class BaselineOutputPlan(
    val appliedSourceRect: ImageRect,
    val orientedContentSize: Size,
    val finalImageSize: Size,
    val projectionTargetSize: Size,
    val samplingDemand: SamplingDemand,
    val rowStrideBytes: Int,
    val requiredRgbaBytes: Int,
)

internal data class SamplingDemand(
    val horizontal: PositiveRatio,
    val vertical: PositiveRatio,
)

internal data class PositiveRatio(
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(numerator > 0)
        require(denominator > 0)
    }

    internal fun isAtMost(other: PositiveRatio): Boolean =
        numerator.toLong() * other.denominator.toLong() <= other.numerator.toLong() * denominator.toLong()
}

internal sealed interface BaselineOutputPlanFact {
    data class Planned(
        val plan: BaselineOutputPlan,
    ) : BaselineOutputPlanFact

    data class RequestNotRepresentable(
        val reason: RequestNonrepresentability,
    ) : BaselineOutputPlanFact
}

internal enum class RequestNonrepresentability {
    InvalidScalar,
    EmptySource,
    ArithmeticOverflow,
    FinalDimension,
    RgbaAddressability,
}

internal data class BaselineDeviceLimits(
    val maxProjectionTargetWidth: Int,
    val maxProjectionTargetHeight: Int,
    val maxTextureSize: Int,
    val maxRenderbufferSize: Int,
    val maxViewportWidth: Int,
    val maxViewportHeight: Int,
)

internal sealed interface BaselineCapabilityFact {
    data object Supported : BaselineCapabilityFact

    data class DeviceLimitExceeded(
        val limit: BaselineDeviceLimit,
        val required: Int,
        val available: Int,
    ) : BaselineCapabilityFact

    data object InvalidEvidence : BaselineCapabilityFact
}

internal enum class BaselineDeviceLimit {
    ProjectionTargetWidth,
    ProjectionTargetHeight,
    TextureSize,
    RenderbufferSize,
    ViewportWidth,
    ViewportHeight,
}
