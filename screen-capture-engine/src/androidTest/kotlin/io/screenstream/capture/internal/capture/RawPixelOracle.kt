package io.screenstream.capture.internal.capture

import io.screenstream.capture.ColorMode
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.SourceRegion
import kotlin.math.floor

internal object RawPixelOracle {
    internal enum class TargetMode { Full, Downscaled, }

    internal class Case(
        internal val name: String,
        internal val parameters: ScreenCaptureParameters,
        internal val logicalWidthPx: Int,
        internal val logicalHeightPx: Int,
        internal val targetImage: TargetImage,
        internal val expectedTargetMode: TargetMode,
        internal val expectedTargetWidthPx: Int,
        internal val expectedTargetHeightPx: Int,
        internal val expectedOutputWidthPx: Int,
        internal val expectedOutputHeightPx: Int,
    )

    internal class TargetImage private constructor(
        internal val widthPx: Int,
        internal val heightPx: Int,
        pixels: IntArray,
    ) {
        private val topDownArgbPixels: IntArray = pixels.copyOf()

        init {
            require(topDownArgbPixels.size == widthPx * heightPx)
        }

        internal fun copyTopDownArgbPixels(): IntArray = topDownArgbPixels.copyOf()

        internal fun channelAt(x: Int, y: Int, channel: Int): Int {
            val pixel = topDownArgbPixels[(y * widthPx) + x]
            return when (channel) {
                0 -> (pixel ushr 16) and 0xFF
                1 -> (pixel ushr 8) and 0xFF
                2 -> pixel and 0xFF
                else -> error("RGB channel must be 0, 1, or 2")
            }
        }

        internal companion object {
            internal fun create(widthPx: Int, heightPx: Int, pixels: IntArray): TargetImage =
                TargetImage(widthPx, heightPx, pixels)
        }
    }

    internal class ExpectedImage(
        internal val sourceLeftPx: Int,
        internal val sourceTopPx: Int,
        internal val sourceRightPx: Int,
        internal val sourceBottomPx: Int,
        internal val widthPx: Int,
        internal val heightPx: Int,
        pixels: ByteArray,
    ) {
        private val topDownRgbaPixels: ByteArray = pixels.copyOf()

        init {
            require(topDownRgbaPixels.size == widthPx * heightPx * RGBA_CHANNEL_COUNT)
        }

        internal fun channelAt(x: Int, y: Int, channel: Int): Int =
            topDownRgbaPixels[(((y * widthPx) + x) * RGBA_CHANNEL_COUNT) + channel].toInt() and 0xFF
    }

    internal val fiveByThreeTarget: TargetImage = TargetImage.create(
        widthPx = 5,
        heightPx = 3,
        pixels = intArrayOf(
            0xFFFF0000.toInt(), 0xFFB34D26.toInt(), 0xFF000000.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(),
            0xFFFF00FF.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(), 0xFFFFFFFF.toInt(),
            0xFF0000FF.toInt(), 0xFF7030B0.toInt(), 0xFF26994D.toInt(), 0xFF008080.toInt(), 0xFFFFFF00.toInt(),
        ),
    )

    internal val expandedTenBySixTarget: TargetImage = expandTwoByTwo(fiveByThreeTarget)

