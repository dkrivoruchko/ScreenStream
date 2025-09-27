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
import kotlin.coroutines.cancellation.CancellationException

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

        // Keep SRs frequent for better clock sync on strict clients (e.g., VLC 4.x live555).
        private const val REPORT_INTERVAL_MS = 2000L
    }

    class TrackInfo(
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

    private val audioEnabled = ssrcAudio != 0L

    private val lock = Mutex()
    private var closed = false

    private val periodicJob = scope.launch {
        while (isActive) {
            lock.withLock {
                if (closed) return@withLock
                // Only send SR after we have at least one RTP packet (valid timestamp/counts)
                if (videoTrack.packetCount > 0) sendPeriodicReport(videoTrack, 0)
                if (audioEnabled && audioTrack.packetCount > 0) sendPeriodicReport(audioTrack, 1)
            }
            delay(REPORT_INTERVAL_MS)
        }
    }

    internal suspend fun update(rtpFrame: RtpFrame) = lock.withLock {
        if (closed) return@withLock

        val track = when (rtpFrame) {
            is RtpFrame.Video -> videoTrack
            is RtpFrame.Audio -> audioTrack
        }
        val wasZero = (track.packetCount == 0L)
        track.packetCount++
        track.octetCount += (rtpFrame.length - 12).coerceAtLeast(0)
        track.lastRtpTimestamp = rtpFrame.timeStamp
        // Send an immediate first SR after the very first RTP packet to establish NTP<->RTP mapping early.
        if (wasZero) {
            val tid = if (rtpFrame is RtpFrame.Video) 0 else 1
            sendPeriodicReport(track, tid)
        }
    }

    internal suspend fun close() = lock.withLock {
        XLog.v(getLog("close"))

        if (closed) return@withLock
        closed = true

        periodicJob.cancel()

        runCatching { sendBye(0, videoTrack.buffer) }.onFailure {
            if (it is CancellationException)
                XLog.v(getLog("close", "Failed to send BYE for video track: ${it.message}"), it)
            else
                XLog.w(getLog("close", "Failed to send BYE for video track: ${it.message}"), it)
        }
        if (audioEnabled) runCatching { sendBye(1, audioTrack.buffer) }.onFailure {
            if (it is CancellationException)
                XLog.v(getLog("close", "Failed to send BYE for audio track: ${it.message}"), it)
            else
                XLog.w(getLog("close", "Failed to send BYE for audio track: ${it.message}"), it)
        }

        videoUdpSocket?.close()
        audioUdpSocket?.close()

        XLog.v(getLog("close", "Done"))
    }

    internal suspend fun setSsrcVideo(newSsrc: Long) = lock.withLock {
        videoTrack.apply {
            buffer.setLong(newSsrc, 4, 8)
            packetCount = 0
            octetCount = 0
            lastReportTimeMs = 0
            lastRtpTimestamp = 0
        }
    }

    internal suspend fun setSsrcAudio(newSsrc: Long) = lock.withLock {
        audioTrack.apply {
            buffer.setLong(newSsrc, 4, 8)
            packetCount = 0
            octetCount = 0
            lastReportTimeMs = 0
            lastRtpTimestamp = 0
        }
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
                val lenHi = ((REPORT_PACKET_LENGTH ushr 8) and 0xFF).toByte()
                val lenLo = (REPORT_PACKET_LENGTH and 0xFF).toByte()
                val tcpHeader = byteArrayOf('$'.code.toByte(), (2 * trackId + 1).toByte(), lenHi, lenLo)
                writeToTcpSocket(tcpHeader, track.buffer)
                XLog.v(getLog("sendPeriodicReport", "TCP track=$trackId, size=28"))
            } catch (t: Throwable) {
                if (t is CancellationException)
                    XLog.v(getLog("sendPeriodicReport", "TCP track=$trackId failed: ${t.message}"), t)
                else
                    XLog.w(getLog("sendPeriodicReport", "TCP track=$trackId failed: ${t.message}"), t)
            }

            Protocol.UDP -> try {
                when (trackId) {
                    0 -> videoUdpSocket?.write(track.buffer)
                    else -> audioUdpSocket?.write(track.buffer)
                }
                XLog.v(getLog("sendPeriodicReport", "UDP track=$trackId, size=28"))
            } catch (e: IOException) {
                XLog.d(getLog("sendPeriodicReport", "UDP track=$trackId failed: ${e.message}"), e)
            }
        }
    }

    private suspend fun sendBye(trackId: Int, buffer: ByteArray) {
        val ssrc = buffer.sliceArray(4..7)
        val byePacket = byteArrayOf(0x81.toByte(), 203.toByte(), 0x00, 0x01, ssrc[0], ssrc[1], ssrc[2], ssrc[3])

        when (protocol) {
            Protocol.TCP -> runCatching {
                val lenHi = ((byePacket.size ushr 8) and 0xFF).toByte()
                val lenLo = (byePacket.size and 0xFF).toByte()
                val tcpHeader = byteArrayOf('$'.code.toByte(), (2 * trackId + 1).toByte(), lenHi, lenLo)
                writeToTcpSocket(tcpHeader, byePacket)
            }.onFailure { XLog.w(getLog("sendBye", "TCP track=$trackId failed: ${it.message}"), it) }

            Protocol.UDP -> runCatching {
                when (trackId) {
                    0 -> videoUdpSocket?.write(byePacket)
                    else -> audioUdpSocket?.write(byePacket)
                }
            }.onFailure { XLog.w(getLog("sendBye", "UDP track=$trackId failed: ${it.message}"), it) }
        }
        XLog.d(getLog("sendBye", "RTCP BYE attempted track=$trackId, SSRC=${ssrc.joinToString { it.toUByte().toString() }}"))
    }

    private fun ByteArray.setLong(value: Long, begin: Int, end: Int) {
        var tmp = value
        for (i in (end - 1) downTo begin) {
            this[i] = (tmp and 0xFF).toByte()
            tmp = tmp ushr 8
        }
    }
}
