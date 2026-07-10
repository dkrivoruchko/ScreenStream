@file:Suppress("unused") // Dormant until the v41 clean-spine cutover.

package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import kotlin.math.max
import kotlin.math.min

internal object BaselineOutputPlanner {
    internal fun planScaleFactor(
        logicalCaptureSize: Size,
        sourceRegion: SourceRegion,
        crop: CropInsetsPx,
        rotation: Rotation,
        factor: Double,
    ): BaselineOutputPlanFact = plan(
        logicalCaptureSize = logicalCaptureSize,
        sourceRegion = sourceRegion,
        crop = crop,
        rotation = rotation,
    ) { orientedSize ->
        val width = CheckedArithmetic.roundPositiveProductToInt(orientedSize.width, factor)
        val height = CheckedArithmetic.roundPositiveProductToInt(orientedSize.height, factor)
        when {
            width === CheckedIntFact.InvalidInput || height === CheckedIntFact.InvalidInput -> FinalSizeFact.InvalidScalar
            width === CheckedIntFact.Overflow || height === CheckedIntFact.Overflow -> FinalSizeFact.NotRepresentable
            else -> FinalSizeFact.Value(
                Size(
                    width = (width as CheckedIntFact.Value).value,
                    height = (height as CheckedIntFact.Value).value,
                ),
            )
        }
    }

    internal fun planTargetSize(
        logicalCaptureSize: Size,
        sourceRegion: SourceRegion,
        crop: CropInsetsPx,
        rotation: Rotation,
        targetWidth: Int,
        targetHeight: Int,
        contentMode: ContentMode,
    ): BaselineOutputPlanFact = plan(
        logicalCaptureSize = logicalCaptureSize,
        sourceRegion = sourceRegion,
        crop = crop,
        rotation = rotation,
    ) { orientedSize ->
        if (targetWidth <= 0 || targetHeight <= 0) {
            FinalSizeFact.InvalidScalar
        } else {
            when (contentMode) {
                ContentMode.Stretch -> FinalSizeFact.Value(Size(targetWidth, targetHeight))
                ContentMode.AspectFit -> aspectFit(orientedSize, targetWidth, targetHeight)
            }
        }
    }

    internal fun validateCapabilities(
        plan: BaselineOutputPlan,
        limits: BaselineDeviceLimits,
    ): BaselineCapabilityFact {
        if (
            limits.maxProjectionTargetWidth <= 0 || limits.maxProjectionTargetHeight <= 0 ||
            limits.maxTextureSize <= 0 || limits.maxRenderbufferSize <= 0 ||
            limits.maxViewportWidth <= 0 || limits.maxViewportHeight <= 0
        ) {
            return BaselineCapabilityFact.InvalidEvidence
        }

        val target = plan.projectionTargetSize
        val output = plan.finalImageSize
        val requiredTextureSize = max(max(target.width, target.height), max(output.width, output.height))
        return when {
            target.width > limits.maxProjectionTargetWidth -> exceeded(
                BaselineDeviceLimit.ProjectionTargetWidth,
                target.width,
                limits.maxProjectionTargetWidth,
            )

            target.height > limits.maxProjectionTargetHeight -> exceeded(
                BaselineDeviceLimit.ProjectionTargetHeight,
                target.height,
                limits.maxProjectionTargetHeight,
            )

            requiredTextureSize > limits.maxTextureSize -> exceeded(
                BaselineDeviceLimit.TextureSize,
                requiredTextureSize,
                limits.maxTextureSize,
            )

            max(output.width, output.height) > limits.maxRenderbufferSize -> exceeded(
                BaselineDeviceLimit.RenderbufferSize,
                max(output.width, output.height),
                limits.maxRenderbufferSize,
            )

            output.width > limits.maxViewportWidth -> exceeded(
                BaselineDeviceLimit.ViewportWidth,
                output.width,
                limits.maxViewportWidth,
            )

            output.height > limits.maxViewportHeight -> exceeded(
                BaselineDeviceLimit.ViewportHeight,
                output.height,
                limits.maxViewportHeight,
            )

            else -> BaselineCapabilityFact.Supported
        }
    }

