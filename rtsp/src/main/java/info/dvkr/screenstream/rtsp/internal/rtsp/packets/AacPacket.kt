package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import java.nio.ByteBuffer
import kotlin.experimental.and

/**
 * RFC 3640 for AAC.
 */
internal class AacPacket : BaseRtpPacket(0, PAYLOAD_TYPE + 1) {

    companion object {
        const val PAYLOAD_TYPE = 96
    }

    fun setAudioInfo(sampleRate: Int) {
        setClock(sampleRate.toLong())
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        var fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
        // Strip ADTS header if detected
        parseAdtsHeader(fixedBuffer)?.let { headerLen ->
            if (fixedBuffer.remaining() > headerLen) {
                fixedBuffer.position(fixedBuffer.position() + headerLen)
                fixedBuffer = fixedBuffer.slice()
            }
        }
        val length = fixedBuffer.remaining()
        // AU headers take 4 extra bytes in each RTP chunk.
        val maxPayload = MAX_PACKET_SIZE - (RTP_HEADER_LENGTH + 4)
        val ts = mediaFrame.info.timestamp * 1000
        var sum = 0
        val frames = mutableListOf<RtpFrame>()

        while (sum < length) {
            val size = if (length - sum < maxPayload) (length - sum) else maxPayload
            val buffer = getBuffer(size + RTP_HEADER_LENGTH + 4)
            fixedBuffer.get(buffer, RTP_HEADER_LENGTH + 4, size)

            // AU-headers-length field (16 bits). 13 bits for AU-size, 3 bits for AU-Index-delta
            buffer[RTP_HEADER_LENGTH] = 0.toByte()
            buffer[RTP_HEADER_LENGTH + 1] = 0x10.toByte() // 16 bits total

            // AU-size
            buffer[RTP_HEADER_LENGTH + 2] = (size shr 5).toByte()
            buffer[RTP_HEADER_LENGTH + 3] = (size shl 3).toByte()

            // Clear last 3 bits (for AU-Index), set to 0
            buffer[RTP_HEADER_LENGTH + 3] = buffer[RTP_HEADER_LENGTH + 3] and 0xF8.toByte()
            // buffer[RTP_HEADER_LENGTH + 3] |= 0x00

            val rtpTs = updateTimeStamp(buffer, ts)
            updateSeq(buffer)

            // Mark only if it is the last chunk of this frame
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