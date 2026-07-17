package io.screenstream.engine.internal.controller

import android.os.Build
import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ContentMode
import io.screenstream.engine.ImageRect
import io.screenstream.engine.ImageSize
import io.screenstream.engine.OutputSize
import io.screenstream.engine.Rotation
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.SourceRegion
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.target.TargetMode
import io.screenstream.engine.internal.target.TargetPlan
import kotlin.math.floor

internal class ReconciliationKey(
    internal val desiredRevision: Long,
    internal val geometryGeneration: Long,
    internal val lifecycleEpoch: Long,
) {
    init {
        require(desiredRevision > 0L)
        require(geometryGeneration > 0L)
        require(lifecycleEpoch > 0L)
    }
}

internal sealed interface ReconciliationInput {
    val key: ReconciliationKey
    val reconciliationOccurrenceIdentity: Long
    val capabilities: GlCapabilityFacts
}

internal class ProvisionalBootstrapInput(
    override val key: ReconciliationKey,
    override val reconciliationOccurrenceIdentity: Long,
    internal val provisionalWidthPx: Int,
    internal val provisionalHeightPx: Int,
    internal val densityDpi: Int,
    override val capabilities: GlCapabilityFacts,
) : ReconciliationInput {
    init {
        require(reconciliationOccurrenceIdentity > 0L)
    }
}

internal class AuthoritativeInput(
    override val key: ReconciliationKey,
    override val reconciliationOccurrenceIdentity: Long,
    internal val captureGeometry: CaptureGeometry,
    internal val parameters: ScreenCaptureParameters,
    override val capabilities: GlCapabilityFacts,
) : ReconciliationInput {
    init {
        require(reconciliationOccurrenceIdentity > 0L)
    }
}

internal sealed interface ReconciliationCalculation {
    val input: ReconciliationInput
}

internal class ProvisionalFull(
    override val input: ProvisionalBootstrapInput,
    internal val targetPlan: TargetPlan,
) : ReconciliationCalculation

internal class Resolved(
    override val input: AuthoritativeInput,
    internal val appliedSourceRect: ImageRect,
    internal val finalImageSize: ImageSize,
    internal val requiredSourceWidthPx: Int,
    internal val requiredSourceHeightPx: Int,
    internal val rgbaRowByteCount: Int,
    internal val rgbaByteCount: Int,
    internal val targetPlan: TargetPlan,
) : ReconciliationCalculation

internal class InvalidRequest(
    override val input: AuthoritativeInput,
) : ReconciliationCalculation

internal class CapacityDenied(
    override val input: ReconciliationInput,
) : ReconciliationCalculation

internal class InternalFailure(
    override val input: ReconciliationInput,
) : ReconciliationCalculation

internal object ReconciliationOwner {

    internal fun calculate(input: ReconciliationInput): ReconciliationCalculation = when (input) {
        is ProvisionalBootstrapInput -> calculateProvisional(input)
        is AuthoritativeInput -> calculateAuthoritative(input)
    }

    private fun calculateProvisional(input: ProvisionalBootstrapInput): ReconciliationCalculation {
        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.UPSIDE_DOWN_CAKE..Build.VERSION_CODES.CINNAMON_BUN ||
            input.provisionalWidthPx <= 0 || input.provisionalHeightPx <= 0 || input.densityDpi <= 0 || !hasValidCapabilities(input.capabilities)
        ) {
            return InternalFailure(input)
        }

        if (exceedsCapabilities(input.provisionalWidthPx, input.provisionalHeightPx, input.capabilities)) {
            return CapacityDenied(input)
        }

