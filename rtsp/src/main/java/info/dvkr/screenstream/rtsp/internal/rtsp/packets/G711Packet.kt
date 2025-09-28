package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame

internal class G711Packet : BaseRtpPacket(0, PAYLOAD_TYPE) {

    companion object {
        const val PAYLOAD_TYPE = 8 // PCMA
    }

    private var nextRtpTs: Long = -1L

    fun setAudioInfo(sampleRate: Int) {
        setClock(sampleRate.toLong())
        nextRtpTs = -1L
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        val fixedBuffer = mediaFrame.data.removeInfo(mediaFrame.info)
        val length = fixedBuffer.remaining()
        val maxPayload = MAX_PACKET_SIZE - RTP_HEADER_LENGTH
        val tsNs = mediaFrame.info.timestamp * 1000
        var sum = 0
        val frames = mutableListOf<RtpFrame>()

        var rtpTs = if (nextRtpTs >= 0) nextRtpTs else toRtpTimestampFromNs(tsNs)

        while (sum < length) {
            val size = if (length - sum < maxPayload) (length - sum) else maxPayload
            val buffer = getBuffer(size + RTP_HEADER_LENGTH)
            fixedBuffer.get(buffer, RTP_HEADER_LENGTH, size)
            setRtpTimestamp(buffer, rtpTs)
            updateSeq(buffer)

            if (sum + size >= length) {
                markPacket(buffer)
            }

            val rtpFrame = RtpFrame.Audio(buffer, rtpTs, buffer.size)
            sum += size
            frames.add(rtpFrame)

            // For PCMA mono, 1 byte == 1 sample
            rtpTs += size
        }
        nextRtpTs = rtpTs
        return frames
    }
}