    private inline fun plan(
        logicalCaptureSize: Size,
        sourceRegion: SourceRegion,
        crop: CropInsetsPx,
        rotation: Rotation,
        resolveFinalSize: (Size) -> FinalSizeFact,
    ): BaselineOutputPlanFact {
        val selected = select(logicalCaptureSize, sourceRegion)
        val cropped = applyCrop(selected, crop)
            ?: return BaselineOutputPlanFact.RequestNotRepresentable(RequestNonrepresentability.EmptySource)
        val appliedSourceRect = ImageRect(cropped.left, cropped.top, cropped.right, cropped.bottom)
        val unrotatedSize = Size(cropped.right - cropped.left, cropped.bottom - cropped.top)
        val orientedSize = when (rotation) {
            Rotation.Degrees0,
            Rotation.Degrees180 -> unrotatedSize

            Rotation.Degrees90,
            Rotation.Degrees270 -> Size(unrotatedSize.height, unrotatedSize.width)
        }
        val finalSize = when (val fact = resolveFinalSize(orientedSize)) {
            FinalSizeFact.InvalidScalar -> return BaselineOutputPlanFact.RequestNotRepresentable(
                RequestNonrepresentability.InvalidScalar,
            )

            FinalSizeFact.NotRepresentable -> return BaselineOutputPlanFact.RequestNotRepresentable(
                RequestNonrepresentability.FinalDimension,
            )

            is FinalSizeFact.Value -> fact.size
        }
        val rowStride = when (val fact = CheckedArithmetic.rgbaRowByteCount(finalSize.width)) {
            is CheckedLongFact.Value -> when (val narrowed = CheckedArithmetic.narrowToPositiveInt(fact.value)) {
                is CheckedIntFact.Value -> narrowed.value
                else -> return BaselineOutputPlanFact.RequestNotRepresentable(RequestNonrepresentability.RgbaAddressability)
            }

            else -> return BaselineOutputPlanFact.RequestNotRepresentable(RequestNonrepresentability.ArithmeticOverflow)
        }
        val requiredBytes = when (
            val fact = CheckedArithmetic.requiredRgbaByteCount(finalSize.width, finalSize.height, rowStride)
        ) {
            is CheckedLongFact.Value -> when (val narrowed = CheckedArithmetic.narrowToPositiveInt(fact.value)) {
                is CheckedIntFact.Value -> narrowed.value
                else -> return BaselineOutputPlanFact.RequestNotRepresentable(RequestNonrepresentability.RgbaAddressability)
            }

            else -> return BaselineOutputPlanFact.RequestNotRepresentable(RequestNonrepresentability.RgbaAddressability)
        }

        val samplingDemand = when (rotation) {
            Rotation.Degrees0,
            Rotation.Degrees180 -> SamplingDemand(
                horizontal = PositiveRatio(finalSize.width, unrotatedSize.width),
                vertical = PositiveRatio(finalSize.height, unrotatedSize.height),
            )

            Rotation.Degrees90,
            Rotation.Degrees270 -> SamplingDemand(
                horizontal = PositiveRatio(finalSize.height, unrotatedSize.width),
                vertical = PositiveRatio(finalSize.width, unrotatedSize.height),
            )
        }

        return BaselineOutputPlanFact.Planned(
            BaselineOutputPlan(
                appliedSourceRect = appliedSourceRect,
                orientedContentSize = orientedSize,
                finalImageSize = finalSize,
                projectionTargetSize = logicalCaptureSize,
                samplingDemand = samplingDemand,
                rowStrideBytes = rowStride,
                requiredRgbaBytes = requiredBytes,
            ),
        )
    }

    private fun select(size: Size, sourceRegion: SourceRegion): LongRect = when (sourceRegion) {
        SourceRegion.Full -> LongRect(0L, 0L, size.width.toLong(), size.height.toLong())
        SourceRegion.LeftHalf -> LongRect(0L, 0L, (size.width / 2).toLong(), size.height.toLong())
        SourceRegion.RightHalf -> LongRect((size.width / 2).toLong(), 0L, size.width.toLong(), size.height.toLong())
    }

    private fun applyCrop(rect: LongRect, crop: CropInsetsPx): IntRect? {
        val left = rect.left + crop.left.toLong()
        val top = rect.top + crop.top.toLong()
        val right = rect.right - crop.right.toLong()
        val bottom = rect.bottom - crop.bottom.toLong()
        if (left < 0L || top < 0L || left >= right || top >= bottom) return null
        if (right > Int.MAX_VALUE || bottom > Int.MAX_VALUE) return null
        return IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private fun aspectFit(orientedSize: Size, targetWidth: Int, targetHeight: Int): FinalSizeFact {
        val widthProduct = CheckedArithmetic.multiplyNonNegative(targetWidth.toLong(), orientedSize.height.toLong())
        val heightProduct = CheckedArithmetic.multiplyNonNegative(targetHeight.toLong(), orientedSize.width.toLong())
        if (widthProduct !is CheckedLongFact.Value || heightProduct !is CheckedLongFact.Value) {
            return FinalSizeFact.NotRepresentable
        }

        return if (widthProduct.value <= heightProduct.value) {
            val height = roundedRatio(orientedSize.height, targetWidth, orientedSize.width)
                ?: return FinalSizeFact.NotRepresentable
            FinalSizeFact.Value(Size(targetWidth, min(targetHeight, max(1, height))))
        } else {
            val width = roundedRatio(orientedSize.width, targetHeight, orientedSize.height)
                ?: return FinalSizeFact.NotRepresentable
            FinalSizeFact.Value(Size(min(targetWidth, max(1, width)), targetHeight))
        }
    }

    private fun roundedRatio(value: Int, scale: Int, denominator: Int): Int? {
        val numerator = CheckedArithmetic.multiplyNonNegative(value.toLong(), scale.toLong())
        if (numerator !is CheckedLongFact.Value) return null
        val rounded = CheckedArithmetic.roundPositiveRatio(numerator.value, denominator.toLong())
        if (rounded !is CheckedLongFact.Value) return null
        return (CheckedArithmetic.narrowToPositiveInt(max(1L, rounded.value)) as? CheckedIntFact.Value)?.value
    }

    private fun exceeded(limit: BaselineDeviceLimit, required: Int, available: Int): BaselineCapabilityFact =
        BaselineCapabilityFact.DeviceLimitExceeded(limit, required, available)

    private sealed interface FinalSizeFact {
        data class Value(val size: Size) : FinalSizeFact
        data object InvalidScalar : FinalSizeFact
        data object NotRepresentable : FinalSizeFact
    }

    private data class LongRect(val left: Long, val top: Long, val right: Long, val bottom: Long)
    private data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)
}
