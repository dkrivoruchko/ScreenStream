package info.dvkr.screenstream.rtsp.internal.rtsp.transport

import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket

internal class InterleavedTcpTransport(
    private val socket: TcpStreamSocket,
    private val channelForTrack: (Int) -> Int,
) : RtpTransport {

    override suspend fun sendRtpPackets(trackId: Int, packets: List<RtpFrame>) {
        val ch = channelForTrack(trackId)
        socket.withLock {
            for (p in packets) {
                write(p.getTcpHeaderFor(ch), p.buffer, 0, p.length)
            }
            flush()
        }
    }

    private fun RtpFrame.getTcpHeaderFor(channel: Int): ByteArray = byteArrayOf(
        '$'.code.toByte(), channel.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()
    )
}

