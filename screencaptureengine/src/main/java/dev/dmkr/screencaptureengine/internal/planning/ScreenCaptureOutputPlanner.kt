package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureTarget
import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.ImageEncoderRequest
import dev.dmkr.screencaptureengine.ImageRect
import dev.dmkr.screencaptureengine.Mirror
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureEffectiveParameters
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure deterministic output planner.
 *
 * This class resolves capture geometry semantics without touching Android platform objects, GL
 * state, MediaProjection, or encoder instances. Runtime/device facts such as texture, viewport,
 * renderbuffer, and memory caps are supplied through [OutputPlanningLimits] by later runtime
 * layers. The selected encoder backend is also resolved later; this planner only builds the
 * raw-input [ImageEncoderRequest] needed to create or validate an encoder.
 */
@Suppress("unused")
internal class ScreenCaptureOutputPlanner internal constructor(
    private val limits: OutputPlanningLimits,
) {
    internal fun plan(
        geometry: CaptureGeometry,
        parameters: ScreenCaptureParameters,
    ): OutputPlanResult {
        val sourceRect = resolveSourceRect(geometry, parameters.sourceRegion)
        val appliedSourceRect = when (val cropResult = applyCrop(sourceRect, parameters.crop)) {
            AppliedSourceRectResult.Overflow -> return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Source crop coordinates exceeded supported limits.",
            )

            AppliedSourceRectResult.Empty -> return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputPlanInvalid,
                message = "Source region and crop produced an empty source rectangle.",
            )

            is AppliedSourceRectResult.Success -> cropResult.rect
        }

        val orientedContentSize = resolveOrientedContentSize(appliedSourceRect, parameters.rotation)
        val finalImageSize = resolveFinalImageSize(orientedContentSize, parameters.outputSize)
            ?: return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output size could not be represented as positive Int dimensions.",
            )

        val outputPixelCount = multiplyLong(finalImageSize.width, finalImageSize.height)
            ?: return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output pixel count overflowed.",
            )
        if (outputPixelCount > limits.maxOutputPixels) {
            return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output pixel count exceeds maxOutputPixels.",
            )
        }
        if (finalImageSize.width > limits.maxFinalImageWidth || finalImageSize.height > limits.maxFinalImageHeight) {
            return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output dimensions exceed runtime limits.",
            )
        }

        val rowStrideBytes = multiplyLong(finalImageSize.width, RGBA_8888_BYTES_PER_PIXEL)
            ?.takeIf { it <= Int.MAX_VALUE && it <= limits.maxRowStrideBytes }
            ?.toInt()
            ?: return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output row stride exceeds supported limits.",
            )
        val rgbaByteCount = multiplyLong(rowStrideBytes, finalImageSize.height)
            ?: return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output RGBA byte count overflowed.",
            )
        if (rgbaByteCount > limits.maxRgbaBytes) {
            return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Final output RGBA byte count exceeds runtime limits.",
            )
        }

        val captureTarget = resolveCaptureTarget(geometry, orientedContentSize, finalImageSize)
            ?: return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Capture target size could not be represented as positive Int dimensions.",
            )
        if (captureTarget.width > limits.maxCaptureTargetWidth || captureTarget.height > limits.maxCaptureTargetHeight) {
            return OutputPlanResult.Failure(
                kind = ScreenCaptureProblemKind.OutputLimitsExceeded,
                message = "Capture target dimensions exceed runtime limits.",
            )
        }

        val encoderRequest = ImageEncoderRequest(
            width = finalImageSize.width,
            height = finalImageSize.height,
            rowStrideBytes = rowStrideBytes,
            maxEncodedBytes = limits.maxEncodedBytes,
            inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
        )
        return OutputPlanResult.Success(
            plan = ScreenCaptureOutputPlan(
                captureGeometry = geometry,
                captureTarget = captureTarget,
                sourceRegion = parameters.sourceRegion,
                crop = parameters.crop,
                appliedSourceRect = appliedSourceRect,
                orientedContentSize = orientedContentSize,
                outputSize = parameters.outputSize,
                finalImageSize = finalImageSize,
                rotation = parameters.rotation,
                mirror = parameters.mirror,
                colorMode = parameters.colorMode,
                readbackMode = limits.readbackMode,
                frameRate = parameters.frameRate,
                encoderRequest = encoderRequest,
                rowStrideBytes = rowStrideBytes,
                rgbaByteCount = rgbaByteCount,
            ),
        )
    }

    private fun resolveSourceRect(
        geometry: CaptureGeometry,
        sourceRegion: SourceRegion,
    ): ImageRect = when (sourceRegion) {
        SourceRegion.Full -> ImageRect(
            left = 0,
            top = 0,
            right = geometry.widthPx,
            bottom = geometry.heightPx,
        )

        SourceRegion.LeftHalf -> ImageRect(
            left = 0,
            top = 0,
            right = geometry.widthPx / 2,
            bottom = geometry.heightPx,
        )

        SourceRegion.RightHalf -> ImageRect(
            left = geometry.widthPx / 2,
            top = 0,
            right = geometry.widthPx,
            bottom = geometry.heightPx,
        )
    }

    private fun applyCrop(
        sourceRect: ImageRect,
        crop: CropInsetsPx,
    ): AppliedSourceRectResult {
        val left = sourceRect.left.toLong() + crop.left.toLong()
        val top = sourceRect.top.toLong() + crop.top.toLong()
        val right = sourceRect.right.toLong() - crop.right.toLong()
        val bottom = sourceRect.bottom.toLong() - crop.bottom.toLong()
        return if (left < right && top < bottom) {
            if (left <= Int.MAX_VALUE && top <= Int.MAX_VALUE && right <= Int.MAX_VALUE && bottom <= Int.MAX_VALUE) {
                AppliedSourceRectResult.Success(
                    rect = ImageRect(
                        left = left.toInt(),
                        top = top.toInt(),
                        right = right.toInt(),
                        bottom = bottom.toInt(),
                    ),
                )
            } else {
                AppliedSourceRectResult.Overflow
            }
        } else {
            AppliedSourceRectResult.Empty
        }
    }

    private fun resolveOrientedContentSize(
        appliedSourceRect: ImageRect,
        rotation: Rotation,
    ): Size = when (rotation) {
        Rotation.Degrees0,
        Rotation.Degrees180,
            -> Size(width = appliedSourceRect.width, height = appliedSourceRect.height)

        Rotation.Degrees90,
        Rotation.Degrees270,
            -> Size(width = appliedSourceRect.height, height = appliedSourceRect.width)
    }

    private fun resolveFinalImageSize(
        orientedContentSize: Size,
        outputSize: OutputSize,
    ): Size? = when (outputSize) {
        is OutputSize.ScaleFactor -> {
            val width = roundPositiveToInt(orientedContentSize.width.toDouble() * outputSize.factor)
            val height = roundPositiveToInt(orientedContentSize.height.toDouble() * outputSize.factor)
            if (width != null && height != null) Size(width = width, height = height) else null
        }

        is OutputSize.TargetSize -> when (outputSize.contentMode) {
            ContentMode.Stretch -> Size(width = outputSize.width, height = outputSize.height)
            ContentMode.AspectFit -> {
                val scale = min(
                    outputSize.width.toDouble() / orientedContentSize.width.toDouble(),
                    outputSize.height.toDouble() / orientedContentSize.height.toDouble(),
                )
                val width = roundPositiveToInt(orientedContentSize.width.toDouble() * scale)
                val height = roundPositiveToInt(orientedContentSize.height.toDouble() * scale)
                if (width != null && height != null) {
                    Size(
                        width = min(width, outputSize.width),
                        height = min(height, outputSize.height),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun resolveCaptureTarget(
        geometry: CaptureGeometry,
        orientedContentSize: Size,
        finalImageSize: Size,
    ): CaptureTarget? {
        /*
         * Early downscale is computed from the selected/cropped/oriented content and final output
         * size, but applies the resulting scale to the whole logical capture. Integer target
         * dimensions are quantized conservatively so the actual target scale never falls below
         * the required scale; upscale remains final GL work.
         */
        val requiredCaptureScale = min(
            1.0,
            max(
                finalImageSize.width.toDouble() / orientedContentSize.width.toDouble(),
                finalImageSize.height.toDouble() / orientedContentSize.height.toDouble(),
            ),
        )
        val width = roundCaptureTargetDimension(geometry.widthPx, requiredCaptureScale)
        val height = roundCaptureTargetDimension(geometry.heightPx, requiredCaptureScale)
        return if (width != null && height != null) {
            val effectiveCaptureScale = min(
                width.toDouble() / geometry.widthPx.toDouble(),
                height.toDouble() / geometry.heightPx.toDouble(),
            )
            CaptureTarget(
                width = width,
                height = height,
                scaleFromLogicalCapture = effectiveCaptureScale,
                isEarlyDownscaled = width < geometry.widthPx || height < geometry.heightPx,
            )
        } else {
            null
        }
    }

    private fun roundPositiveToInt(value: Double): Int? {
        if (!value.isFinite() || value < 0.0 || value > Int.MAX_VALUE.toDouble()) return null
        val rounded = floor(value + 0.5)
        if (!rounded.isFinite() || rounded > Int.MAX_VALUE.toDouble()) return null
        return max(1, rounded.toInt())
    }

    private fun roundCaptureTargetDimension(
        logicalDimension: Int,
        requiredCaptureScale: Double,
    ): Int? {
        val scaledDimension = logicalDimension.toDouble() * requiredCaptureScale
        if (!scaledDimension.isFinite() || scaledDimension < 0.0 || scaledDimension > Int.MAX_VALUE.toDouble()) {
            return null
        }
        val roundedDimension = roundPositiveToInt(scaledDimension) ?: return null
        val conservativeDimension = if (roundedDimension.toDouble() / logicalDimension.toDouble() < requiredCaptureScale) {
            val ceiling = ceil(scaledDimension)
            if (!ceiling.isFinite() || ceiling > Int.MAX_VALUE.toDouble()) return null
            ceiling.toInt()
        } else {
            roundedDimension
        }
        return min(max(1, conservativeDimension), logicalDimension)
    }

    private fun multiplyLong(
        left: Int,
        right: Int,
    ): Long? = try {
        Math.multiplyExact(left.toLong(), right.toLong())
    } catch (_: ArithmeticException) {
        null
    }
}

/**
 * Planning caps supplied by public config and runtime capability discovery.
 *
 * This pure planner does not query GL or allocate buffers. Runtime layers should derive
 * capture-target limits from SurfaceTexture/viewport/texture constraints, final-image limits
 * from framebuffer and readback constraints, and byte limits from configured memory policy and
 * backend caps.
 */
@Suppress("unused")
internal data class OutputPlanningLimits(
    internal val maxOutputPixels: Int,
    internal val maxEncodedBytes: Int,
    internal val maxFinalImageWidth: Int = Int.MAX_VALUE,
    internal val maxFinalImageHeight: Int = Int.MAX_VALUE,
    internal val maxCaptureTargetWidth: Int = Int.MAX_VALUE,
    internal val maxCaptureTargetHeight: Int = Int.MAX_VALUE,
    internal val maxRowStrideBytes: Int = Int.MAX_VALUE,
    internal val maxRgbaBytes: Long = Long.MAX_VALUE,
    internal val readbackMode: ReadbackMode = ReadbackMode.Es2,
) {
    init {
        require(maxOutputPixels in MAX_OUTPUT_PIXELS_RANGE) {
            "maxOutputPixels must be in $MAX_OUTPUT_PIXELS_RANGE, was $maxOutputPixels"
        }
        require(maxEncodedBytes in MAX_ENCODED_BYTES_RANGE) {
            "maxEncodedBytes must be in $MAX_ENCODED_BYTES_RANGE, was $maxEncodedBytes"
        }
        require(maxFinalImageWidth > 0) { "maxFinalImageWidth must be positive, was $maxFinalImageWidth" }
        require(maxFinalImageHeight > 0) { "maxFinalImageHeight must be positive, was $maxFinalImageHeight" }
        require(maxCaptureTargetWidth > 0) {
            "maxCaptureTargetWidth must be positive, was $maxCaptureTargetWidth"
        }
        require(maxCaptureTargetHeight > 0) {
            "maxCaptureTargetHeight must be positive, was $maxCaptureTargetHeight"
        }
        require(maxRowStrideBytes > 0) { "maxRowStrideBytes must be positive, was $maxRowStrideBytes" }
        require(maxRgbaBytes > 0L) { "maxRgbaBytes must be positive, was $maxRgbaBytes" }
    }
}

/** Result boundary used before lifecycle layers assign problem sequences or state transitions. */
@Suppress("unused")
internal sealed class OutputPlanResult private constructor() {
    /** Planning succeeded and produced a generation-independent output plan. */
    internal class Success internal constructor(
        internal val plan: ScreenCaptureOutputPlan,
    ) : OutputPlanResult()

    /** Planning failed; callers map [kind] to startup failure, rejected update, or suspension. */
    internal class Failure internal constructor(
        internal val kind: ScreenCaptureProblemKind,
        internal val message: String,
    ) : OutputPlanResult()
}

/**
 * Generation-independent plan produced by [ScreenCaptureOutputPlanner].
 *
 * The plan intentionally does not contain [ImageEncoderInfo]. Runtime encoder creation uses the
 * parameter-selected provider after [encoderRequest] is known, then calls [toEffectiveParameters]
 * with the selected provider/backend info.
 */
@Suppress("unused")
internal class ScreenCaptureOutputPlan internal constructor(
    internal val captureGeometry: CaptureGeometry,
    internal val captureTarget: CaptureTarget,
    internal val sourceRegion: SourceRegion,
    internal val crop: CropInsetsPx,
    internal val appliedSourceRect: ImageRect,
    internal val orientedContentSize: Size,
    internal val outputSize: OutputSize,
    internal val finalImageSize: Size,
    internal val rotation: Rotation,
    internal val mirror: Mirror,
    internal val colorMode: ColorMode,
    internal val readbackMode: ReadbackMode,
    internal val frameRate: FrameRate,
    internal val encoderRequest: ImageEncoderRequest,
    internal val rowStrideBytes: Int,
    internal val rgbaByteCount: Long,
) {
    /** Builds public effective parameters after runtime encoder/backend selection is known. */
    internal fun toEffectiveParameters(encoderInfo: ImageEncoderInfo): ScreenCaptureEffectiveParameters =
        ScreenCaptureEffectiveParameters(
            captureGeometry = captureGeometry,
            captureTarget = captureTarget,
            sourceRegion = sourceRegion,
            crop = crop,
            appliedSourceRect = appliedSourceRect,
            orientedContentSize = orientedContentSize,
            outputSize = outputSize,
            finalImageSize = finalImageSize,
            rotation = rotation,
            mirror = mirror,
            colorMode = colorMode,
            readbackMode = readbackMode,
            encoderInfo = encoderInfo,
            frameRate = frameRate,
        )
}

/** Internal crop resolution result that preserves invalid-plan versus limits-exceeded mapping. */
private sealed class AppliedSourceRectResult private constructor() {
    class Success(
        val rect: ImageRect,
    ) : AppliedSourceRectResult()

    object Empty : AppliedSourceRectResult()

    object Overflow : AppliedSourceRectResult()
}

private const val RGBA_8888_BYTES_PER_PIXEL: Int = 4
private val MAX_OUTPUT_PIXELS_RANGE: IntRange = 1..268_435_456
private val MAX_ENCODED_BYTES_RANGE: IntRange = 1_024..268_435_456
