package info.dvkr.screenstream.rtsp.internal.rtsp.packets

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtpFrame

internal class OpusPacket : BaseRtpPacket(0, PAYLOAD_TYPE + 1) {

    companion object {
        const val PAYLOAD_TYPE = 96
    }

    private var rtpTimestamp: Long = 0
    private var tsInitialized: Boolean = false
    private var samplesPerPacket: Int = 960 // default for 20 ms @ 48 kHz

    fun setAudioInfo(sampleRate: Int) {
        setClock(48_000)
        samplesPerPacket = (sampleRate / 50)
        if (samplesPerPacket <= 0) samplesPerPacket = 960
        tsInitialized = false
        rtpTimestamp = 0
    }

    override fun createPacket(mediaFrame: MediaFrame): List<RtpFrame> {
        // RFC 7587: one Opus packet per RTP packet (no fragmentation)
        val fixed = mediaFrame.data.removeInfo(mediaFrame.info)
        val length = fixed.remaining()
        val tsNs = mediaFrame.info.timestamp * 1000
        val buffer = getBuffer(length + RTP_HEADER_LENGTH)
        fixed.get(buffer, RTP_HEADER_LENGTH, length)
        if (!tsInitialized) {
            rtpTimestamp = toRtpTimestampFromNs(tsNs)
            tsInitialized = true
        } else {
            rtpTimestamp += samplesPerPacket.toLong()
        }
        setRtpTimestamp(buffer, rtpTimestamp)
        updateSeq(buffer)
        return listOf(RtpFrame.Audio(buffer, rtpTimestamp, buffer.size))
    }
}
