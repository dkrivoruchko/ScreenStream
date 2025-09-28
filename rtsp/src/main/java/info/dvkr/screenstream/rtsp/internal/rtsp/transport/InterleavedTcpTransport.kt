package info.dvkr.screenstream.rtsp.internal.rtsp.transport

import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket

internal class InterleavedTcpTransport(
    private val socket: TcpStreamSocket,
    private val channelForTrack: (Int) -> Int,
) : RtpTransport {

    override suspend fun sendRtpPackets(trackId: Int, packets: List<RtpFrame>) {
        val ch = channelForTrack(trackId)
        socket.withLock {
            for (packet in packets) write(interleavedHeader(ch, packet.length), packet.buffer, 0, packet.length)
            flush()
        }
    }
}
