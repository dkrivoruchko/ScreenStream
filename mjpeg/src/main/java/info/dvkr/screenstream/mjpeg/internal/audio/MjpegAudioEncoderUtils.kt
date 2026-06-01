package info.dvkr.screenstream.mjpeg.internal.audio

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import androidx.core.util.toClosedRange
import kotlin.math.ceil
import kotlin.math.floor

internal object MjpegAudioEncoderUtils {

    private val allAvailableEncoders: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { it.isEncoder }
    }

    val availableOpusEncoders: List<MjpegAudioEncoderInfo> by lazy {
        allAvailableEncoders
            .filter { encoder -> encoder.supportedTypes.any { it.equals(OPUS_MIME_TYPE, ignoreCase = true) } }
            .sortedByDescending { getCodecScore(it.name) }
            .map { encoder ->
                MjpegAudioEncoderInfo(
                    name = encoder.name,
                    vendorName = encoder.name.asVendorName(),
                    isHardwareAccelerated = encoder.isHardwareAcceleratedCompat(),
                    isCBRModeSupported = encoder.isCbrCapable(OPUS_MIME_TYPE),
                    capabilities = encoder.getCapabilitiesForType(OPUS_MIME_TYPE)
                )
            }
            .sortedWith(compareBy({ if (it.isHardwareAccelerated) 0 else 1 }, { if (it.isCBRModeSupported) 0 else 1 }))
    }

    val selectedOpusEncoder: MjpegAudioEncoderInfo?
        get() = availableOpusEncoders.firstOrNull()

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
        "c2.android.opus.encoder" -> -1
        "omx.google.opus.encoder" -> -1
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

    internal fun MediaCodecInfo.AudioCapabilities.getBitRateInKbits(): ClosedRange<Int> {
        val supported = bitrateRange ?: Range(6_000, 510_000)
        val min = floor(supported.lower / 1000f).toInt().coerceAtLeast(6)
        val max = ceil(supported.upper / 1000f).toInt().coerceIn(6, 510)
        return Range(min, max).toClosedRange()
    }
}
