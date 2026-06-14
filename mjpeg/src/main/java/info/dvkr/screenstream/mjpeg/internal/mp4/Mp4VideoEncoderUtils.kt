package info.dvkr.screenstream.mjpeg.internal.mp4

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal const val H264_MIME_TYPE: String = "video/avc"

internal data class Mp4VideoEncoderInfo(
    val name: String,
    val vendorName: String,
    val isHardwareAccelerated: Boolean,
    val isCBRModeSupported: Boolean,
    val capabilities: MediaCodecInfo.CodecCapabilities
)

internal object Mp4VideoEncoderUtils {
    private val allAvailableEncoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
    }

    val selectedH264Encoder: Mp4VideoEncoderInfo?
        get() = availableH264Encoders.firstOrNull()

    val availableH264Encoders: List<Mp4VideoEncoderInfo> by lazy {
        allAvailableEncoders
            .filter { encoder -> encoder.supportedTypes.any { it.equals(H264_MIME_TYPE, ignoreCase = true) } }
            .filter { encoder ->
                runCatching {
                    encoder.getCapabilitiesForType(H264_MIME_TYPE).colorFormats.contains(
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                }.getOrDefault(false)
            }
            .map { encoder ->
                Mp4VideoEncoderInfo(
                    name = encoder.name,
                    vendorName = encoder.name.asVendorName(),
                    isHardwareAccelerated = encoder.isHardwareAcceleratedCompat(),
                    isCBRModeSupported = encoder.isCbrCapable(H264_MIME_TYPE),
                    capabilities = encoder.getCapabilitiesForType(H264_MIME_TYPE)
                )
            }
            .sortedWith(
                compareBy(
                    {
                        when {
                            it.isHardwareAccelerated && it.isCBRModeSupported -> 0
                            it.isHardwareAccelerated && !it.isCBRModeSupported -> 1
                            !it.isHardwareAccelerated && it.isCBRModeSupported -> 2
                            else -> 3
                        }
                    },
                    { getCodecScore(it.name) }
                )
            )
    }

    fun selectH264Encoder(autoSelect: Boolean, preferredName: String): Mp4VideoEncoderInfo? =
        if (autoSelect) selectedH264Encoder
        else availableH264Encoders.firstOrNull { it.name == preferredName } ?: selectedH264Encoder

    private fun MediaCodecInfo.isCbrCapable(mimeType: String): Boolean =
        runCatching {
            getCapabilitiesForType(mimeType)
                ?.encoderCapabilities
                ?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) ?: false
        }.getOrDefault(false)

    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !isSoftwareOnlyCompat()
        }

    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return !isHardwareAccelerated

        val lowerName = name.lowercase()
        return when {
            lowerName.startsWith("arc.") -> false
            lowerName.startsWith("omx.google.") -> true
            lowerName.startsWith("omx.ffmpeg.") -> true
            lowerName.startsWith("c2.android.") -> true
            lowerName.startsWith("c2.google.") -> true
            lowerName.startsWith("omx.sec.") && lowerName.contains(".sw.") -> true
            lowerName == "omx.qcom.video.decoder.hevcswvdec" -> true
            !lowerName.startsWith("omx.") && !lowerName.startsWith("c2.") -> true
            else -> false
        }
    }

    private fun getCodecScore(name: String): Int = when (name.lowercase()) {
        "omx.google.h264.encoder" -> 1
        "c2.android.avc.encoder" -> 1
        else -> 0
    }

    private fun String.asVendorName(): String = when {
        contains("qcom", ignoreCase = true) -> "Qualcomm"
        contains("exynos", ignoreCase = true) -> "Exynos"
        contains("mtk", ignoreCase = true) || contains("mediatek", ignoreCase = true) -> "MediaTek"
        contains("nvidia", ignoreCase = true) -> "NVIDIA"
        contains("intel", ignoreCase = true) -> "Intel"
        contains("google", ignoreCase = true) -> "Google"
        contains("sprd", ignoreCase = true) -> "Spreadtrum"
        else -> "Generic"
    }

    internal fun MediaCodecInfo.VideoCapabilities.adjustSize(
        sourceWidth: Int,
        sourceHeight: Int,
        resizeFactor: Float,
        exactWidth: Int,
        exactHeight: Int,
        stretch: Boolean
    ): Pair<Int, Int> {
        val initial = if (exactWidth > 0 && exactHeight > 0) {
            exactWidth to exactHeight
        } else {
            (sourceWidth * resizeFactor).roundToInt() to (sourceHeight * resizeFactor).roundToInt()
        }

        var targetWidth = initial.first.coerceIn(supportedWidths.lower, supportedWidths.upper)
        var targetHeight = initial.second.coerceIn(supportedHeights.lower, supportedHeights.upper)

        if (!stretch) {
            val aspectRatio = sourceHeight.toDouble() / sourceWidth.toDouble()
            val heightFromWidth = (targetWidth * aspectRatio).roundToInt().coerceIn(supportedHeights.lower, supportedHeights.upper)
            val widthFromHeight = (targetHeight / aspectRatio).roundToInt().coerceIn(supportedWidths.lower, supportedWidths.upper)
            if (abs(heightFromWidth - targetHeight) < abs(widthFromHeight - targetWidth)) {
                targetHeight = heightFromWidth
            } else {
                targetWidth = widthFromHeight
            }
        }

        targetWidth = alignToMultiple(targetWidth, widthAlignment, supportedWidths)
        targetHeight = alignToMultiple(targetHeight, heightAlignment, supportedHeights)

        if (isSizeSupported(targetWidth, targetHeight)) return targetWidth to targetHeight

        val aspectRatio = sourceHeight.toDouble() / sourceWidth.toDouble()
        var bestWidth = targetWidth
        var bestHeight = targetHeight
        var bestError = Double.MAX_VALUE

        for (width in supportedWidths.lower..supportedWidths.upper step widthAlignment.coerceAtLeast(1)) {
            val rawHeight = (width * aspectRatio).roundToInt()
            val height = alignToMultiple(rawHeight, heightAlignment, supportedHeights)
            if (isSizeSupported(width, height)) {
                val error = abs(width - targetWidth) + abs(height - targetHeight)
                if (error < bestError) {
                    bestError = error.toDouble()
                    bestWidth = width
                    bestHeight = height
                }
            }
        }

        return bestWidth to bestHeight
    }

    internal fun MediaCodecInfo.VideoCapabilities.getBitRateInKbits(): ClosedRange<Int> {
        val supported = bitrateRange ?: Range(1_000, 240_000_000)
        val min = floor(supported.lower / 1000f).toInt().coerceAtLeast(1)
        val max = ceil(supported.upper / 1000f).toInt().coerceIn(1, 240_000)
        return min..max
    }

    private fun alignToMultiple(value: Int, alignment: Int, range: Range<Int>): Int {
        val aligned = if (alignment <= 1) value else {
            val remainder = value % alignment
            if (remainder == 0) value else {
                val down = value - remainder
                val up = down + alignment
                if (abs(value - up) < abs(value - down)) up else down
            }
        }
        return aligned.coerceIn(range.lower, range.upper)
    }
}
