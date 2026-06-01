package info.dvkr.screenstream.mjpeg.internal.audio

import android.media.MediaCodecInfo
import androidx.compose.runtime.Immutable

internal const val OPUS_MIME_TYPE: String = "audio/opus"
internal const val OPUS_SAMPLE_RATE: Int = 48000

internal data class EncodedAudioPacket(
    val data: ByteArray,
    val durationSamples: Int
)

internal data class MjpegAudioPacket(
    val generation: Long,
    val data: ByteArray,
    val durationSamples: Int
)

@Immutable
internal data class MjpegAudioEncoderInfo(
    val name: String,
    val vendorName: String,
    val isHardwareAccelerated: Boolean,
    val isCBRModeSupported: Boolean,
    val capabilities: MediaCodecInfo.CodecCapabilities
)
