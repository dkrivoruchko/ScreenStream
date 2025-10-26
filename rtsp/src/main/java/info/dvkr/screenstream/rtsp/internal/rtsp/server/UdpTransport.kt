package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket

internal class UdpTransport(
    private val getVideoRtp: () -> UdpStreamSocket?,
    private val getAudioRtp: () -> UdpStreamSocket?,
) : RtpTransport {

    override suspend fun sendRtpPackets(trackId: Int, packets: List<RtpFrame>) {

        val sock = when (trackId) {
            RtpFrame.VIDEO_TRACK_ID -> getVideoRtp()
            else -> getAudioRtp()
        } ?: return

        for (packet in packets) sock.write(packet.buffer, 0, packet.length)
    }
}