    internal fun expected(case: Case): ExpectedImage {
        val parameters = case.parameters
        val selectedLeftPx: Int
        val selectedRightPx: Int
        when (parameters.sourceRegion) {
            SourceRegion.Full -> {
                selectedLeftPx = 0
                selectedRightPx = case.logicalWidthPx
            }

            SourceRegion.LeftHalf -> {
                selectedLeftPx = 0
                selectedRightPx = case.logicalWidthPx / 2
            }

            SourceRegion.RightHalf -> {
                selectedLeftPx = case.logicalWidthPx / 2
                selectedRightPx = case.logicalWidthPx
            }
        }

        val crop = parameters.crop
        val sourceLeftPx = selectedLeftPx + crop.left
        val sourceTopPx = crop.top
        val sourceRightPx = selectedRightPx - crop.right
        val sourceBottomPx = case.logicalHeightPx - crop.bottom
        val croppedWidthPx = sourceRightPx - sourceLeftPx
        val croppedHeightPx = sourceBottomPx - sourceTopPx
        require(croppedWidthPx > 0 && croppedHeightPx > 0)

        val rotated = (parameters.rotation == Rotation.Degrees90) || (parameters.rotation == Rotation.Degrees270)
        val orientedWidthPx = if (rotated) croppedHeightPx else croppedWidthPx
        val orientedHeightPx = if (rotated) croppedWidthPx else croppedHeightPx
        val outputSize = outputSize(parameters.outputSize, orientedWidthPx, orientedHeightPx)
        check(outputSize[0] == case.expectedOutputWidthPx) {
            "${case.name}: independently resolved width ${outputSize[0]}, expected literal ${case.expectedOutputWidthPx}"
        }
        check(outputSize[1] == case.expectedOutputHeightPx) {
            "${case.name}: independently resolved height ${outputSize[1]}, expected literal ${case.expectedOutputHeightPx}"
        }
        check(case.targetImage.widthPx == case.expectedTargetWidthPx)
        check(case.targetImage.heightPx == case.expectedTargetHeightPx)
        when (case.expectedTargetMode) {
            TargetMode.Full -> {
                check(case.expectedTargetWidthPx == case.logicalWidthPx)
                check(case.expectedTargetHeightPx == case.logicalHeightPx)
            }

            TargetMode.Downscaled -> {
                check(case.expectedTargetWidthPx <= case.logicalWidthPx)
                check(case.expectedTargetHeightPx <= case.logicalHeightPx)
            }
        }

        val pixels = ByteArray(outputSize[0] * outputSize[1] * RGBA_CHANNEL_COUNT)
        for (outputY in 0 until outputSize[1]) {
            for (outputX in 0 until outputSize[0]) {
                var orientedX = (outputX + 0.5) * orientedWidthPx.toDouble() / outputSize[0].toDouble()
                var orientedY = (outputY + 0.5) * orientedHeightPx.toDouble() / outputSize[1].toDouble()
                when (parameters.mirror) {
                    Mirror.None -> Unit
                    Mirror.Horizontal -> orientedX = orientedWidthPx - orientedX
                    Mirror.Vertical -> orientedY = orientedHeightPx - orientedY
                }

                val sourceX: Double
                val sourceY: Double
                when (parameters.rotation) {
                    Rotation.Degrees0 -> {
                        sourceX = orientedX
                        sourceY = orientedY
                    }

                    Rotation.Degrees90 -> {
                        sourceX = orientedY
                        sourceY = croppedHeightPx - orientedX
                    }

                    Rotation.Degrees180 -> {
                        sourceX = croppedWidthPx - orientedX
                        sourceY = croppedHeightPx - orientedY
                    }

                    Rotation.Degrees270 -> {
                        sourceX = croppedWidthPx - orientedY
                        sourceY = orientedX
                    }
                }

                val normalizedX = (sourceLeftPx + sourceX) / case.logicalWidthPx.toDouble()
                val normalizedY = (sourceTopPx + sourceY) / case.logicalHeightPx.toDouble()
                val outputOffset = ((outputY * outputSize[0]) + outputX) * RGBA_CHANNEL_COUNT
                val quantized = IntArray(RGB_CHANNEL_COUNT) { channel ->
                    quantize(sampleLinearClamp(case.targetImage, normalizedX, normalizedY, channel))
                }
                if (parameters.colorMode == ColorMode.Grayscale) {
                    val gray = ((77 * quantized[0]) + (150 * quantized[1]) + (29 * quantized[2]) + 128) shr 8
                    quantized.fill(gray)
                }
                for (channel in 0 until RGB_CHANNEL_COUNT) {
                    pixels[outputOffset + channel] = quantized[channel].toByte()
                }
                pixels[outputOffset + ALPHA_CHANNEL] = 0xFF.toByte()
            }
        }
        return ExpectedImage(
            sourceLeftPx = sourceLeftPx,
            sourceTopPx = sourceTopPx,
            sourceRightPx = sourceRightPx,
            sourceBottomPx = sourceBottomPx,
            widthPx = outputSize[0],
            heightPx = outputSize[1],
            pixels = pixels,
        )
    }

