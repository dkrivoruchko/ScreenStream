package info.dvkr.screenstream.mjpeg.internal.audio

import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.max

internal interface MjpegAudioSource {

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
            val DEFAULT_OPUS = Params(sampleRate = OPUS_SAMPLE_RATE, isStereo = true, bitrate = 128 * 1000)
        }

        @Throws(IllegalArgumentException::class)
        internal fun calculateBufferSizeInBytes(desiredBufferDurationMs: Int = 40): Int {
            require(desiredBufferDurationMs > 0) { "desiredBufferDurationMs must be positive" }

            val bytesPerSample = 2
            val channelCount = if (isStereo) 2 else 1
            val bytesPerFrame = bytesPerSample * channelCount

            val rawSize = (sampleRate * (desiredBufferDurationMs / 1000.0) * bytesPerFrame).toInt()
            val remainder = rawSize % bytesPerFrame
            val alignedSize = if (remainder == 0) rawSize else rawSize + bytesPerFrame - remainder

            val channelMask = if (isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

            return max(alignedSize, minBufferSize)
        }
    }

    val isRunning: Boolean

    @Throws
    fun checkIfConfigurationSupported()
    fun start()
    fun stop()
}
