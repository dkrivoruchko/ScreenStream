package info.dvkr.screenstream.rtsp.internal

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import androidx.core.util.toClosedRange
import info.dvkr.screenstream.rtsp.internal.Codec.Audio
import info.dvkr.screenstream.rtsp.internal.Codec.Video
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal object EncoderUtils {

    private val allAvailableEncoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
    }

    // Video: H.265, H.264, AV1
    val availableVideoEncoders: List<VideoCodecInfo> by lazy {
        buildList {
            addAll(findVideoEncoders(Video.H264))
            addAll(findVideoEncoders(Video.H265))
            addAll(findVideoEncoders(Video.AV1))
        }.sortedWith(
            compareBy(
                {
                    when (it.codec) {
                        Video.H264 -> 0
                        Video.H265 -> 1
                        Video.AV1 -> 2
                    }
                },
                {
                    when {
                        it.isHardwareAccelerated && it.isCBRModeSupported -> 0
                        it.isHardwareAccelerated && !it.isCBRModeSupported -> 1
                        !it.isHardwareAccelerated && it.isCBRModeSupported -> 2
                        else -> 3
                    }
                }
            )
        )
    }


    // Audio: OPUS, AAC, G.711
    val availableAudioEncoders: List<AudioCodecInfo> by lazy {
        buildList {
            addAll(findAudioEncoders(Audio.OPUS))
            addAll(findAudioEncoders(Audio.AAC))
            add(AudioCodecInfo(name = "sw.audio.g711.alaw", codec = Audio.G711, vendorName = "Generic", false, true, null))
        }.sortedWith(
            compareBy(
                { if (it.isHardwareAccelerated) 0 else 1 },
                {
                    when (it.codec) {
                        Audio.OPUS -> 0
                        Audio.AAC -> 1
                        Audio.G711 -> 2
                    }
                }
            )
        )
    }

    private fun findVideoEncoders(codec: Video): List<VideoCodecInfo> = getEncodersForMime(codec.mimeType, true)
        .filter {
            runCatching {
                it.getCapabilitiesForType(codec.mimeType).colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }.getOrDefault(false)
        }
        .map { encoder ->
            VideoCodecInfo(
                name = encoder.name,
                codec = codec,
                vendorName = encoder.name.asVendorName(),
                isHardwareAccelerated = encoder.isHardwareAcceleratedCompat(),
                isCBRModeSupported = encoder.isCbrCapable(codec.mimeType),
                capabilities = encoder.getCapabilitiesForType(codec.mimeType)
            )
        }

    private fun findAudioEncoders(codec: Audio): List<AudioCodecInfo> = getEncodersForMime(codec.mimeType, false)
        .map { encoder ->
            AudioCodecInfo(
                name = encoder.name,
                codec = codec,
                vendorName = encoder.name.asVendorName(),
                isHardwareAccelerated = encoder.isHardwareAcceleratedCompat(),
                isCBRModeSupported = encoder.isCbrCapable(codec.mimeType),
                capabilities = encoder.getCapabilitiesForType(codec.mimeType)
            )
        }

    private fun getEncodersForMime(mimeType: String, cbrPriority: Boolean): List<MediaCodecInfo> {
        val matching = allAvailableEncoders
            .filter { it.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .sortedByDescending { getCodecScore(it.name) }

        val (hardware, software) = matching.partition { it.isHardwareAcceleratedCompat() }
        if (!cbrPriority) return hardware + software

        // If CBR priority is requested, group CBR-capable encoders first
        val (hwCbr, hwNonCbr) = hardware.partition { it.isCbrCapable(mimeType) }
        val (swCbr, swNonCbr) = software.partition { it.isCbrCapable(mimeType) }
        return hwCbr + hwNonCbr + swCbr + swNonCbr
    }

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

    /**
     * Adapted from google/ExoPlayer:
     * https://github.com/google/ExoPlayer/commit/48555550d7fcf6953f2382466818c74092b26355
     */
    private fun MediaCodecInfo.isSoftwareOnlyCompat(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return !isHardwareAccelerated
        }

        val lowerName = name.lowercase()
        return when {
            // Arc codecs on Chrome OS are typically hardware
            lowerName.startsWith("arc.") -> false

            // Known software prefixes
            lowerName.startsWith("omx.google.") -> true
            lowerName.startsWith("omx.ffmpeg.") -> true
            lowerName.startsWith("c2.android.") -> true
            lowerName.startsWith("c2.google.") -> true

            // Samsung SW codecs
            lowerName.startsWith("omx.sec.") && lowerName.contains(".sw.") -> true

            // Older Qualcomm SW
            lowerName == "omx.qcom.video.decoder.hevcswvdec" -> true

            // If it’s not in the known omx./c2. families, ExoPlayer typically flags it as software.
            // But some OEM hardware encoders don’t follow standard naming. They may get misclassified.
            !lowerName.startsWith("omx.") && !lowerName.startsWith("c2.") -> true

            else -> false
        }
    }

    private fun getCodecScore(name: String): Int = when (name.lowercase()) {
        "c2.sec.aac.encoder" -> -2
        "omx.google.aac.encoder" -> -1
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

    internal fun MediaCodecInfo.VideoCapabilities.adjustResizeFactor(
        sourceWidth: Int,
        sourceHeight: Int,
        resizeFactor: Float
    ): Triple<Float, Int, Int> {
        var targetWidth = (sourceWidth * resizeFactor).roundToInt()
        var targetHeight = (sourceHeight * resizeFactor).roundToInt()

        targetWidth = targetWidth.coerceIn(supportedWidths.lower, supportedWidths.upper)
        targetHeight = targetHeight.coerceIn(supportedHeights.lower, supportedHeights.upper)

        fun alignToMultiple(value: Int, alignment: Int, min: Int, max: Int): Int {
            if (alignment <= 1) return value.coerceIn(min, max)
            val remainder = value % alignment
            if (remainder == 0) return value.coerceIn(min, max)
            val down = (value - remainder).coerceAtLeast(min)
            val up = (down + alignment).coerceAtMost(max)
            val diffDown = abs(value - down)
            val diffUp = abs(value - up)
            return if (diffUp < diffDown) up else down
        }

        targetWidth = alignToMultiple(targetWidth, widthAlignment, supportedWidths.lower, supportedWidths.upper)
        targetHeight = alignToMultiple(targetHeight, heightAlignment, supportedHeights.lower, supportedHeights.upper)

        val aspectRatio = sourceHeight.toDouble() / sourceWidth.toDouble()

        fun ratioError(w: Int, h: Int): Double = abs(h.toDouble() / w.toDouble() - aspectRatio)

        val idealHeight = (targetWidth * aspectRatio).roundToInt().coerceIn(supportedHeights.lower, supportedHeights.upper)
        val alignedIdealHeight = alignToMultiple(idealHeight, heightAlignment, supportedHeights.lower, supportedHeights.upper)
        val errorIfHeightPicked = ratioError(targetWidth, alignedIdealHeight)

        val idealWidth = (targetHeight / aspectRatio).roundToInt().coerceIn(supportedWidths.lower, supportedWidths.upper)
        val alignedIdealWidth = alignToMultiple(idealWidth, widthAlignment, supportedWidths.lower, supportedWidths.upper)
        val errorIfWidthPicked = ratioError(alignedIdealWidth, targetHeight)

        if (isSizeSupported(targetWidth, alignedIdealHeight)) {
            if (isSizeSupported(alignedIdealWidth, targetHeight)) {
                if (errorIfHeightPicked < errorIfWidthPicked) {
                    targetHeight = alignedIdealHeight
                } else {
                    targetWidth = alignedIdealWidth
                }
            } else {
                targetHeight = alignedIdealHeight
            }
        } else if (isSizeSupported(alignedIdealWidth, targetHeight)) {
            targetWidth = alignedIdealWidth
        }

        if (!isSizeSupported(targetWidth, targetHeight)) {
            var bestWidth = targetWidth
            var bestHeight = targetHeight
            var bestRatioError = Double.MAX_VALUE

            for (w in targetWidth downTo supportedWidths.lower step widthAlignment) {
                val rawH = (w * aspectRatio).roundToInt().coerceIn(supportedHeights.lower, supportedHeights.upper)
                val candidateH = alignToMultiple(rawH, heightAlignment, supportedHeights.lower, supportedHeights.upper)
                if (isSizeSupported(w, candidateH)) {
                    val candidateError = ratioError(w, candidateH)
                    if (candidateError < bestRatioError) {
                        bestRatioError = candidateError
                        bestWidth = w
                        bestHeight = candidateH
                    }
                }
            }

            for (w in targetWidth..supportedWidths.upper step widthAlignment) {
                val rawH = (w * aspectRatio).roundToInt().coerceIn(supportedHeights.lower, supportedHeights.upper)
                val candidateH = alignToMultiple(rawH, heightAlignment, supportedHeights.lower, supportedHeights.upper)
                if (isSizeSupported(w, candidateH)) {
                    val candidateError = ratioError(w, candidateH)
                    if (candidateError < bestRatioError) {
                        bestRatioError = candidateError
                        bestWidth = w
                        bestHeight = candidateH
                    }
                }
            }

            if (bestRatioError < Double.MAX_VALUE) {
                targetWidth = bestWidth
                targetHeight = bestHeight
            } else {
                targetWidth = supportedWidths.lower
                targetHeight = supportedHeights.lower
            }
        }

        val finalWidthFactor = targetWidth.toFloat() / sourceWidth.toFloat()
        val finalHeightFactor = targetHeight.toFloat() / sourceHeight.toFloat()
        val newFactor = (finalWidthFactor + finalHeightFactor) / 2f

        return Triple(newFactor, targetWidth, targetHeight)
    }

    internal fun MediaCodecInfo.VideoCapabilities.getFrameRates(width: Int, height: Int): ClosedRange<Int> {
        val supported = getSupportedFrameRatesFor(width, height) ?: Range(1.0, 240.0)
        val minFps = ceil(supported.lower).toInt().coerceAtLeast(1)
        val maxFps = floor(supported.upper).toInt().coerceIn(1, 240)
        return if (minFps <= maxFps) {
            Range(minFps, maxFps).toClosedRange()
        } else {
            Range(1, 240).toClosedRange()
        }
    }

    internal fun MediaCodecInfo.VideoCapabilities.getBitRateInKbits(): ClosedRange<Int> {
        val supported = bitrateRange ?: Range(1_000, 240_000_000)
        val min = floor(supported.lower / 1000f).toInt().coerceAtLeast(1)
        val max = ceil(supported.upper / 1000f).toInt().coerceIn(1, 240_000)
        return Range(min, max).toClosedRange()
    }

    internal fun MediaCodecInfo.AudioCapabilities.getBitRateInKbits(): ClosedRange<Int> {
        val supported = bitrateRange ?: Range(6_000, 510_000)
        val min = floor(supported.lower / 1000f).toInt().coerceAtLeast(6)
        val max = ceil(supported.upper / 1000f).toInt().coerceIn(6, 510)
        return Range(min, max).toClosedRange()
    }
}
