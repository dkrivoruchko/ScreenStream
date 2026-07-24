package io.screenstream.engine.internal.session

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ContentMode
import io.screenstream.engine.ImageRect
import io.screenstream.engine.ImageSize
import io.screenstream.engine.OutputSize
import io.screenstream.engine.Rotation
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.SourceRegion
import io.screenstream.engine.internal.capture.CapturePlan
import io.screenstream.engine.internal.capture.CaptureTargetMode
import kotlin.math.floor

internal sealed interface SessionPlanResolution {
    class Resolved internal constructor(
        internal val capturePlan: CapturePlan,
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : SessionPlanResolution

    class Rejected internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : SessionPlanResolution
}

internal object SessionPlanResolver {
    internal fun resolve(
        parameters: ScreenCaptureParameters,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        platformSdkInt: Int,
        sourceDimensionsAuthoritative: Boolean,
    ): SessionPlanResolution {
        if (widthPx <= 0 || heightPx <= 0 || densityDpi <= 0) {
            return invalid("capture geometry must be positive")
        }

        val regionLeft: Int
        val regionRight: Int
        when (parameters.sourceRegion) {
            SourceRegion.Full -> {
                regionLeft = 0
                regionRight = widthPx
            }

            SourceRegion.LeftHalf -> {
                if (widthPx < 2) return invalid("a half-region requires capture width of at least two")
                regionLeft = 0
                regionRight = widthPx / 2
            }

            SourceRegion.RightHalf -> {
                if (widthPx < 2) return invalid("a half-region requires capture width of at least two")
                regionLeft = widthPx / 2
                regionRight = widthPx
            }
        }

        val crop = parameters.crop
        val regionWidth = regionRight - regionLeft
        if (crop.left >= regionWidth || crop.right >= regionWidth - crop.left ||
            crop.top >= heightPx || crop.bottom >= heightPx - crop.top
        ) {
            return invalid("crop leaves no capture content")
        }

        val left = regionLeft + crop.left
        val top = crop.top
        val right = regionRight - crop.right
        val bottom = heightPx - crop.bottom
        val croppedWidth = right - left
        val croppedHeight = bottom - top
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

        val output: Pair<Int, Int>
        val targetMode: CaptureTargetMode
        val targetWidthPx: Int
        val targetHeightPx: Int
        val byteCount: Long
        try {
            output = resolveOutput(parameters.outputSize, orientedWidth, orientedHeight)
                ?: return invalid("output dimensions cannot be represented")
            byteCount = Math.multiplyExact(Math.multiplyExact(output.first.toLong(), output.second.toLong()), 4L)

            val scaleFactor = parameters.outputSize as? OutputSize.ScaleFactor
            val downscaledEligible = platformSdkInt in MIN_DOWNSCALED_API..MAX_DOWNSCALED_API &&
                    sourceDimensionsAuthoritative && parameters.sourceRegion == SourceRegion.Full &&
                    crop.left == 0 && crop.top == 0 && crop.right == 0 && crop.bottom == 0 &&
                    scaleFactor != null && scaleFactor.factor < 1.0
            if (downscaledEligible) {
                val width = widthPx.toLong()
                val height = heightPx.toLong()
                val divisor = gcd(width, height)
                val baseWidth = width / divisor
                val baseHeight = height / divisor
                val requiredSourceWidth: Long
                val requiredSourceHeight: Long
                when (parameters.rotation) {
                    Rotation.Degrees0,
                    Rotation.Degrees180,
                        -> {
                        requiredSourceWidth = output.first.toLong()
                        requiredSourceHeight = output.second.toLong()
                    }

                    Rotation.Degrees90,
                    Rotation.Degrees270,
                        -> {
                        requiredSourceWidth = output.second.toLong()
                        requiredSourceHeight = output.first.toLong()
                    }
                }
                val scale = minOf(
                    divisor,
                    maxOf(
                        1L,
                        maxOf(
                            ceilDiv(requiredSourceWidth, baseWidth),
                            ceilDiv(requiredSourceHeight, baseHeight),
                        ),
                    ),
                )
                if (scale < divisor) {
                    targetMode = CaptureTargetMode.Downscaled
                    targetWidthPx = checkedPositiveInt(Math.multiplyExact(baseWidth, scale))
                    targetHeightPx = checkedPositiveInt(Math.multiplyExact(baseHeight, scale))
                } else {
                    targetMode = CaptureTargetMode.Full
                    targetWidthPx = widthPx
                    targetHeightPx = heightPx
                }
            } else {
                targetMode = CaptureTargetMode.Full
                targetWidthPx = widthPx
                targetHeightPx = heightPx
            }
        } catch (_: ArithmeticException) {
            return exhausted("capture dimensions exceed the supported arithmetic range")
        }
        if (byteCount > Int.MAX_VALUE.toLong()) {
            return exhausted("RGBA carrier exceeds the addressable Int range")
        }

        return try {
            val geometry = CaptureGeometry.create(widthPx, heightPx, densityDpi)
            val sourceRect = ImageRect.create(left, top, right, bottom)
            val imageSize = ImageSize.create(output.first, output.second)
            SessionPlanResolution.Resolved(
                capturePlan = CapturePlan(
                    parameters = parameters,
                    sourceWidthPx = widthPx,
                    sourceHeightPx = heightPx,
                    densityDpi = densityDpi,
                    targetMode = targetMode,
                    targetWidthPx = targetWidthPx,
                    targetHeightPx = targetHeightPx,
                    outputWidthPx = output.first,
                    outputHeightPx = output.second,
                ),
                effectiveParameters = ScreenCaptureEffectiveParameters.create(
                    appliedParameters = parameters,
                    captureGeometry = geometry,
                    appliedSourceRect = sourceRect,
                    finalImageSize = imageSize,
                ),
            )
        } catch (failure: ArithmeticException) {
            SessionPlanResolution.Rejected(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            SessionPlanResolution.Rejected(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    private fun resolveOutput(outputSize: OutputSize, orientedWidth: Int, orientedHeight: Int): Pair<Int, Int>? =
        when (outputSize) {
            is OutputSize.ScaleFactor -> {
                val scaledWidth = orientedWidth.toDouble() * outputSize.factor
                val scaledHeight = orientedHeight.toDouble() * outputSize.factor
                val roundedWidth = floor(scaledWidth + 0.5)
                val roundedHeight = floor(scaledHeight + 0.5)
                if (!scaledWidth.isFinite() || !scaledHeight.isFinite() ||
                    !roundedWidth.isFinite() || !roundedHeight.isFinite() ||
                    roundedWidth !in 0.0..Int.MAX_VALUE.toDouble() ||
                    roundedHeight !in 0.0..Int.MAX_VALUE.toDouble()
                ) {
                    null
                } else {
                    Pair(maxOf(1, roundedWidth.toInt()), maxOf(1, roundedHeight.toInt()))
                }
            }

            is OutputSize.TargetSize -> when (outputSize.contentMode) {
                ContentMode.Stretch -> Pair(outputSize.width, outputSize.height)
                ContentMode.AspectFit -> {
                    val widthProduct = Math.multiplyExact(outputSize.width.toLong(), orientedHeight.toLong())
                    val heightProduct = Math.multiplyExact(outputSize.height.toLong(), orientedWidth.toLong())
                    if (widthProduct <= heightProduct) {
                        Pair(
                            outputSize.width,
                            minOf(
                                outputSize.height.toLong(),
                                maxOf(
                                    1L,
                                    Math.addExact(widthProduct, orientedWidth / 2L) / orientedWidth.toLong(),
                                ),
                            ).toInt(),
                        )
                    } else {
                        Pair(
                            minOf(
                                outputSize.width.toLong(),
                                maxOf(
                                    1L,
                                    Math.addExact(heightProduct, orientedHeight / 2L) / orientedHeight.toLong(),
                                ),
                            ).toInt(),
                            outputSize.height,
                        )
                    }
                }
            }
        }

    private fun gcd(left: Long, right: Long): Long {
        var a = left
        var b = right
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun ceilDiv(numerator: Long, denominator: Long): Long = Math.addExact(
        numerator / denominator,
        if (numerator % denominator == 0L) 0L else 1L,
    )

    private fun checkedPositiveInt(value: Long): Int {
        if (value !in 1L..Int.MAX_VALUE.toLong()) {
            throw ArithmeticException("positive dimension does not fit Int")
        }
        return value.toInt()
    }

    private fun invalid(message: String): SessionPlanResolution.Rejected = SessionPlanResolution.Rejected(
        ScreenCaptureProblem.InvalidRequest,
        IllegalArgumentException(message),
    )

    private fun exhausted(message: String): SessionPlanResolution.Rejected = SessionPlanResolution.Rejected(
        ScreenCaptureProblem.ResourceExhausted,
        ArithmeticException(message),
    )

    private const val MIN_DOWNSCALED_API = 32
    private const val MAX_DOWNSCALED_API = 37
}
