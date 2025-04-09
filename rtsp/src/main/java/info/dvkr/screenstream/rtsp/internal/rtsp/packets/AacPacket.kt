package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame
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
        val fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
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
}