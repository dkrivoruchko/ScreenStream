package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspMessagesBase
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspServerMessages
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

internal class RtspServerConnection(
    private val tcpStreamSocket: TcpStreamSocket,
    private val commandsManager: RtspServerMessages,
    private val videoParams: AtomicReference<RtspClient.VideoParams?>,
    private val audioParams: AtomicReference<RtspClient.AudioParams?>,
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit,
    private val requiredProtocol: Protocol
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val TRACK_ID_REGEX = Regex("trackID=(\\d+)")

    private sealed interface State {
        data object Init : State
        data object Ready : State
        data object Playing : State
        data object Closed : State
    }

    private var state: State = State.Init

    private var protocol: Protocol = Protocol.TCP
    private var sessionId: String = secureRandom.nextInt().toString(16)
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

    internal class ParamInjector() {
        private var lastInjectNs: Long = 0L

        fun maybeInjectForH264(packet: H264Packet, isKeyFrame: Boolean) {
            if (isKeyFrame) return
            val now = System.nanoTime()
            if (now - lastInjectNs > 2_000_000_000L) {
                packet.forceStapAOnce()
                lastInjectNs = now
            }
        }

        fun maybeInjectForH265(packet: H265Packet, isKeyFrame: Boolean) {
            if (isKeyFrame) return
            val now = System.nanoTime()
            if (now - lastInjectNs > 2_000_000_000L) {
                packet.forceParamsOnce()
                lastInjectNs = now
            }
        }
    }

    private val paramInjector = ParamInjector()
    private var waitingForKeyframe: Boolean = false
    private var sendRtpPackets: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit = { _, _ -> }

    private val videoQueue = Channel<VideoBlob>(capacity = 32) { it.buf.releaseOne() }
    private val audioQueue = Channel<AudioBlob>(capacity = 64) { it.buf.releaseOne() }

    private val statsReporter = ClientStatsReporter(
        sessionId = sessionId,
        remoteHost = tcpStreamSocket.remoteHost,
        protocol = protocol,
        queueCapVideo = 32,
        queueCapAudio = 64
    )
    internal val stats: StateFlow<ClientStats> = statsReporter.stats

    internal fun enqueueVideo(blob: VideoBlob): Boolean {
        if (!videoQueue.trySend(blob).isSuccess) {
            if (blob.isKeyFrame) {
                while (true) {
                    val drained = videoQueue.tryReceive().getOrNull() ?: break
                    drained.buf.releaseOne()
                }
                val ok = videoQueue.trySend(blob).isSuccess
                if (!ok) statsReporter.onVideoDrop() else statsReporter.onVideoEnqueue()
                return ok
            } else {
                statsReporter.onVideoDrop()
                return false
            }
        } else {
            statsReporter.onVideoEnqueue()
            return true
        }
    }

    internal fun enqueueAudio(blob: AudioBlob): Boolean {
        if (!audioQueue.trySend(blob).isSuccess) {
            val evicted = audioQueue.tryReceive().getOrNull()
            evicted?.buf?.releaseOne()
            if (!audioQueue.trySend(blob).isSuccess) {
                statsReporter.onAudioDrop()
                return false
            }
        }
        statsReporter.onAudioEnqueue()
        return true
    }

    internal fun start() {
        clientJob = scope.launch {
            try {
                onEvent(RtspStreamingService.InternalEvent.RtspServerOnClientConnected(this@RtspServerConnection))
                commandLoop()
            } catch (_: Throwable) {
            } finally {
                stop()
            }
        }
        videoWriterJob = scope.launch { videoWriterLoop() }
        audioWriterJob = scope.launch { audioWriterLoop() }
    }

    private suspend fun commandLoop() {
        while (scope.isActive && tcpStreamSocket.isConnected()) {
            val request = tcpStreamSocket.readRequestHeaders() ?: break
            val (method, cSeq) = commandsManager.parseRequest(request)

            if (cSeq < 0) {
                tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400)) }
                continue
            }

            when (method) {
                RtspMessagesBase.Method.OPTIONS -> tcpStreamSocket.withLock { writeAndFlush(commandsManager.createOptionsResponse(cSeq)) }

                RtspMessagesBase.Method.DESCRIBE -> tcpStreamSocket.withLock {
                    val videoParams = this@RtspServerConnection.videoParams.get()
                    if (videoParams == null) {
                        writeAndFlush(commandsManager.createErrorResponse(415, cSeq))
                    } else {
                        writeAndFlush(commandsManager.createDescribeResponse(cSeq, videoParams, audioParams.get()))
                    }
                }

                RtspMessagesBase.Method.SETUP -> {
                    val transport = commandsManager.getTransport(request)
                    val trackId = TRACK_ID_REGEX.find(request)?.groups?.get(1)?.value?.toIntOrNull() ?: -1
                    if (trackId !in 0..1) {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400, cSeq)) }
                        continue
                    }

                    if (transport.contains("TCP", ignoreCase = true)) {
                        // Accept TCP even if preferred protocol is UDP to maximize compatibility
                        protocol = Protocol.TCP
                        statsReporter.setProtocol(protocol)
                        val inter = Regex("interleaved=([0-9]+)-([0-9]+)").find(transport)?.destructured
                        val ch = inter?.let { (a, b) -> a.toInt() to b.toInt() } ?: ((trackId shl 1) to ((trackId shl 1) + 1))
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) videoCh = ch else audioCh = ch

                        sendRtpPackets = { tid, packets ->
                            val ch = if (tid == RtpFrame.VIDEO_TRACK_ID) videoCh.first else audioCh.first
                            var i = 0
                            val n = packets.size
                            for (packet in packets) {
                                tcpStreamSocket.withLock {
                                    write(interleavedHeader(ch, packet.length), packet.buffer, 0, packet.length)
                                    val isAudio = (tid == RtpFrame.AUDIO_TRACK_ID)
                                    val shouldFlush = isAudio || i == n - 1 || (i and 0x7) == 0
                                    if (shouldFlush) flush()
                                }
                                i++
                            }
                        }

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
                        // Accept UDP even if preferred protocol is TCP
                        protocol = Protocol.UDP
                        statsReporter.setProtocol(protocol)
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

                        sendRtpPackets = { tid, packets ->
                            val sock = if (tid == RtpFrame.VIDEO_TRACK_ID) videoRtpSocket else audioRtpSocket
                            if (sock != null) {
                                for (packet in packets) sock.write(packet.buffer, 0, packet.length)
                            }
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

                RtspMessagesBase.Method.PLAY -> tcpStreamSocket.withLock {
                    runCatching {
                        val videoParams = this@RtspServerConnection.videoParams.get()
                        waitingForKeyframe = when (videoParams?.codec) {
                            Codec.Video.H265 -> true
                            Codec.Video.H264 -> true
                            else -> false
                        }
                    }
                    if (rtcpReporter == null) {
                        val socket = this@RtspServerConnection.tcpStreamSocket
                        rtcpReporter = RtcpReporter(
                            scope = scope,
                            protocol = protocol,
                            writeToTcpSocket = { header, data ->
                                socket.withLock {
                                    val channelId = header[1].toInt() and 0xFF
                                    val mapped = when (channelId) {
                                        1 -> videoCh.second
                                        3 -> audioCh.second
                                        else -> channelId
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
                    val trackInfo = mutableMapOf<Int, RtspServerMessages.PlayTrackInfo>()
                    val nowUs = MasterClock.relativeTimeUs()
                    if (videoPacketizer != null) {
                        val seqV = ((initialVideoSeq ?: 0) + 1) and 0xFFFF
                        val tsV = (nowUs * BaseRtpPacket.VIDEO_CLOCK_FREQUENCY) / 1_000_000L
                        trackInfo[RtpFrame.VIDEO_TRACK_ID] = RtspServerMessages.PlayTrackInfo(seq = seqV, rtpTime = tsV)
                    }
                    if (audioPacketizer != null) {
                        val seqA = ((initialAudioSeq ?: 0) + 1) and 0xFFFF
                        val sampleRate = audioParams.get()?.sampleRate ?: 48000
                        val tsA = (nowUs * sampleRate) / 1_000_000L
                        trackInfo[RtpFrame.AUDIO_TRACK_ID] = RtspServerMessages.PlayTrackInfo(seq = seqA, rtpTime = tsA)
                    }
                    writeAndFlush(commandsManager.createPlayResponse(cSeq, sessionId, trackInfo))

                    // Enable streaming after sending the PLAY response
                    isStreaming = true
                    state = State.Playing
                }

                RtspMessagesBase.Method.PAUSE -> tcpStreamSocket.withLock {
                    isStreaming = false
                    state = State.Ready
                    rtcpReporter?.close()
                    rtcpReporter = null
                    writeAndFlush(commandsManager.createPauseResponse(cSeq, sessionId))
                }

                RtspMessagesBase.Method.TEARDOWN -> {
                    tcpStreamSocket.withLock { writeAndFlush(commandsManager.createTeardownResponse(cSeq, sessionId)) }
                    break
                }

                RtspMessagesBase.Method.GET_PARAMETER -> {
                    val sess = commandsManager.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(454, cSeq)) }
                    } else {
                        tcpStreamSocket.withLock { writeAndFlush(commandsManager.createGetParameterResponse(cSeq, sessionId)) }
                    }
                }

                else -> tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(405, cSeq)) }
            }
        }
    }

    private suspend fun videoWriterLoop() {
        while (scope.isActive) {
            if (!isStreaming) {
                delay(10)
                continue
            }
            val videoBlob = videoQueue.receiveCatching().getOrNull() ?: break

            if (videoParams.get() == null) {
                videoBlob.buf.releaseOne()
                continue
            }

            // Drop non-keyframes until the first IDR to help HEVC clients sync cleanly
            if (waitingForKeyframe && !videoBlob.isKeyFrame) {
                videoBlob.buf.releaseOne()
                continue
            }

            if (videoBlob.isKeyFrame) waitingForKeyframe = false

            val buffer = ByteBuffer.wrap(videoBlob.buf.bytes, 0, videoBlob.length)
            val info =
                MediaFrame.Info(offset = 0, size = videoBlob.length, timestamp = videoBlob.timestampUs, isKeyFrame = videoBlob.isKeyFrame)
            val frame = MediaFrame.VideoFrame(buffer, info) {}

            if (videoPacketizer == null) prepareVideoPacketizerIfNeeded()

            // Periodically prepend VPS/SPS/PPS on non‑IDR to help resync strict players.
            when (val vp = videoPacketizer) {
                is H264Packet -> paramInjector.maybeInjectForH264(vp, videoBlob.isKeyFrame)
                is H265Packet -> paramInjector.maybeInjectForH265(vp, videoBlob.isKeyFrame)
            }

            val packets = videoPacketizer!!.createPacket(frame)
            try {
                sendRtpPackets(RtpFrame.VIDEO_TRACK_ID, packets)
                for (packet in packets) rtcpReporter?.update(packet)
                val bytes = packets.sumOf { it.length }
                statsReporter.onVideoSent(packets.size, bytes)
            } catch (_: Throwable) {
                break
            }

            videoBlob.buf.releaseOne()
        }
    }

    private suspend fun audioWriterLoop() {
        while (scope.isActive) {
            if (!isStreaming) {
                delay(10)
                continue
            }
            val audioBlob = audioQueue.receiveCatching().getOrNull() ?: break

            if (audioParams.get() == null) {
                audioBlob.buf.releaseOne()
                continue
            }

            val buffer = ByteBuffer.wrap(audioBlob.buf.bytes, 0, audioBlob.length)
            val info = MediaFrame.Info(offset = 0, size = audioBlob.length, timestamp = audioBlob.timestampUs, isKeyFrame = false)
            val frame = MediaFrame.AudioFrame(buffer, info) {}

            if (audioPacketizer == null) prepareAudioPacketizerIfNeeded()
            val packets = audioPacketizer!!.createPacket(frame)
            try {
                sendRtpPackets(RtpFrame.AUDIO_TRACK_ID, packets)
                for (packet in packets) rtcpReporter?.update(packet)
                val bytes = packets.sumOf { it.length }
                statsReporter.onAudioSent(packets.size, bytes)
            } catch (_: Throwable) {
                break
            }

            audioBlob.buf.releaseOne()
        }
    }

    internal suspend fun stop() {
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
            val videoBlob = videoQueue.tryReceive().getOrNull() ?: break
            videoBlob.buf.releaseOne()
        }
        while (true) {
            val audioBlob = audioQueue.tryReceive().getOrNull() ?: break
            audioBlob.buf.releaseOne()
        }

        scope.cancel()
        state = State.Closed
        onEvent(RtspStreamingService.InternalEvent.RtspServerOnClientDisconnected(this))
    }

    private suspend fun prepareVideoPacketizerIfNeeded() {
        if (videoPacketizer != null) return
        val videoParams = this@RtspServerConnection.videoParams.get() ?: return

        videoPacketizer = when (videoParams.codec) {
            Codec.Video.H264 -> H264Packet().apply { setVideoInfo(videoParams.sps, videoParams.pps!!) }
            Codec.Video.H265 -> H265Packet().apply { setVideoInfo(videoParams.sps, videoParams.pps!!, videoParams.vps!!) }
            Codec.Video.AV1 -> Av1Packet().apply { setSequenceHeader(videoParams.sps) }
        }.apply {
            videoSsrc = secureRandom.nextLong()
            setSSRC(videoSsrc)
            val seq = secureRandom.nextInt(0x10000)
            setInitialSeq(seq)
            initialVideoSeq = seq
        }

        rtcpReporter?.setSsrcVideo(videoSsrc)
    }

    private suspend fun prepareAudioPacketizerIfNeeded() {
        if (audioPacketizer != null) return
        val audioParams = this@RtspServerConnection.audioParams.get() ?: return

        audioPacketizer = when (audioParams.codec) {
            Codec.Audio.AAC -> AacPacket().apply { setAudioInfo(audioParams.sampleRate) }
            Codec.Audio.OPUS -> OpusPacket().apply { setAudioInfo(audioParams.sampleRate) }
            Codec.Audio.G711 -> G711Packet().apply { setAudioInfo(audioParams.sampleRate) }
        }.apply {
            audioSsrc = secureRandom.nextLong()
            setSSRC(audioSsrc)
            val seq = secureRandom.nextInt(0x10000)
            setInitialSeq(seq)
            initialAudioSeq = seq
        }

        rtcpReporter?.setSsrcAudio(audioSsrc)
    }

    override fun toString(): String = "RtspServerConnection(address='${tcpStreamSocket.remoteHost}')"
}
