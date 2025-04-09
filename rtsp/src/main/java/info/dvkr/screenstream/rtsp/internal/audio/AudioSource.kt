package info.dvkr.screenstream.rtsp.internal.audio

import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.max

internal interface AudioSource {

    class Frame(val buffer: ByteArray, val size: Int, val timestampUs: Long)

    data class Params(
        val sampleRate: Int,
        val isStereo: Boolean,
        val bitrate: Int,
        val echoCanceler: Boolean = true,
        val noiseSuppressor: Boolean = false,
        val autoGainControl: Boolean = false
    ) {
        companion object {
            // CommandsManager.AUDIO_SAMPLING_RATES
            val DEFAULT = Params(sampleRate = 44100, isStereo = true, bitrate = 128 * 1000)

            // G711 only supports sample rate 8kHz and mono channel
            val DEFAULT_G711 = Params(sampleRate = 8000, isStereo = false, bitrate = 64 * 1000)

            //  Opus only supports sample rate 48kHz and stereo channel
            val DEFAULT_OPUS = Params(sampleRate = 48000, isStereo = true, bitrate = 128 * 1000)
        }

        internal fun calculateBufferSizeInBytes(desiredBufferDurationMs: Int = 40): Int {
            require(desiredBufferDurationMs > 0) { "desiredBufferDurationMs must be positive" }

            val bytesPerSample = 2 // 2 bytes per sample for 16-bit PCM
            val channelCount = if (isStereo) 2 else 1
            val bytesPerFrame = bytesPerSample * channelCount

            val rawSize = (sampleRate * (desiredBufferDurationMs / 1000.0) * bytesPerFrame).toInt()
            val remainder = rawSize % bytesPerFrame
            val alignedSize = if (remainder == 0) rawSize else (rawSize + bytesPerFrame - remainder)

            val channelMask = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

            return max(alignedSize, minBufferSize)
        }
    }

    val isRunning: Boolean

    @Throws(Error::class)
    fun checkIfConfigurationSupported()
    fun start()
    fun stop()
}