package io.screenstream.engine.internal.session.reconciliation

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.ContentMode
import io.screenstream.engine.ImageRect
import io.screenstream.engine.ImageSize
import io.screenstream.engine.Mirror
import io.screenstream.engine.OutputSize
import io.screenstream.engine.Rotation
import io.screenstream.engine.ScreenCaptureEffectiveParameters
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.SourceRegion
import io.screenstream.engine.internal.android.AndroidCaptureApiBand
import io.screenstream.engine.internal.gl.GlCapabilityFacts
import io.screenstream.engine.internal.gl.GlFrameDesiredState
import io.screenstream.engine.internal.gl.GlRenderTargetCompatibilityFacts
import io.screenstream.engine.internal.target.TargetMode
import io.screenstream.engine.internal.target.TargetPlan
import kotlin.math.floor

internal class TopologyStamp internal constructor(
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
    val stamp: TopologyStamp
    val reconciliationOccurrenceIdentity: Long
    val apiBand: AndroidCaptureApiBand
    val capabilities: GlCapabilityFacts
}

internal class ProvisionalBootstrapInput(
    override val stamp: TopologyStamp,
    override val reconciliationOccurrenceIdentity: Long,
    override val apiBand: AndroidCaptureApiBand,
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
    override val stamp: TopologyStamp,
    override val reconciliationOccurrenceIdentity: Long,
    override val apiBand: AndroidCaptureApiBand,
    internal val captureGeometry: CaptureGeometry,
    internal val parameters: ScreenCaptureParameters,
    internal val currentTopology: ReconciliationCurrentTopology,
    override val capabilities: GlCapabilityFacts,
) : ReconciliationInput {
    init {
        require(reconciliationOccurrenceIdentity > 0L)
    }
}

internal class ReconciliationTargetTopologyFacts(
    internal val plan: TargetPlan,
    internal val installedCaptureWidthPx: Int?,
    internal val installedCaptureHeightPx: Int?,
    internal val reusable: Boolean,
) {
    init {
        require(
            (installedCaptureWidthPx == null && installedCaptureHeightPx == null) ||
                    (installedCaptureWidthPx != null && installedCaptureWidthPx > 0 &&
                            installedCaptureHeightPx != null && installedCaptureHeightPx > 0),
        )
    }
}

internal class ReconciliationRenderTopologyFacts(
    internal val compatibility: GlRenderTargetCompatibilityFacts,
    internal val reusable: Boolean,
)

internal enum class ReconciliationJpegBackend {
    NativeEnabled,
    FrameworkOnNativeCarrier,
    FrameworkOnManagedCarrier,
}

internal class ReconciliationJpegTopologyFacts(
    internal val backend: ReconciliationJpegBackend,
    internal val carrierByteCount: Int,
    internal val reusable: Boolean,
) {
    init {
        require(carrierByteCount > 0)
    }
}

internal class ReconciliationFrameworkTopologyFacts(
    internal val imageSize: ImageSize,
    internal val pixelByteCount: Int,
    internal val resourcesComplete: Boolean,
) {
    init {
        require(pixelByteCount > 0)
    }
}

internal class ReconciliationCurrentTopology(
    internal val target: ReconciliationTargetTopologyFacts?,
    internal val render: ReconciliationRenderTopologyFacts?,
    internal val jpeg: ReconciliationJpegTopologyFacts?,
    internal val framework: ReconciliationFrameworkTopologyFacts?,
)

