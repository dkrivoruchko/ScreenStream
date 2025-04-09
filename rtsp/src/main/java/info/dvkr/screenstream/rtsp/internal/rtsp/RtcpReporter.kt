package info.dvkr.screenstream.rtsp.internal.rtsp

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

internal class RtcpReporter(
    scope: CoroutineScope,
    private val protocol: Protocol,
    private val writeToTcpSocket: suspend (ByteArray, ByteArray) -> Unit,
    private val videoUdpSocket: UdpStreamSocket?,
    private val audioUdpSocket: UdpStreamSocket?,
    ssrcVideo: Long,
    ssrcAudio: Long
) {
    private companion object {
        private const val REPORT_PACKET_LENGTH = 28
        private const val REPORT_INTERVAL_MS = 5000L
    }

    private class TrackInfo(
        val buffer: ByteArray = ByteArray(REPORT_PACKET_LENGTH),
        var packetCount: Long = 0L,
        var octetCount: Long = 0L,
        var lastReportTimeMs: Long = 0L,
        var lastRtpTimestamp: Long = 0L
    )

    private val videoTrack = TrackInfo().apply {
        buffer[0] = 0x80.toByte()
        buffer[1] = 200.toByte()
        buffer.setLong(REPORT_PACKET_LENGTH / 4 - 1L, 2, 4)
        buffer.setLong(ssrcVideo, 4, 8)
    }

    private val audioTrack = TrackInfo().apply {
        buffer[0] = 0x80.toByte()
        buffer[1] = 200.toByte()
        buffer.setLong(REPORT_PACKET_LENGTH / 4 - 1L, 2, 4)
        buffer.setLong(ssrcAudio, 4, 8)
    }

    private val lock = Mutex()
    private var closed = false

    private val periodicJob = scope.launch {
        while (isActive) {
            lock.withLock {
                if (closed) return@withLock
                sendPeriodicReport(videoTrack, 0)
                sendPeriodicReport(audioTrack, 1)
            }
            delay(REPORT_INTERVAL_MS)
        }
    }

    internal suspend fun update(rtpFrame: RtpFrame) = lock.withLock {
        if (closed) return@withLock

        when (rtpFrame) {
            is RtpFrame.Video -> videoTrack
            is RtpFrame.Audio -> audioTrack
        }.apply {
            packetCount++
            octetCount += (rtpFrame.length - 12).coerceAtLeast(0)
            lastRtpTimestamp = rtpFrame.timeStamp
        }
    }

    internal suspend fun close() = lock.withLock {
        XLog.v(getLog("close"))

        if (closed) return@withLock
        closed = true

        periodicJob.cancel()

        runCatching { sendBye(0, videoTrack.buffer) }.onFailure { XLog.w(getLog("close", "Failed to send BYE for video track"), it) }
        runCatching { sendBye(1, audioTrack.buffer) }.onFailure { XLog.w(getLog("close", "Failed to send BYE for audio track"), it) }

        videoUdpSocket?.close()
        audioUdpSocket?.close()

        XLog.v(getLog("close", "Done"))
    }

    private suspend fun sendPeriodicReport(track: TrackInfo, trackId: Int) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - track.lastReportTimeMs < REPORT_INTERVAL_MS) return

        track.lastReportTimeMs = nowMs

        val ntpSec = (nowMs / 1000L) + 2208988800L
        val ntpFrac = ((nowMs % 1000L) * 0x100000000L) / 1000L

        track.buffer.setLong(ntpSec, 8, 12)
        track.buffer.setLong(ntpFrac, 12, 16)
        track.buffer.setLong(track.lastRtpTimestamp, 16, 20)
        track.buffer.setLong(track.packetCount, 20, 24)
        track.buffer.setLong(track.octetCount, 24, 28)

        when (protocol) {
            Protocol.TCP -> try {
                val tcpHeader = byteArrayOf('$'.code.toByte(), (2 * trackId + 1).toByte(), 0, REPORT_PACKET_LENGTH.toByte())
                writeToTcpSocket(tcpHeader, track.buffer)
                XLog.v(getLog("sendPeriodicReport", "TCP track=$trackId, size=28"))
            } catch (e: IOException) {
                XLog.w(getLog("sendPeriodicReport", "TCP track=$trackId failed: ${e.message}"), e)
            }

            Protocol.UDP -> try {
                when (trackId) {
                    0 -> videoUdpSocket?.write(track.buffer)
                    else -> audioUdpSocket?.write(track.buffer)
                }
                XLog.v(getLog("sendPeriodicReport", "UDP track=$trackId, size=28"))
            } catch (e: IOException) {
                XLog.w(getLog("sendPeriodicReport", "UDP track=$trackId failed: ${e.message}"), e)
            }
        }
    }

    private suspend fun sendBye(trackId: Int, buffer: ByteArray) {
        val ssrc = buffer.sliceArray(4..7)
        val byePacket = byteArrayOf(0x81.toByte(), 203.toByte(), 0x00, 0x01, ssrc[0], ssrc[1], ssrc[2], ssrc[3])

        when (protocol) {
            Protocol.TCP -> {
                val tcpHeader = byteArrayOf('$'.code.toByte(), (2 * trackId + 1).toByte(), 0, byePacket.size.toByte())
                writeToTcpSocket(tcpHeader, byePacket)
            }

            Protocol.UDP -> runCatching {
                when (trackId) {
                    0 -> videoUdpSocket?.write(byePacket)
                    else -> audioUdpSocket?.write(byePacket)
                }
            }
        }
        XLog.d(getLog("sendBye", "RTCP BYE sent track=$trackId, SSRC=${ssrc.joinToString { it.toUByte().toString() }}"))
    }

    private fun ByteArray.setLong(value: Long, begin: Int, end: Int) {
        var tmp = value
        for (i in (end - 1) downTo begin) {
            this[i] = (tmp and 0xFF).toByte()
            tmp = tmp ushr 8
        }
    }
}