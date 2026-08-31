package io.screenstream.capture.internal.session.topology

import android.os.Build.VERSION_CODES
import io.screenstream.capture.CaptureGeometry
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.ImageRect
import io.screenstream.capture.ImageSize
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureEffectiveParameters
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.SourceRegionBounds
import io.screenstream.capture.internal.capture.CapturePlan
import io.screenstream.capture.internal.capture.CaptureTargetMode
import kotlin.math.floor

internal sealed interface SessionPlanResolution {
    class Resolved(
        internal val capturePlan: CapturePlan,
        internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    ) : SessionPlanResolution {
        internal val encoderPlan: Rgba8888Layout
            get() = capturePlan.rgbaLayout
    }

    class Rejected(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : SessionPlanResolution

    companion object {
        private const val MAX_DOWNSCALED_TARGET_SDK_INT = 37

        internal fun resolve(
            parameters: ScreenCaptureParameters,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            platformSdkInt: Int,
            sourceDimensionsAreAuthoritative: Boolean,
        ): SessionPlanResolution {
            if ((widthPx <= 0) || (heightPx <= 0) || (densityDpi <= 0)) {
                return Rejected(
                    problem = ScreenCaptureProblem.InvalidRequest,
                    cause = IllegalArgumentException("capture geometry must be positive"),
                )
            }

            val sourceRegionBounds = SourceRegionBounds.resolve(parameters.sourceRegion, widthPx)
                ?: return Rejected(
                    problem = ScreenCaptureProblem.InvalidRequest,
                    cause = IllegalArgumentException("a half-region requires capture width of at least two"),
                )

            val crop = parameters.crop
            val regionWidthPx = sourceRegionBounds.widthPx
            if ((crop.left >= regionWidthPx) || (crop.right >= (regionWidthPx - crop.left)) ||
                (crop.top >= heightPx) || (crop.bottom >= (heightPx - crop.top))
            ) {
                return Rejected(
                    problem = ScreenCaptureProblem.InvalidRequest,
                    cause = IllegalArgumentException("crop leaves no capture content"),
                )
            }

            val sourceLeftPx = sourceRegionBounds.leftPx + crop.left
            val sourceTopPx = crop.top
            val sourceRightPx = sourceRegionBounds.rightPx - crop.right
            val sourceBottomPx = heightPx - crop.bottom
            val croppedWidthPx = sourceRightPx - sourceLeftPx
            val croppedHeightPx = sourceBottomPx - sourceTopPx
            val orientedWidthPx: Int
            val orientedHeightPx: Int
            when (parameters.rotation) {
                Rotation.Degrees0, Rotation.Degrees180 -> {
                    orientedWidthPx = croppedWidthPx
                    orientedHeightPx = croppedHeightPx
                }

                Rotation.Degrees90, Rotation.Degrees270 -> {
                    orientedWidthPx = croppedHeightPx
                    orientedHeightPx = croppedWidthPx
                }
            }

            val finalImageSize: ImageSize
            val targetMode: CaptureTargetMode
            val targetWidthPx: Int
            val targetHeightPx: Int
            val rgbaLayout: Rgba8888Layout
            try {
                finalImageSize = resolveFinalImageSize(parameters.outputSize, orientedWidthPx, orientedHeightPx)
                    ?: return Rejected(
                        problem = ScreenCaptureProblem.InvalidRequest,
                        cause = IllegalArgumentException("output dimensions cannot be represented"),
                    )
                rgbaLayout = Rgba8888Layout.create(finalImageSize.widthPx, finalImageSize.heightPx)

                val scaleFactor = parameters.outputSize as? OutputSize.ScaleFactor
                val isDownscaledTargetEligible = (platformSdkInt in (VERSION_CODES.S_V2..MAX_DOWNSCALED_TARGET_SDK_INT)) &&
                        sourceDimensionsAreAuthoritative && (parameters.sourceRegion == SourceRegion.Full) &&
                        (crop == CropInsetsPx.ZERO) &&
                        (scaleFactor != null) && (scaleFactor.factor < 1.0)
                if (isDownscaledTargetEligible) {
                    val width = widthPx.toLong()
                    val height = heightPx.toLong()
                    var gcdCandidate = width
                    var gcdRemainder = height
                    while (gcdRemainder != 0L) {
                        val remainder = gcdCandidate % gcdRemainder
                        gcdCandidate = gcdRemainder
                        gcdRemainder = remainder
                    }
                    val greatestCommonDivisor = gcdCandidate
                    val reducedWidth = width / greatestCommonDivisor
                    val reducedHeight = height / greatestCommonDivisor
                    val requiredSourceWidth: Long
                    val requiredSourceHeight: Long
                    when (parameters.rotation) {
                        Rotation.Degrees0, Rotation.Degrees180 -> {
                            requiredSourceWidth = finalImageSize.widthPx.toLong()
                            requiredSourceHeight = finalImageSize.heightPx.toLong()
                        }

                        Rotation.Degrees90, Rotation.Degrees270 -> {
                            requiredSourceWidth = finalImageSize.heightPx.toLong()
                            requiredSourceHeight = finalImageSize.widthPx.toLong()
                        }
                    }
                    val requiredWidthScale = Math.addExact(
                        requiredSourceWidth / reducedWidth,
                        if ((requiredSourceWidth % reducedWidth) == 0L) 0L else 1L,
                    )
                    val requiredHeightScale = Math.addExact(
                        requiredSourceHeight / reducedHeight,
                        if ((requiredSourceHeight % reducedHeight) == 0L) 0L else 1L,
                    )
                    val scale = minOf(
                        greatestCommonDivisor,
                        maxOf(
                            1L,
                            maxOf(requiredWidthScale, requiredHeightScale),
                        ),
                    )
                    if (scale < greatestCommonDivisor) {
                        val downscaledWidth = Math.multiplyExact(reducedWidth, scale)
                        val downscaledHeight = Math.multiplyExact(reducedHeight, scale)
                        if ((downscaledWidth !in (1L..Int.MAX_VALUE.toLong())) || (downscaledHeight !in (1L..Int.MAX_VALUE.toLong()))) {
                            throw ArithmeticException("positive dimension does not fit Int")
                        }
                        targetMode = CaptureTargetMode.Downscaled
                        targetWidthPx = downscaledWidth.toInt()
                        targetHeightPx = downscaledHeight.toInt()
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
                return Rejected(
                    problem = ScreenCaptureProblem.ResourceExhausted,
                    cause = ArithmeticException("capture dimensions exceed the supported arithmetic range"),
                )
            }
            return try {
                val geometry = CaptureGeometry.create(widthPx, heightPx, densityDpi)
                val appliedSourceRect = ImageRect.create(sourceLeftPx, sourceTopPx, sourceRightPx, sourceBottomPx)
                Resolved(
                    capturePlan = CapturePlan(
                        sourceRegion = parameters.sourceRegion,
                        crop = parameters.crop,
                        appliedSourceRect = appliedSourceRect,
                        rotation = parameters.rotation,
                        mirror = parameters.mirror,
                        colorMode = parameters.colorMode,
                        sourceWidthPx = widthPx,
                        sourceHeightPx = heightPx,
                        densityDpi = densityDpi,
                        targetMode = targetMode,
                        targetWidthPx = targetWidthPx,
                        targetHeightPx = targetHeightPx,
                        rgbaLayout = rgbaLayout,
                    ),
                    effectiveParameters = ScreenCaptureEffectiveParameters.create(
                        appliedParameters = parameters,
                        captureGeometry = geometry,
                        appliedSourceRect = appliedSourceRect,
                        finalImageSize = finalImageSize,
                    ),
                )
            } catch (failure: ArithmeticException) {
                Rejected(ScreenCaptureProblem.ResourceExhausted, failure)
            } catch (failure: Exception) {
                Rejected(ScreenCaptureProblem.InternalFailure, failure)
            }
        }

        private fun resolveFinalImageSize(outputSize: OutputSize, orientedWidthPx: Int, orientedHeightPx: Int): ImageSize? =
            when (outputSize) {
                is OutputSize.ScaleFactor -> {
                    val scaledWidth = orientedWidthPx.toDouble() * outputSize.factor
                    val scaledHeight = orientedHeightPx.toDouble() * outputSize.factor
                    val roundedWidth = floor(scaledWidth + 0.5)
                    val roundedHeight = floor(scaledHeight + 0.5)
                    if (!scaledWidth.isFinite() || !scaledHeight.isFinite() ||
                        !roundedWidth.isFinite() || !roundedHeight.isFinite() ||
                        (roundedWidth !in (0.0..Int.MAX_VALUE.toDouble())) ||
                        (roundedHeight !in (0.0..Int.MAX_VALUE.toDouble()))
                    ) {
                        null
                    } else {
                        ImageSize.create(maxOf(1, roundedWidth.toInt()), maxOf(1, roundedHeight.toInt()))
                    }
                }

                is OutputSize.TargetSize -> when (outputSize.contentMode) {
                    OutputSize.ContentMode.Stretch -> ImageSize.create(outputSize.widthPx, outputSize.heightPx)
                    OutputSize.ContentMode.AspectFit -> {
                        val widthProduct = Math.multiplyExact(outputSize.widthPx.toLong(), orientedHeightPx.toLong())
                        val heightProduct = Math.multiplyExact(outputSize.heightPx.toLong(), orientedWidthPx.toLong())
                        if (widthProduct <= heightProduct) {
                            ImageSize.create(
                                outputSize.widthPx,
                                minOf(
                                    outputSize.heightPx.toLong(),
                                    maxOf(
                                        1L,
                                        Math.addExact(widthProduct, orientedWidthPx / 2L) / orientedWidthPx.toLong(),
                                    ),
                                ).toInt(),
                            )
                        } else {
                            ImageSize.create(
                                minOf(
                                    outputSize.widthPx.toLong(),
                                    maxOf(
                                        1L,
                                        Math.addExact(heightProduct, orientedHeightPx / 2L) / orientedHeightPx.toLong(),
                                    ),
                                ).toInt(),
                                outputSize.heightPx,
                            )
                        }
                    }
                }
            }

    }
}
