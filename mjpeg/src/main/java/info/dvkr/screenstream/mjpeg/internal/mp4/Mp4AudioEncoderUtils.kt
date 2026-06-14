package info.dvkr.screenstream.mjpeg.internal.mp4

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList

internal const val AAC_MIME_TYPE: String = "audio/mp4a-latm"
internal const val AAC_SAMPLE_RATE: Int = 48_000

internal data class Mp4AudioEncoderInfo(
    val name: String,
    val isCBRModeSupported: Boolean,
    val capabilities: MediaCodecInfo.CodecCapabilities
)

internal object Mp4AudioEncoderUtils {
    val selectedAacEncoder: Mp4AudioEncoderInfo?
        get() = availableAacEncoders.firstOrNull()

    private val availableAacEncoders: List<Mp4AudioEncoderInfo> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder }
            .filter { encoder -> encoder.supportedTypes.any { it.equals(AAC_MIME_TYPE, ignoreCase = true) } }
            .sortedBy { getCodecScore(it.name) }
            .map { encoder ->
                Mp4AudioEncoderInfo(
                    name = encoder.name,
                    isCBRModeSupported = encoder.isCbrCapable(AAC_MIME_TYPE),
                    capabilities = encoder.getCapabilitiesForType(AAC_MIME_TYPE)
                )
            }
            .sortedWith(compareBy({ if (it.isCBRModeSupported) 0 else 1 }, { getCodecScore(it.name) }))
    }

    internal fun aacLcAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
        val sampleRateIndex = when (sampleRate) {
            96_000 -> 0
            88_200 -> 1
            64_000 -> 2
            48_000 -> 3
            44_100 -> 4
            32_000 -> 5
            24_000 -> 6
            22_050 -> 7
            16_000 -> 8
            12_000 -> 9
            11_025 -> 10
            8_000 -> 11
            else -> 3
        }
        val config = ((2 and 0x1F) shl 11) or ((sampleRateIndex and 0x0F) shl 7) or ((channelCount and 0x0F) shl 3)
        return byteArrayOf((config ushr 8).toByte(), config.toByte())
    }

    private fun MediaCodecInfo.isCbrCapable(mimeType: String): Boolean =
        runCatching {
            getCapabilitiesForType(mimeType)
                ?.encoderCapabilities
                ?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) ?: false
        }.getOrDefault(false)

    private fun getCodecScore(name: String): Int = when (name.lowercase()) {
        "c2.sec.aac.encoder" -> -2
        "omx.google.aac.encoder" -> -1
        else -> 0
    }
}
