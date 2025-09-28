package info.dvkr.screenstream.rtsp.internal.rtsp

import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Periodically emits RTCP Sender Reports (and a BYE on close) for video/audio tracks.
 * - Sends an immediate first SR after the very first RTP packet to establish NTP↔RTP mapping early.
 * - Continues at a fixed cadence while tracks are active; suppresses when idle.
 * - Works over both UDP (per‑track sockets) and interleaved TCP (even/odd channels).
 */
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
        private const val REPORT_INTERVAL_MS = 2000L
    }

    class TrackInfo(
        val buffer: ByteArray = ByteArray(REPORT_PACKET_LENGTH),
        var packetCount: Long = 0L,
        var octetCount: Long = 0L,
        var lastReportTimeMs: Long = 0L,
        var lastRtpTimestamp: Long = 0L,
        var lastRtpUpdateAtMs: Long = 0L
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
                val now = System.currentTimeMillis()
                val activeVideo = videoTrack.packetCount > 0 && (now - videoTrack.lastRtpUpdateAtMs) <= (2 * REPORT_INTERVAL_MS + 1000L)
                val activeAudio =
                    audioEnabled && audioTrack.packetCount > 0 && (now - audioTrack.lastRtpUpdateAtMs) <= (2 * REPORT_INTERVAL_MS + 1000L)

                if (activeVideo) sendPeriodicReport(videoTrack, 0)
                if (activeAudio) sendPeriodicReport(audioTrack, 1)

                // If no active tracks for a while, suppress SRs; do not close here to allow quick resume.
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
        track.lastRtpUpdateAtMs = System.currentTimeMillis()
        // Send an immediate first SR after the very first RTP packet to establish NTP<->RTP mapping early.
        if (wasZero) {
            val tid = if (rtpFrame is RtpFrame.Video) 0 else 1
            sendPeriodicReport(track, tid)
        }
    }

    internal suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true

        periodicJob.cancel()

        runCatching { sendBye(0, videoTrack.buffer) }
        if (audioEnabled) runCatching { sendBye(1, audioTrack.buffer) }

        videoUdpSocket?.close()
        audioUdpSocket?.close()
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
                val tcpHeader = interleavedHeader(2 * trackId + 1, REPORT_PACKET_LENGTH)
                writeToTcpSocket(tcpHeader, track.buffer)
            } catch (t: Throwable) {
                // swallow
            }

            Protocol.UDP -> try {
                when (trackId) {
                    0 -> videoUdpSocket?.write(track.buffer)
                    else -> audioUdpSocket?.write(track.buffer)
                }
            } catch (e: IOException) {
                // swallow
            }
        }
    }

    private suspend fun sendBye(trackId: Int, buffer: ByteArray) {
        val ssrc = buffer.sliceArray(4..7)
        val byePacket = byteArrayOf(0x81.toByte(), 203.toByte(), 0x00, 0x01, ssrc[0], ssrc[1], ssrc[2], ssrc[3])

        when (protocol) {
            Protocol.TCP -> runCatching {
                val tcpHeader = interleavedHeader(2 * trackId + 1, byePacket.size)
                writeToTcpSocket(tcpHeader, byePacket)
            }

            Protocol.UDP -> runCatching {
                when (trackId) {
                    0 -> videoUdpSocket?.write(byePacket)
                    else -> audioUdpSocket?.write(byePacket)
                }
            }
        }
    }

    private fun ByteArray.setLong(value: Long, begin: Int, end: Int) {
        var tmp = value
        for (i in (end - 1) downTo begin) {
            this[i] = (tmp and 0xFF).toByte()
            tmp = tmp ushr 8
        }
    }
}
