package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import androidx.annotation.CallSuper
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.random.Random

internal abstract class BaseRtpPacket(private var clock: Long, private val payloadType: Int) {
    companion object {
        const val MTU = 1200
        const val RTP_HEADER_LENGTH = 12
        const val VIDEO_CLOCK_FREQUENCY = 90000L
        protected const val MAX_PACKET_SIZE = MTU - 28
    }

    private var seq = Random.nextInt(0, 0x10000)
    private var ssrc = 0

    abstract fun createPacket(mediaFrame: MediaFrame): List<RtpFrame>

    @CallSuper
    open fun reset() {
        seq = Random.nextInt(0, 0x10000)
        ssrc = 0
    }

    fun setSSRC(ssrc: Long) {
        this.ssrc = (ssrc and 0xFFFFFFFF).toInt()
    }

    fun setInitialSeq(initial: Int) {
        seq = initial and 0xFFFF
    }

    protected fun setClock(clock: Long) {
        this.clock = clock
    }

    protected fun getBuffer(size: Int): ByteArray {
        val buffer = ByteArray(size)
        buffer[0] = 0x80.toByte()
        buffer[1] = payloadType.toByte()
        buffer.setLong(ssrc.toLong(), 8, 12)
        buffer[1] = buffer[1] and 0x7F
        return buffer
    }

    protected fun updateTimeStamp(buffer: ByteArray, timestamp: Long): Long {
        val ts = timestamp * clock / 1_000_000_000L
        buffer.setLong(ts, 4, 8)
        return ts
    }

    protected fun updateSeq(buffer: ByteArray) {
        seq = (seq + 1) and 0xFFFF
        buffer.setLong(seq.toLong(), 2, 4)
    }

    protected fun markPacket(buffer: ByteArray) {
        buffer[1] = buffer[1] or 0x80.toByte()
    }

    protected fun ByteBuffer.removeInfo(info: MediaFrame.Info): ByteBuffer {
        try {
            position(info.offset)
            limit(info.offset + info.size)
        } catch (_: Exception) {
        }
        return slice()
    }

    private fun ByteArray.setLong(n: Long, begin: Int, end: Int) {
        var value = n
        for (i in end - 1 downTo begin) {
            this[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
    }
}