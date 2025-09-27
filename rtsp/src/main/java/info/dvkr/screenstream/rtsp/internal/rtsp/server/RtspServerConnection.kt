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
import info.dvkr.screenstream.rtsp.internal.rtsp.PacketizationConfig
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.RtpTimestampCalculator
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
import info.dvkr.screenstream.rtsp.internal.rtsp.transport.InterleavedTcpTransport
import info.dvkr.screenstream.rtsp.internal.rtsp.transport.RtpTransport
import info.dvkr.screenstream.rtsp.internal.rtsp.transport.UdpTransport
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
    private var sdpLogged: Boolean = false
    private var videoPacketsSent: Long = 0
    private var audioPacketsSent: Long = 0
    private var videoWriterDebugLogged: Int = 0
    private var lastSpsPpsInjectNs: Long = 0L
    private var waitingForKeyframe: Boolean = false
    private var rtpTransport: RtpTransport? = null

    // Per-client queues
    private val videoQueue = kotlinx.coroutines.channels.Channel<VideoBlob>(capacity = 32)
    private val audioQueue = kotlinx.coroutines.channels.Channel<AudioBlob>(capacity = 64)

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
                val ok = videoQueue.trySend(blob).isSuccess
                if (!ok) {
                    videoDrops++
                    lastDropAtMs = System.currentTimeMillis()
                    XLog.d(getLog("enqueueVideo", "DROP keyframe; queue still full"))
                } else {
                    XLog.d(getLog("enqueueVideo", "ENQ keyframe after drain"))
                }
                return ok
            } else {
                // Drop non-key
                videoDrops++
                lastDropAtMs = System.currentTimeMillis()
                XLog.d(getLog("enqueueVideo", "DROP non-key; queue full"))
                return false
            }
        } else {
            if (videoPacketsSent < 4) XLog.d(getLog("enqueueVideo", "ENQ size=${blob.length}, key=${blob.isKeyFrame}"))
            return true
        }
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
        while (scope.isActive && tcpStreamSocket.isConnected()) {
            // Do not hold the write lock while waiting for incoming requests;
            // this would block RTP interleaved writes.
            val request = tcpStreamSocket.readRequestHeaders() ?: break
            if (RtspLogging.verboseRtsp) runCatching {
                val firstLine = request.lineSequence().firstOrNull().orEmpty()
                val transport = Regex("Transport\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
                    .find(request)?.groupValues?.getOrNull(1).orEmpty()
                XLog.i(getLog("RTSP", "Request: $firstLine; Transport: ${transport.ifBlank { "-" }}"))
            }
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
                        val resp = commandsManager.createDescribeResponse(cSeq, v, audioParams.get())
                        if (!sdpLogged) {
                            sdpLogged = true
                            val sdp = resp.substringAfter("\r\n\r\n")
                            XLog.i(getLog("RTSP", "DESCRIBE OK, SDP bytes=${sdp.toByteArray(Charsets.US_ASCII).size}"))
                            if (RtspLogging.verboseRtsp) XLog.i(getLog("RTSP", "SDP:\n$sdp"))
                        }
                        writeAndFlush(resp)
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
                        // Accept TCP even if preferred protocol is UDP to maximize compatibility
                        protocol = Protocol.TCP
                        val inter = Regex("interleaved=([0-9]+)-([0-9]+)").find(transport)?.destructured
                        val ch = inter?.let { (a, b) -> a.toInt() to b.toInt() } ?: ((trackId shl 1) to ((trackId shl 1) + 1))
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) videoCh = ch else audioCh = ch
                        rtpTransport = InterleavedTcpTransport(tcpStreamSocket) { tid ->
                            if (tid == RtpFrame.VIDEO_TRACK_ID) videoCh.first else audioCh.first
                        }
                        tcpStreamSocket.withLock {
                            val resp = commandsManager.createSetupResponse(cSeq, transport, 0, 0, sessionId)
                            val respTransport = Regex("Transport\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
                                .find(resp)?.groupValues?.getOrNull(1).orEmpty()
                            if (RtspLogging.verboseRtsp) XLog.i(getLog("RTSP", "SETUP/TCP OK, Transport: ${respTransport.ifBlank { "-" }}"))
                            writeAndFlush(resp)
                        }
                        // Prepare packetizer and initial sequence
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            prepareVideoPacketizerIfNeeded()
                        } else {
                            prepareAudioPacketizerIfNeeded()
                        }
                    } else if (transport.contains("UDP", ignoreCase = true)) {
                        // Accept UDP even if preferred protocol is TCP
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
                        rtpTransport = UdpTransport(
                            getVideoRtp = { videoRtpSocket },
                            getAudioRtp = { audioRtpSocket }
                        )
                        tcpStreamSocket.withLock {
                            val resp = commandsManager.createSetupResponse(cSeq, transport, serverRtp, serverRtcp, sessionId)
                            val respTransport = Regex("Transport\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
                                .find(resp)?.groupValues?.getOrNull(1).orEmpty()
                            if (RtspLogging.verboseRtsp) XLog.i(getLog("RTSP", "SETUP/UDP OK, Transport: ${respTransport.ifBlank { "-" }}"))
                            writeAndFlush(resp)
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
                    // Respond to PLAY first, then start streaming to avoid races
                    // where packetizers advance seq/timestamps before RTP-Info is sent.
                    // For HEVC, require an IDR before we forward frames to a fresh client.
                    runCatching {
                        val v = videoParams.get()
                        waitingForKeyframe = when (v?.codec) {
                            Codec.Video.H265 -> PacketizationConfig.requireFirstIdrForHevc
                            Codec.Video.H264 -> PacketizationConfig.requireFirstIdrForAvc
                            else -> false
                        }
                    }
                    if (rtcpReporter == null) {
                        val socket = this@RtspServerConnection.tcpStreamSocket
                        rtcpReporter = RtcpReporter(
                            scope = scope,
                            protocol = protocol,
                            writeToTcpSocket = { header, data ->
                                // Always serialize writes via socket lock to avoid ByteChannel corruption
                                socket.withLock {
                                    val ch = header[1].toInt() and 0xFF
                                    val mapped = when (ch) {
                                        1 -> videoCh.second
                                        3 -> audioCh.second
                                        else -> ch
                                    }
                                    header[1] = mapped.toByte()
                                    if (isConnected()) writeAndFlush(header, data)
                                }
                            },
                            videoUdpSocket = videoRtcpSocket,
                            audioUdpSocket = audioRtcpSocket,
                            ssrcVideo = videoSsrc,
                            ssrcAudio = audioSsrc
                        )
                    }
                    // Provide RTP-Info with sequence numbers of the first packet that will be sent.
                    // Our RTP packetizers increment the sequence before writing the first packet, so
                    // advertise initialSeq + 1 to match the first outgoing RTP packet.
                    val trackInfo = mutableMapOf<Int, CommandsManager.PlayTrackInfo>()
                    val nowUs = MasterClock.relativeTimeUs()
                    if (videoPacketizer != null) {
                        val seqV = initialVideoSeq?.let { (it + 1) and 0xFFFF }
                        val tsV = RtpTimestampCalculator.videoRtpTime(nowUs)
                        if (seqV != null) trackInfo[RtpFrame.VIDEO_TRACK_ID] = CommandsManager.PlayTrackInfo(seq = seqV, rtptime = tsV)
                    }
                    if (audioPacketizer != null) {
                        val seqA = initialAudioSeq?.let { (it + 1) and 0xFFFF }
                        val sr = audioParams.get()?.sampleRate ?: 48000
                        val tsA = RtpTimestampCalculator.audioRtpTime(nowUs, sr)
                        if (seqA != null) trackInfo[RtpFrame.AUDIO_TRACK_ID] = CommandsManager.PlayTrackInfo(seq = seqA, rtptime = tsA)
                    }
                    if (trackInfo.isEmpty()) writeAndFlush(commandsManager.createPlayResponse(cSeq, sessionId))
                    else writeAndFlush(commandsManager.createPlayResponse(cSeq, sessionId, trackInfo))

                    // Enable streaming after sending the PLAY response
                    isStreaming = true
                    XLog.i(getLog("PLAY", "isStreaming=true"))
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
            // Do not consume queue until PLAY; preserve first keyframe for the client
            if (!isStreaming) {
                kotlinx.coroutines.delay(10)
                continue
            }
            val blob = videoQueue.receiveCatching().getOrNull() ?: break
            if (RtspLogging.verboseRtp && videoWriterDebugLogged < RtspLogging.rtpFirstN) {
                videoWriterDebugLogged++
                XLog.d(getLog("videoWriterLoop", "recv size=${blob.length}, key=${blob.isKeyFrame}"))
            }
            videoParams.get() ?: continue
            if (videoPacketizer == null) prepareVideoPacketizerIfNeeded()

            // Drop non-keyframes until the first IDR to help HEVC clients sync cleanly
            if (waitingForKeyframe && !blob.isKeyFrame) {
                blob.buf.release()
                continue
            }
            if (blob.isKeyFrame) waitingForKeyframe = false

            val buffer = ByteBuffer.wrap(blob.buf.bytes, 0, blob.length)
            val info = MediaFrame.Info(offset = 0, size = blob.length, timestamp = blob.timestampUs, isKeyFrame = blob.isKeyFrame)
            val frame = MediaFrame.VideoFrame(buffer, info) {}

            // Periodically prepend SPS/PPS (STAP‑A) even on non‑IDR to help resync strict players.
            if (PacketizationConfig.paramReinjectionEnabled && !blob.isKeyFrame) {
                val now = System.nanoTime()
                val intervalNs = PacketizationConfig.reinjectParamsIntervalSec.toLong() * 1_000_000_000L
                if (now - lastSpsPpsInjectNs > intervalNs) { // configurable
                    when (val vp = videoPacketizer) {
                        is H264Packet -> vp.forceStapAOnce()
                        is H265Packet -> vp.forceParamsOnce()
                    }
                    lastSpsPpsInjectNs = now
                }
            }

            val packets = videoPacketizer!!.createPacket(frame)
            try {
                rtpTransport?.sendRtpPackets(RtpFrame.VIDEO_TRACK_ID, packets)
                for (p in packets) rtcpReporter?.update(p)
                videoPacketsSent += packets.size
                if (RtspLogging.verboseRtp && (videoPacketsSent <= RtspLogging.rtpFirstN.toLong() || blob.isKeyFrame)) {
                    val first = packets.firstOrNull()
                    val last = packets.lastOrNull()
                    fun seqOf(pkt: RtpFrame?): Int =
                        pkt?.let { ((it.buffer[2].toInt() and 0xFF) shl 8) or (it.buffer[3].toInt() and 0xFF) } ?: -1

                    fun tsOf(pkt: RtpFrame?): Long = pkt?.let {
                        ((it.buffer[4].toLong() and 0xFF) shl 24) or
                                ((it.buffer[5].toLong() and 0xFF) shl 16) or
                                ((it.buffer[6].toLong() and 0xFF) shl 8) or
                                ((it.buffer[7].toLong() and 0xFF))
                    } ?: -1

                    val seqFirst = seqOf(first)
                    val seqLast = seqOf(last)
                    val ts = tsOf(first)
                    val marker = last?.let { (it.buffer[1].toInt() and 0x80) != 0 } ?: false
                    XLog.d(getLog("videoRtp", "ts=$ts, seq=$seqFirst..$seqLast, m=$marker, count=${packets.size}, key=${blob.isKeyFrame}"))
                }
            } catch (t: Throwable) {
                XLog.w(getLog("videoWriterLoop", "write failed: ${t.message}"), t)
                break
            }

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
            if (!isStreaming) {
                kotlinx.coroutines.delay(10)
                continue
            }
            val blob = audioQueue.receiveCatching().getOrNull() ?: break
            audioParams.get() ?: continue
            if (audioPacketizer == null) prepareAudioPacketizerIfNeeded()

            val buffer = ByteBuffer.wrap(blob.buf.bytes, 0, blob.length)
            val info = MediaFrame.Info(offset = 0, size = blob.length, timestamp = blob.timestampUs, isKeyFrame = false)
            val frame = MediaFrame.AudioFrame(buffer, info) {}

            val packets = audioPacketizer!!.createPacket(frame)
            try {
                rtpTransport?.sendRtpPackets(RtpFrame.AUDIO_TRACK_ID, packets)
                for (p in packets) rtcpReporter?.update(p)
                audioPacketsSent += packets.size
                if (RtspLogging.verboseRtp && audioPacketsSent <= RtspLogging.rtpFirstN.toLong()) {
                    val first = packets.firstOrNull()
                    val last = packets.lastOrNull()
                    fun seqOf(pkt: RtpFrame?): Int =
                        pkt?.let { ((it.buffer[2].toInt() and 0xFF) shl 8) or (it.buffer[3].toInt() and 0xFF) } ?: -1

                    fun tsOf(pkt: RtpFrame?): Long = pkt?.let {
                        ((it.buffer[4].toLong() and 0xFF) shl 24) or
                                ((it.buffer[5].toLong() and 0xFF) shl 16) or
                                ((it.buffer[6].toLong() and 0xFF) shl 8) or
                                ((it.buffer[7].toLong() and 0xFF))
                    } ?: -1

                    val seqFirst = seqOf(first)
                    val seqLast = seqOf(last)
                    val ts = tsOf(first)
                    val marker = last?.let { (it.buffer[1].toInt() and 0x80) != 0 } ?: false
                    XLog.d(getLog("audioRtp", "ts=$ts, seq=$seqFirst..$seqLast, m=$marker, count=${packets.size}"))
                }
            } catch (t: Throwable) {
                XLog.w(getLog("audioWriterLoop", "write failed: ${t.message}"), t)
                break
            }
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

    override fun toString(): String = "RtspServerConnection(address='${tcpStreamSocket.remoteHost}')"

    private suspend fun prepareVideoPacketizerIfNeeded() {
        if (videoPacketizer != null) return
        val v = videoParams.get() ?: return
        XLog.i(getLog("prepareVideoPacketizerIfNeeded", "codec=${v.codec}, sps=${v.sps.size}, pps=${v.pps?.size ?: 0}"))
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
        XLog.i(getLog("prepareAudioPacketizerIfNeeded", "codec=${a.codec}, sr=${a.sampleRate}"))
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