        return ProvisionalFull(
            input = input,
            targetPlan = TargetPlan(
                mode = TargetMode.Full,
                targetWidthPx = input.provisionalWidthPx,
                targetHeightPx = input.provisionalHeightPx,
            ),
        )
    }

    private fun calculateAuthoritative(input: AuthoritativeInput): ReconciliationCalculation {
        val geometry = input.captureGeometry
        val parameters = input.parameters
        val captureWidth = geometry.widthPx
        val captureHeight = geometry.heightPx

        if (Build.VERSION.SDK_INT !in Build.VERSION_CODES.N..Build.VERSION_CODES.CINNAMON_BUN ||
            captureWidth <= 0 || captureHeight <= 0 || geometry.densityDpi <= 0 || !hasValidCapabilities(input.capabilities)
        ) {
            return InternalFailure(input)
        }

        val regionLeft: Int
        val regionRight: Int
        when (parameters.sourceRegion) {
            SourceRegion.Full -> {
                regionLeft = 0
                regionRight = captureWidth
            }

            SourceRegion.LeftHalf -> {
                if (captureWidth < 2) return InvalidRequest(input)
                regionLeft = 0
                regionRight = captureWidth / 2
            }

            SourceRegion.RightHalf -> {
                if (captureWidth < 2) return InvalidRequest(input)
                regionLeft = captureWidth / 2
                regionRight = captureWidth
            }
        }

        val regionWidth = regionRight - regionLeft
        val crop = parameters.crop
        if (crop.left >= regionWidth || crop.right >= regionWidth - crop.left || crop.top >= captureHeight || crop.bottom >= captureHeight - crop.top) {
            return InvalidRequest(input)
        }

        val appliedLeft = regionLeft + crop.left
        val appliedTop = crop.top
        val appliedRight = regionRight - crop.right
        val appliedBottom = captureHeight - crop.bottom
        val croppedWidth = appliedRight - appliedLeft
        val croppedHeight = appliedBottom - appliedTop

        val orientedWidth: Int
        val orientedHeight: Int
        when (parameters.rotation) {
            Rotation.Degrees0,
            Rotation.Degrees180,
                -> {
                orientedWidth = croppedWidth
                orientedHeight = croppedHeight
            }

            Rotation.Degrees90,
            Rotation.Degrees270,
                -> {
                orientedWidth = croppedHeight
                orientedHeight = croppedWidth
            }
        }

        val outputWidth: Int
        val outputHeight: Int
        when (val outputSize = parameters.outputSize) {
            is OutputSize.ScaleFactor -> {
                val roundedWidth = floor(orientedWidth.toDouble() * outputSize.factor + 0.5)
                val roundedHeight = floor(orientedHeight.toDouble() * outputSize.factor + 0.5)
                if (!roundedWidth.isFinite() || !roundedHeight.isFinite() ||
                    roundedWidth !in 0.0..Int.MAX_VALUE.toDouble() ||
                    roundedHeight !in 0.0..Int.MAX_VALUE.toDouble()
                ) {
                    return InvalidRequest(input)
                }
                outputWidth = maxOf(1, roundedWidth.toInt())
                outputHeight = maxOf(1, roundedHeight.toInt())
            }

            is OutputSize.TargetSize -> when (outputSize.contentMode) {
                ContentMode.Stretch -> {
                    outputWidth = outputSize.width
                    outputHeight = outputSize.height
                }

                ContentMode.AspectFit -> {
                    val widthProduct = outputSize.width.toLong() * orientedHeight.toLong()
                    val heightProduct = outputSize.height.toLong() * orientedWidth.toLong()
                    if (widthProduct <= heightProduct) {
                        outputWidth = outputSize.width
                        outputHeight = minOf(
                            outputSize.height.toLong(),
                            maxOf(1L, (widthProduct + orientedWidth / 2L) / orientedWidth),
                        ).toInt()
                    } else {
                        outputHeight = outputSize.height
                        outputWidth = minOf(
                            outputSize.width.toLong(),
                            maxOf(1L, (heightProduct + orientedHeight / 2L) / orientedHeight),
                        ).toInt()
                    }
                }
            }
        }

        val requiredSourceWidth: Int
        val requiredSourceHeight: Int
        when (parameters.rotation) {
            Rotation.Degrees0,
            Rotation.Degrees180,
                -> {
                requiredSourceWidth = outputWidth
                requiredSourceHeight = outputHeight
            }

            Rotation.Degrees90,
            Rotation.Degrees270,
                -> {
                requiredSourceWidth = outputHeight
                requiredSourceHeight = outputWidth
            }
        }

        val pixelCount = outputWidth.toLong() * outputHeight.toLong()
        if (pixelCount > Int.MAX_VALUE.toLong() / RGBA_BYTES_PER_PIXEL) {
            return CapacityDenied(input)
        }
        val rowByteCount = (RGBA_BYTES_PER_PIXEL * outputWidth).toInt()
        val byteCount = (RGBA_BYTES_PER_PIXEL * pixelCount).toInt()

        var targetMode = TargetMode.Full
        var targetWidth = captureWidth
        var targetHeight = captureHeight
        val scaleFactor = parameters.outputSize as? OutputSize.ScaleFactor
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S_V2..Build.VERSION_CODES.CINNAMON_BUN &&
            parameters.sourceRegion == SourceRegion.Full &&
            crop.left == 0 && crop.top == 0 && crop.right == 0 && crop.bottom == 0 &&
            scaleFactor != null && scaleFactor.factor < 1.0
        ) {
            val gcd = greatestCommonDivisor(captureWidth, captureHeight)
            val baseWidth = captureWidth / gcd
            val baseHeight = captureHeight / gcd
            val k = minOf(
                gcd.toLong(),
                maxOf(
                    1L,
                    ceilDiv(requiredSourceWidth, baseWidth),
                    ceilDiv(requiredSourceHeight, baseHeight),
                ),
            )
            val targetWidthLong = baseWidth.toLong() * k
            val targetHeightLong = baseHeight.toLong() * k
            if (targetWidthLong !in 1L..Int.MAX_VALUE.toLong() || targetHeightLong !in 1L..Int.MAX_VALUE.toLong()) {
                return InternalFailure(input)
            }
            if (k < gcd.toLong()) {
                targetMode = TargetMode.Downscaled
                targetWidth = targetWidthLong.toInt()
                targetHeight = targetHeightLong.toInt()
            }
        }

        if (exceedsCapabilities(targetWidth, targetHeight, input.capabilities) ||
            exceedsCapabilities(outputWidth, outputHeight, input.capabilities)
        ) {
            return CapacityDenied(input)
        }

        return Resolved(
            input = input,
            appliedSourceRect = ImageRect(appliedLeft, appliedTop, appliedRight, appliedBottom),
            finalImageSize = ImageSize(outputWidth, outputHeight),
            requiredSourceWidthPx = requiredSourceWidth,
            requiredSourceHeightPx = requiredSourceHeight,
            rgbaRowByteCount = rowByteCount,
            rgbaByteCount = byteCount,
            targetPlan = TargetPlan(targetMode, targetWidth, targetHeight),
        )
    }

    private fun hasValidCapabilities(capabilities: GlCapabilityFacts): Boolean =
        capabilities.maxTextureSize > 0 && capabilities.maxViewportWidth > 0 && capabilities.maxViewportHeight > 0

    private fun exceedsCapabilities(width: Int, height: Int, capabilities: GlCapabilityFacts): Boolean =
        width > capabilities.maxTextureSize ||
                height > capabilities.maxTextureSize ||
                width > capabilities.maxViewportWidth ||
                height > capabilities.maxViewportHeight

    private fun greatestCommonDivisor(first: Int, second: Int): Int {
        var left = first
        var right = second
        while (right != 0) {
            val remainder = left % right
            left = right
            right = remainder
        }
        return left
    }

    private fun ceilDiv(numerator: Int, denominator: Int): Long {
        val quotient = numerator / denominator
        return quotient.toLong() + if (numerator % denominator == 0) 0L else 1L
    }

    private const val RGBA_BYTES_PER_PIXEL: Long = 4L
}