internal enum class ReconciliationResourceAction {
    Absent,
    Retain,
    Create,
    Replace,
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
    internal val effectiveParameters: ScreenCaptureEffectiveParameters,
    internal val frameReconciliationFacts: GlFrameDesiredState,
    internal val renderCompatibilityFacts: GlRenderTargetCompatibilityFacts,
    internal val targetAction: ReconciliationResourceAction,
    internal val renderAction: ReconciliationResourceAction,
    internal val jpegAction: ReconciliationResourceAction,
    internal val frameworkAction: ReconciliationResourceAction,
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
        if (input.apiBand != AndroidCaptureApiBand.Api34To37 ||
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

        if (input.apiBand == AndroidCaptureApiBand.Unsupported ||
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
        if ((input.apiBand == AndroidCaptureApiBand.Api32To33 ||
                    input.apiBand == AndroidCaptureApiBand.Api34To37) &&
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

        val appliedRect = ImageRect(appliedLeft, appliedTop, appliedRight, appliedBottom)
        val finalSize = ImageSize(outputWidth, outputHeight)
        val effective = ScreenCaptureEffectiveParameters(
            captureGeometry = geometry,
            sourceRegion = parameters.sourceRegion,
            crop = parameters.crop,
            appliedSourceRect = appliedRect,
            outputSize = parameters.outputSize,
            finalImageSize = finalSize,
            rotation = parameters.rotation,
            mirror = parameters.mirror,
            colorMode = parameters.colorMode,
            frameRate = parameters.frameRate,
            frameRepeatIntervalMillis = parameters.frameRepeatIntervalMillis,
            jpegQuality = parameters.jpegQuality,
        )
        val selectedTargetPlan = TargetPlan(targetMode, targetWidth, targetHeight)
        val current = input.currentTopology
        val targetAction = when {
            current.target == null -> ReconciliationResourceAction.Create
            !current.target.reusable -> ReconciliationResourceAction.Replace
            selectedTargetPlan.mode == TargetMode.Full && current.target.plan.mode == TargetMode.Full &&
                    retainsFullTarget(current.target, captureWidth, captureHeight) -> ReconciliationResourceAction.Retain
            selectedTargetPlan.mode == TargetMode.Downscaled && current.target.plan.mode == TargetMode.Downscaled &&
                    current.target.installedCaptureWidthPx == captureWidth &&
                    current.target.installedCaptureHeightPx == captureHeight &&
                    current.target.plan.targetWidthPx >= requiredSourceWidth &&
                    current.target.plan.targetHeightPx >= requiredSourceHeight -> ReconciliationResourceAction.Retain
            else -> ReconciliationResourceAction.Replace
        }
        val executableTargetPlan = if (targetAction == ReconciliationResourceAction.Retain &&
            selectedTargetPlan.mode == TargetMode.Downscaled
        ) {
            checkNotNull(current.target).plan
        } else {
            selectedTargetPlan
        }
        val renderFacts = GlRenderTargetCompatibilityFacts(finalSize, byteCount)
        val renderAction = when {
            current.render == null -> ReconciliationResourceAction.Create
            !current.render.reusable -> ReconciliationResourceAction.Replace
            current.render.compatibility.imageSize == finalSize &&
                    current.render.compatibility.rgbaByteCount == byteCount -> ReconciliationResourceAction.Retain
            else -> ReconciliationResourceAction.Replace
        }
        val jpegAction = when {
            current.jpeg == null -> ReconciliationResourceAction.Create
            !current.jpeg.reusable -> ReconciliationResourceAction.Replace
            current.jpeg.carrierByteCount == byteCount -> ReconciliationResourceAction.Retain
            else -> ReconciliationResourceAction.Replace
        }
        val frameworkAction = when (current.jpeg?.backend) {
            null,
            ReconciliationJpegBackend.NativeEnabled,
                -> ReconciliationResourceAction.Absent

            ReconciliationJpegBackend.FrameworkOnNativeCarrier,
            ReconciliationJpegBackend.FrameworkOnManagedCarrier,
                -> when {
                    current.framework == null -> ReconciliationResourceAction.Create
                    current.framework.resourcesComplete && current.framework.imageSize == finalSize &&
                            current.framework.pixelByteCount == byteCount -> ReconciliationResourceAction.Retain
                    else -> ReconciliationResourceAction.Replace
                }
        }
        val logicalInverse = calculateLogicalInverse(
            captureWidth = captureWidth,
            captureHeight = captureHeight,
            appliedLeft = appliedLeft,
            appliedTop = appliedTop,
            croppedWidth = croppedWidth,
            croppedHeight = croppedHeight,
            rotation = parameters.rotation,
            mirror = parameters.mirror,
        ) ?: return InternalFailure(input)

        return Resolved(
            input = input,
            appliedSourceRect = appliedRect,
            finalImageSize = finalSize,
            requiredSourceWidthPx = requiredSourceWidth,
            requiredSourceHeightPx = requiredSourceHeight,
            rgbaRowByteCount = rowByteCount,
            rgbaByteCount = byteCount,
            targetPlan = executableTargetPlan,
            effectiveParameters = effective,
            frameReconciliationFacts = GlFrameDesiredState(logicalInverse, parameters.colorMode),
            renderCompatibilityFacts = renderFacts,
            targetAction = targetAction,
            renderAction = renderAction,
            jpegAction = jpegAction,
            frameworkAction = frameworkAction,
        )
    }

    private fun retainsFullTarget(
        current: ReconciliationTargetTopologyFacts,
        captureWidth: Int,
        captureHeight: Int,
    ): Boolean = if (current.installedCaptureWidthPx != null && current.installedCaptureHeightPx != null) {
        current.installedCaptureWidthPx == captureWidth && current.installedCaptureHeightPx == captureHeight
    } else {
        current.plan.targetWidthPx == captureWidth && current.plan.targetHeightPx == captureHeight
    }

    private fun calculateLogicalInverse(
        captureWidth: Int,
        captureHeight: Int,
        appliedLeft: Int,
        appliedTop: Int,
        croppedWidth: Int,
        croppedHeight: Int,
        rotation: Rotation,
        mirror: Mirror,
    ): FloatArray? {
        fun map(a: Double, b: Double): Pair<Double, Double> {
            val orientedWidth = if (rotation == Rotation.Degrees90 || rotation == Rotation.Degrees270) {
                croppedHeight.toDouble()
            } else {
                croppedWidth.toDouble()
            }
            val orientedHeight = if (rotation == Rotation.Degrees90 || rotation == Rotation.Degrees270) {
                croppedWidth.toDouble()
            } else {
                croppedHeight.toDouble()
            }
            var u = a * orientedWidth
            var v = b * orientedHeight
            when (mirror) {
                Mirror.None -> Unit
                Mirror.Horizontal -> u = orientedWidth - u
                Mirror.Vertical -> v = orientedHeight - v
            }
            val x: Double
            val y: Double
            when (rotation) {
                Rotation.Degrees0 -> {
                    x = u
                    y = v
                }

                Rotation.Degrees90 -> {
                    x = v
                    y = croppedHeight - u
                }

                Rotation.Degrees180 -> {
                    x = croppedWidth - u
                    y = croppedHeight - v
                }

                Rotation.Degrees270 -> {
                    x = croppedWidth - v
                    y = u
                }
            }
            return Pair(
                (appliedLeft + x) / captureWidth.toDouble(),
                (appliedTop + y) / captureHeight.toDouble(),
            )
        }

        val p00 = map(0.0, 0.0)
        val p10 = map(1.0, 0.0)
        val p01 = map(0.0, 1.0)
        val coefficients = doubleArrayOf(
            p10.first - p00.first,
            p10.second - p00.second,
            p01.first - p00.first,
            p01.second - p00.second,
            p00.first,
            p00.second,
        )
        if (coefficients.any { !it.isFinite() || it !in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble() }) return null
        return FloatArray(16).also { matrix ->
            matrix[0] = coefficients[0].toFloat()
            matrix[1] = coefficients[1].toFloat()
            matrix[4] = coefficients[2].toFloat()
            matrix[5] = coefficients[3].toFloat()
            matrix[10] = 1f
            matrix[12] = coefficients[4].toFloat()
            matrix[13] = coefficients[5].toFloat()
            matrix[15] = 1f
        }
    }

    private fun hasValidCapabilities(capabilities: GlCapabilityFacts): Boolean =
        capabilities.maxTextureSize > 0 && capabilities.maxViewportWidth > 0 && capabilities.maxViewportHeight > 0

    private fun exceedsCapabilities(width: Int, height: Int, capabilities: GlCapabilityFacts): Boolean =
        width > capabilities.maxTextureSize || height > capabilities.maxTextureSize ||
                width > capabilities.maxViewportWidth || height > capabilities.maxViewportHeight

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
