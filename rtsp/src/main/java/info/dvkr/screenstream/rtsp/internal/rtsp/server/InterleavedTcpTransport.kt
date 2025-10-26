package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket

internal class InterleavedTcpTransport(
    private val socket: TcpStreamSocket,
    private val channelForTrack: (Int) -> Int,
) : RtpTransport {

    override suspend fun sendRtpPackets(trackId: Int, packets: List<RtpFrame>) {
        val ch = channelForTrack(trackId)
        var i = 0
        val n = packets.size
        for (packet in packets) {
            socket.withLock {
                write(interleavedHeader(ch, packet.length), packet.buffer, 0, packet.length)
                val isAudio = (trackId == RtpFrame.AUDIO_TRACK_ID)
                val shouldFlush = isAudio || i == n - 1 || (i and 0x7) == 0
                if (shouldFlush) flush()
            }
            i++
        }
    }
}
