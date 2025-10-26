package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.RtpFrame

internal interface RtpTransport {
    suspend fun sendRtpPackets(trackId: Int, packets: List<RtpFrame>)
}

