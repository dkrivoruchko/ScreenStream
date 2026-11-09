package info.dvkr.screenstream.rtsp.internal.audio

import android.os.Handler
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class G711Codec(
    private val getAudioFrame: () -> AudioSource.Frame?,
    private val onAudioData: (MediaFrame.AudioFrame) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private var isPrepared = false
    private var isRunning = false

    @Volatile
    private var encoderHandler: Handler? = null

    private val encodeRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            try {
                val frame = getAudioFrame()
                if (frame != null) {
                    val encodedData = encode(frame.buffer, frame.size)
                    val audioFrame = MediaFrame.AudioFrame(
                        data = ByteBuffer.wrap(encodedData),
                        info = MediaFrame.Info(
                            offset = 0,
                            size = encodedData.size,
                            timestamp = frame.timestampUs.coerceAtLeast(0),
                            isKeyFrame = false
                        ),
                        releaseCallback = {}
                    )
                    onAudioData(audioFrame)
                    encoderHandler?.post(this)
                } else {
                    encoderHandler?.postDelayed(this, 5L)
                }
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /**
     * Currently, G711 is restricted to 8 kHz mono.
     *
     * @throws [IllegalArgumentException] if the sampleRate/isStereo are not 8000 Hz, 1 channel.
     */
    @Throws(IllegalArgumentException::class)
    fun prepare(sampleRate: Int, isStereo: Boolean) {
        require(sampleRate == 8000 && !isStereo) { "G711 codec only supports 8000 Hz, mono audio." }
        isPrepared = true
    }

    @Synchronized
    fun startEncoding(handler: Handler) {
        if (!isPrepared || isRunning) return
        encoderHandler = handler
        isRunning = true
        handler.post(encodeRunnable)
    }

    @Synchronized
    fun stopEncoding() {
        isRunning = false
        encoderHandler?.removeCallbacks(encodeRunnable)
        encoderHandler = null
    }

    /**
     * Convert PCM 16-bit LE data into A-Law G711 data.
     */
    private fun encode(buffer: ByteArray, size: Int): ByteArray {
        val sampleCount = size / 2
        val output = ByteArray(sampleCount)
        val inputBuffer = ByteBuffer.wrap(buffer, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(sampleCount) { i ->
            val sample = inputBuffer.short
            output[i] = linearToALawSample(sample)
        }
        return output
    }

    /**
     * Convert one 16-bit PCM sample to A-Law format.
     */
    private fun linearToALawSample(sampleIn: Short): Byte {
        val absSample = abs(sampleIn.toInt()).coerceAtMost(C_CLIP)
        val sign = if (sampleIn >= 0) 0x00 else 0x80
        val compressed = if (absSample >= 256) {
            val exponent = aLawCompressTable[(absSample shr 8) and 0x7F].toInt()
            val mantissa = (absSample shr (exponent + 3)) and 0x0F
            (exponent shl 4) or mantissa
        } else {
            absSample shr 4
        }
        return (compressed xor (sign xor 0x55)).toByte()
    }

    private val C_CLIP = 32635
    private val aLawCompressTable = byteArrayOf(
        1, 1, 2, 2, 3, 3, 3, 3,
        4, 4, 4, 4, 4, 4, 4, 4,
        5, 5, 5, 5, 5, 5, 5, 5,
        5, 5, 5, 5, 5, 5, 5, 5,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        6, 6, 6, 6, 6, 6, 6, 6,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7,
        7, 7, 7, 7, 7, 7, 7, 7
    )
}