    private fun expandTwoByTwo(source: TargetImage): TargetImage {
        val expandedWidthPx = source.widthPx * 2
        val expandedHeightPx = source.heightPx * 2
        val expanded = IntArray(expandedWidthPx * expandedHeightPx)
        val sourcePixels = source.copyTopDownArgbPixels()
        for (sourceY in 0 until source.heightPx) {
            for (sourceX in 0 until source.widthPx) {
                val pixel = sourcePixels[(sourceY * source.widthPx) + sourceX]
                val expandedX = sourceX * 2
                val expandedY = sourceY * 2
                expanded[(expandedY * expandedWidthPx) + expandedX] = pixel
                expanded[(expandedY * expandedWidthPx) + expandedX + 1] = pixel
                expanded[((expandedY + 1) * expandedWidthPx) + expandedX] = pixel
                expanded[((expandedY + 1) * expandedWidthPx) + expandedX + 1] = pixel
            }
        }
        return TargetImage.create(expandedWidthPx, expandedHeightPx, expanded)
    }

    private fun outputSize(outputSize: OutputSize, orientedWidthPx: Int, orientedHeightPx: Int): IntArray =
        when (outputSize) {
            is OutputSize.ScaleFactor -> intArrayOf(
                maxOf(1, floor((orientedWidthPx * outputSize.factor) + 0.5).toInt()),
                maxOf(1, floor((orientedHeightPx * outputSize.factor) + 0.5).toInt()),
            )

            is OutputSize.TargetSize -> when (outputSize.contentMode) {
                OutputSize.ContentMode.Stretch -> intArrayOf(outputSize.widthPx, outputSize.heightPx)
                OutputSize.ContentMode.AspectFit -> {
                    val widthProduct = outputSize.widthPx.toLong() * orientedHeightPx.toLong()
                    val heightProduct = outputSize.heightPx.toLong() * orientedWidthPx.toLong()
                    if (widthProduct <= heightProduct) {
                        intArrayOf(
                            outputSize.widthPx,
                            ((widthProduct + (orientedWidthPx / 2L)) / orientedWidthPx.toLong())
                                .coerceIn(1L, outputSize.heightPx.toLong()).toInt(),
                        )
                    } else {
                        intArrayOf(
                            ((heightProduct + (orientedHeightPx / 2L)) / orientedHeightPx.toLong())
                                .coerceIn(1L, outputSize.widthPx.toLong()).toInt(),
                            outputSize.heightPx,
                        )
                    }
                }
            }
        }

    private fun sampleLinearClamp(
        target: TargetImage,
        normalizedX: Double,
        normalizedY: Double,
        channel: Int,
    ): Double {
        val texelX = (normalizedX * target.widthPx) - 0.5
        val texelY = (normalizedY * target.heightPx) - 0.5
        val x0 = floor(texelX).toInt()
        val y0 = floor(texelY).toInt()
        val x1 = x0 + 1
        val y1 = y0 + 1
        val weightX = texelX - x0
        val weightY = texelY - y0
        val clampedX0 = x0.coerceIn(0, target.widthPx - 1)
        val clampedX1 = x1.coerceIn(0, target.widthPx - 1)
        val clampedY0 = y0.coerceIn(0, target.heightPx - 1)
        val clampedY1 = y1.coerceIn(0, target.heightPx - 1)
        val topLeft = target.channelAt(clampedX0, clampedY0, channel)
        val topRight = target.channelAt(clampedX1, clampedY0, channel)
        val bottomLeft = target.channelAt(clampedX0, clampedY1, channel)
        val bottomRight = target.channelAt(clampedX1, clampedY1, channel)
        return (topLeft * (1.0 - weightX) * (1.0 - weightY)) +
                (topRight * weightX * (1.0 - weightY)) +
                (bottomLeft * (1.0 - weightX) * weightY) +
                (bottomRight * weightX * weightY)
    }

    private fun quantize(value: Double): Int = floor(value.coerceIn(0.0, 255.0) + 0.5).toInt()

    private const val RGB_CHANNEL_COUNT = 3
    private const val RGBA_CHANNEL_COUNT = 4
    private const val ALPHA_CHANNEL = 3
}
