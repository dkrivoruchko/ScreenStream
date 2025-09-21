package info.dvkr.screenstream.rtsp.internal.rtsp.server

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.CommandsManager
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

internal class RtspServerConnection(
    private val tcpStreamSocket: TcpStreamSocket,
    private val commandsManager: CommandsManager,
    private val videoParams: AtomicReference<RtspClient.VideoParams?>,
    private val audioParams: AtomicReference<RtspClient.AudioParams?>,
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit,
    private val requiredProtocol: Protocol
) {
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val TRACK_ID_REGEX = Regex("trackID=(\\d+)")

    private var protocol: Protocol = Protocol.TCP
    private var sessionId: String = SecureRandom().nextInt().toString(16)
    internal var isStreaming: Boolean = false

    // Interleaved channels for TCP mode (video 0/1, audio 2/3 by default)
    private var videoCh: Pair<Int, Int> = 0 to 1
    private var audioCh: Pair<Int, Int> = 2 to 3

    // UDP sockets per track
    private var videoRtpSocket: UdpStreamSocket? = null
    private var videoRtcpSocket: UdpStreamSocket? = null
    private var audioRtpSocket: UdpStreamSocket? = null
    private var audioRtcpSocket: UdpStreamSocket? = null

    // Packetizers
    private var videoPacketizer: BaseRtpPacket? = null
    private var audioPacketizer: BaseRtpPacket? = null

    // RTCP reporting
    private var rtcpReporter: RtcpReporter? = null
    private var videoSsrc: Long = 0L
    private var audioSsrc: Long = 0L
    private var initialVideoSeq: Int? = null
    private var initialAudioSeq: Int? = null

    private var clientJob: Job? = null
    private var videoWriterJob: Job? = null
    private var audioWriterJob: Job? = null

    // Per-client queues
    private val videoQueue = kotlinx.coroutines.channels.Channel<VideoBlob>(capacity = 8)
    private val audioQueue = kotlinx.coroutines.channels.Channel<AudioBlob>(capacity = 32)

    // Metrics for slow-client detection
    private var videoDrops: Long = 0
    private var audioDrops: Long = 0
    private var lastDropAtMs: Long = 0L

    internal fun enqueueVideo(blob: VideoBlob): Boolean {
        // Prefer dropping non-key frames if full; if keyframe, try to clear and accept
        if (!videoQueue.trySend(blob).isSuccess) {
            if (blob.isKeyFrame) {
                // Drain queue to prioritize keyframe
                while (videoQueue.tryReceive().isSuccess) { /* drain */
                }
                if (!videoQueue.trySend(blob).isSuccess) {
                    videoDrops++
                    lastDropAtMs = System.currentTimeMillis()
                    return false
                }
            } else {
                // Drop non-key
                videoDrops++
                lastDropAtMs = System.currentTimeMillis()
                return false
            }
        }
        return true
    }

    internal fun enqueueAudio(blob: AudioBlob): Boolean {
        // Drop newest if full; keep latency bounded
        if (!audioQueue.trySend(blob).isSuccess) {
            // Drop oldest and try again
            audioQueue.tryReceive().getOrNull()
            if (!audioQueue.trySend(blob).isSuccess) {
                audioDrops++
                lastDropAtMs = System.currentTimeMillis()
                return false
            }
        }
        return true
    }

    internal fun start() {
        XLog.d(getLog("start"))
        clientJob = scope.launch {
            try {
                onEvent(RtspStreamingService.InternalEvent.RtspServerOnClientConnected(this@RtspServerConnection))
                commandLoop()
            } catch (_: CancellationException) {
            } catch (t: Throwable) {
                XLog.e(getLog("start", "error: ${t.message}"), t)
            } finally {
                stop()
            }
        }
        videoWriterJob = scope.launch { videoWriterLoop() }
        audioWriterJob = scope.launch { audioWriterLoop() }
    }

    private suspend fun commandLoop() {
        while (scope.isActive && tcpStreamSocket.withLock { isConnected() }) {
            val request = tcpStreamSocket.withLock { readRequestHeaders() } ?: break
            val (method, cSeq) = commandsManager.parseRequest(request)
            when (method) {
                CommandsManager.Method.OPTIONS -> tcpStreamSocket.withLock {
                    writeAndFlush(commandsManager.createOptionsResponse(cSeq))
                }

                CommandsManager.Method.DESCRIBE -> tcpStreamSocket.withLock {
                    val v = videoParams.get()
                    if (v == null) {
                        writeAndFlush(commandsManager.createServiceUnavailableResponse(cSeq))
                    } else {
                        writeAndFlush(commandsManager.createDescribeResponse(cSeq, v, audioParams.get()))
                    }
                }

                CommandsManager.Method.SETUP -> {
                    val transport = commandsManager.getTransport(request)
                    val trackId = TRACK_ID_REGEX.find(request)?.groups?.get(1)?.value?.toIntOrNull() ?: -1
                    if (trackId !in 0..1) {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400, cSeq)) }
                        continue
                    }

                    if (transport.contains("TCP", ignoreCase = true)) {
                        if (requiredProtocol == Protocol.UDP) {
                            tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(461, cSeq)) }
                            continue
                        }
                        protocol = Protocol.TCP
                        val inter = Regex("interleaved=([0-9]+)-([0-9]+)").find(transport)?.destructured
                        val ch = inter?.let { (a, b) -> a.toInt() to b.toInt() } ?: ((trackId shl 1) to ((trackId shl 1) + 1))
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) videoCh = ch else audioCh = ch
                        tcpStreamSocket.withLock {
                            writeAndFlush(commandsManager.createSetupResponse(cSeq, transport, 0, 0, sessionId))
                        }
                        // Prepare packetizer and initial sequence
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            prepareVideoPacketizerIfNeeded()
                        } else {
                            prepareAudioPacketizerIfNeeded()
                        }
                    } else if (transport.contains("UDP", ignoreCase = true)) {
                        if (requiredProtocol == Protocol.TCP) {
                            tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(461, cSeq)) }
                            continue
                        }
                        protocol = Protocol.UDP
                        val clientPorts = commandsManager.parseClientPorts(transport)
                        if (clientPorts == null) {
                            tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400, cSeq)) }
                            continue
                        }
                        val (clientRtp, clientRtcp) = clientPorts
                        val remoteHost = tcpStreamSocket.remoteHost
                        val selectorManager = tcpStreamSocket.selectorManager
                        val rtp = UdpStreamSocket(selectorManager, remoteHost, clientRtp, 0)
                        val rtcp = UdpStreamSocket(selectorManager, remoteHost, clientRtcp, 0)
                        rtp.connect()
                        rtcp.connect()
                        val serverRtp = rtp.localPort() ?: 0
                        val serverRtcp = rtcp.localPort() ?: 0

                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            videoRtpSocket = rtp; videoRtcpSocket = rtcp
                        } else {
                            audioRtpSocket = rtp; audioRtcpSocket = rtcp
                        }
                        tcpStreamSocket.withLock {
                            writeAndFlush(commandsManager.createSetupResponse(cSeq, transport, serverRtp, serverRtcp, sessionId))
                        }
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            prepareVideoPacketizerIfNeeded()
                        } else {
                            prepareAudioPacketizerIfNeeded()
                        }
                    } else {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(461, cSeq)) }
                    }
                }

                CommandsManager.Method.PLAY -> tcpStreamSocket.withLock {
                    isStreaming = true
                    if (rtcpReporter == null) {
                        rtcpReporter = RtcpReporter(
                            scope = scope,
                            protocol = protocol,
                            writeToTcpSocket = { header, data ->
                                val ch = header[1].toInt() and 0xFF
                                val mapped = when (ch) {
                                    1 -> videoCh.second
                                    3 -> audioCh.second
                                    else -> ch
                                }
                                header[1] = mapped.toByte()
                                writeAndFlush(header, data)
                            },
                            videoUdpSocket = videoRtcpSocket,
                            audioUdpSocket = audioRtcpSocket,
                            ssrcVideo = videoSsrc,
                            ssrcAudio = audioSsrc
                        )
                    }
                    val nowUs = MasterClock.relativeTimeUs()
                    val trackInfo = buildMap {
                        initialVideoSeq?.let {
                            val rtptime = (nowUs * BaseRtpPacket.VIDEO_CLOCK_FREQUENCY) / 1_000_000L
                            put(RtpFrame.VIDEO_TRACK_ID, CommandsManager.PlayTrackInfo(seq = it, rtptime = rtptime))
                        }
                        initialAudioSeq?.let {
                            val rtptime = audioParams.get()?.let { ap -> (nowUs * ap.sampleRate) / 1_000_000L }
                            put(RtpFrame.AUDIO_TRACK_ID, CommandsManager.PlayTrackInfo(seq = it, rtptime = rtptime))
                        }
                    }
                    if (trackInfo.isEmpty()) writeAndFlush(commandsManager.createPlayResponse(cSeq, sessionId))
                    else writeAndFlush(commandsManager.createPlayResponse(cSeq, sessionId, trackInfo))
                }

                CommandsManager.Method.PAUSE -> tcpStreamSocket.withLock {
                    isStreaming = false
                    rtcpReporter?.close()
                    rtcpReporter = null
                    writeAndFlush(commandsManager.createPauseResponse(cSeq, sessionId))
                }

                CommandsManager.Method.TEARDOWN -> {
                    tcpStreamSocket.withLock { writeAndFlush(commandsManager.createTeardownResponse(cSeq, sessionId)) }
                    break
                }

                CommandsManager.Method.GET_PARAMETER -> {
                    val sess = commandsManager.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(454, cSeq)) }
                    } else {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createGetParameterResponse(cSeq, sessionId)) }
                    }
                }

                else -> tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400, cSeq)) }
            }
        }
    }

    private suspend fun videoWriterLoop() {
        while (scope.isActive) {
            val blob = videoQueue.receiveCatching().getOrNull() ?: break
            if (!isStreaming) continue
            videoParams.get() ?: continue
            if (videoPacketizer == null) prepareVideoPacketizerIfNeeded()
            rtcpReporter?.setSsrcVideo(videoSsrc)

            val buffer = ByteBuffer.wrap(blob.buf.bytes, 0, blob.length)
            val info = MediaFrame.Info(offset = 0, size = blob.length, timestamp = blob.timestampUs, isKeyFrame = blob.isKeyFrame)
            val frame = MediaFrame.VideoFrame(buffer, info) {}

            val packets = videoPacketizer!!.createPacket(frame)
            if (protocol == Protocol.TCP) {
                val (chRtp, _) = videoCh
                for (p in packets) tcpStreamSocket.withLock {
                    writeAndFlush(p.getTcpHeaderFor(chRtp), p.buffer, 0, p.length)
                }
            } else {
                val sock = videoRtpSocket ?: continue
                for (p in packets) sock.write(p.buffer, 0, p.length)
            }
            for (p in packets) rtcpReporter?.update(p)

            blob.buf.release()

            // Optional: detect persistently slow client and drop connection
//            val now = System.currentTimeMillis()
//            if (videoDrops > 100 && (now - lastDropAtMs) < 5000L && videoQueue.isEmpty.not()) {
//                XLog.w(getLog("videoWriterLoop", "Persistently slow client; dropping connection"))
//                stop(); break
//            }
        }
    }

    private suspend fun audioWriterLoop() {
        while (scope.isActive) {
            val blob = audioQueue.receiveCatching().getOrNull() ?: break
            if (!isStreaming) continue
            audioParams.get() ?: continue
            if (audioPacketizer == null) prepareAudioPacketizerIfNeeded()
            rtcpReporter?.setSsrcAudio(audioSsrc)

            val buffer = ByteBuffer.wrap(blob.buf.bytes, 0, blob.length)
            val info = MediaFrame.Info(offset = 0, size = blob.length, timestamp = blob.timestampUs, isKeyFrame = false)
            val frame = MediaFrame.AudioFrame(buffer, info) {}

            val packets = audioPacketizer!!.createPacket(frame)
            if (protocol == Protocol.TCP) {
                val (chRtp, _) = audioCh
                for (p in packets) tcpStreamSocket.withLock {
                    writeAndFlush(p.getTcpHeaderFor(chRtp), p.buffer, 0, p.length)
                }
            } else {
                val sock = audioRtpSocket ?: continue
                for (p in packets) sock.write(p.buffer, 0, p.length)
            }
            for (p in packets) rtcpReporter?.update(p)
            blob.buf.release()

//            val now = System.currentTimeMillis()
//            if (audioDrops > 500 && (now - lastDropAtMs) < 5000L && audioQueue.isEmpty.not()) {
//                XLog.w(getLog("audioWriterLoop", "Persistently slow client; dropping connection"))
//                stop(); break
//            }
        }
    }

    internal suspend fun stop() {
        XLog.d(getLog("stop"))
        clientJob?.cancel()
        clientJob = null
        videoWriterJob?.cancel(); videoWriterJob = null
        audioWriterJob?.cancel(); audioWriterJob = null
        runCatching { rtcpReporter?.close() }
        rtcpReporter = null
        runCatching { videoRtpSocket?.close() }
        videoRtpSocket = null
        runCatching { videoRtcpSocket?.close() }
        videoRtcpSocket = null
        runCatching { audioRtpSocket?.close() }
        audioRtpSocket = null
        runCatching { audioRtcpSocket?.close() }
        audioRtcpSocket = null

        while (true) {
            val v = videoQueue.tryReceive().getOrNull() ?: break
            v.buf.release()
        }
        while (true) {
            val a = audioQueue.tryReceive().getOrNull() ?: break
            a.buf.release()
        }

        scope.cancel()
        onEvent(RtspStreamingService.InternalEvent.RtspServerOnClientDisconnected(this))
    }

    private fun RtpFrame.getTcpHeaderFor(channel: Int): ByteArray = byteArrayOf(
        '$'.code.toByte(), channel.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()
    )

    override fun toString(): String = "RtspServerConnection(address='${tcpStreamSocket.remoteHost}')"

    private suspend fun prepareVideoPacketizerIfNeeded() {
        if (videoPacketizer != null) return
        val v = videoParams.get() ?: return
        videoPacketizer = when (v.codec) {
            Codec.Video.H264 -> H264Packet().apply { setVideoInfo(v.sps, v.pps!!) }
            Codec.Video.H265 -> H265Packet().apply { setVideoInfo(v.sps, v.pps!!, v.vps!!) }
            Codec.Video.AV1 -> Av1Packet()
        }.apply {
            videoSsrc = SecureRandom().nextLong()
            setSSRC(videoSsrc)
            val seq = SecureRandom().nextInt(0x10000)
            setInitialSeq(seq)
            initialVideoSeq = seq
        }
        rtcpReporter?.setSsrcVideo(videoSsrc)
    }

    private suspend fun prepareAudioPacketizerIfNeeded() {
        if (audioPacketizer != null) return
        val a = audioParams.get() ?: return
        audioPacketizer = when (a.codec) {
            Codec.Audio.AAC -> AacPacket().apply { setAudioInfo(a.sampleRate) }
            Codec.Audio.OPUS -> OpusPacket().apply { setAudioInfo(a.sampleRate) }
            Codec.Audio.G711 -> G711Packet().apply { setAudioInfo(a.sampleRate) }
        }.apply {
            audioSsrc = SecureRandom().nextLong()
            setSSRC(audioSsrc)
            val seq = SecureRandom().nextInt(0x10000)
            setInitialSeq(seq)
            initialAudioSeq = seq
        }
        rtcpReporter?.setSsrcAudio(audioSsrc)
    }
}
