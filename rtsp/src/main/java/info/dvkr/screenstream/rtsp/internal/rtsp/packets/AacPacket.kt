package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and

internal class AacPacket : BaseRtpPacket(0, PAYLOAD_TYPE + 1) {

    companion object {
        const val PAYLOAD_TYPE = 96
    }

    fun setAudioInfo(sampleRate: Int) {
        setClock(sampleRate.toLong())
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
        parseAdtsHeader(fixedBuffer)?.let { headerLen ->
            if (fixedBuffer.remaining() > headerLen) {
                fixedBuffer.position(fixedBuffer.position() + headerLen)
                fixedBuffer = fixedBuffer.slice()
            }
        }
        val length = fixedBuffer.remaining()
        val maxPayload = MAX_PACKET_SIZE - (RTP_HEADER_LENGTH + 4)
        val ts = mediaFrame.info.timestamp * 1000
        var sum = 0
        val frames = mutableListOf<RtpFrame>()

        while (sum < length) {
            val size = if (length - sum < maxPayload) (length - sum) else maxPayload
            val buffer = getBuffer(size + RTP_HEADER_LENGTH + 4)
            fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 4, size)

            buffer[RTP_HEADER_LENGTH] = 0x00
            buffer[RTP_HEADER_LENGTH + 1] = 0x10

            val sizeBits = size shl 3
            buffer[RTP_HEADER_LENGTH + 2] = (sizeBits ushr 5).toByte()
            buffer[RTP_HEADER_LENGTH + 3] = (sizeBits shl 3).toByte()

            buffer[RTP_HEADER_LENGTH + 3] = buffer[RTP_HEADER_LENGTH + 3] and 0xF8.toByte()

            val rtpTs = updateTimeStamp(buffer, ts)
            updateSeq(buffer)

            if (sum + size >= length) {
                markPacket(buffer)
            }

            val rtpFrame = RtpFrame.Audio(buffer, rtpTs, buffer.size)
            sum += size
            frames.add(rtpFrame)
        }
        return frames
    }

    private fun parseAdtsHeader(buf: ByteBuffer): Int? {
        if (buf.remaining() < 7) return null
        val base = buf.position()
        val b0 = buf.get(base).toInt() and 0xFF
        val b1 = buf.get(base + 1).toInt() and 0xFF
        // 12-bit syncword 0xFFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return null
        val layer = (b1 ushr 1) and 0x3
        if (layer != 0) return null // must be 00
        val protectionAbsent = (b1 and 0x01) == 1
        val headerLen = if (protectionAbsent) 7 else 9
        if (!protectionAbsent && buf.remaining() < 9) return null
        // Optional: Validate aac_frame_length to guard against corruption
        if (buf.remaining() >= headerLen + 2) {
            val b3 = buf.get(base + 3).toInt() and 0xFF
            val b4 = buf.get(base + 4).toInt() and 0xFF
            val b5 = buf.get(base + 5).toInt() and 0xFF
            val frameLen = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 ushr 5) and 0x07)
            if (frameLen < headerLen || frameLen > buf.remaining()) return null
        }
        return headerLen
    }
}
