package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
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
import kotlinx.coroutines.CancellationException
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val TRACK_ID_REGEX = Regex("trackID=(\\d+)")

    private sealed interface State {
        data object Init : State
        data object Ready : State
        data object Playing : State
        data object Closed : State
    }

    private var state: State = State.Init

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

    private val paramInjector = ParamInjector()
    private var waitingForKeyframe: Boolean = false
    private var rtpTransport: RtpTransport? = null

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
            } catch (_: CancellationException) {
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
            when (method) {
                RtspMessagesBase.Method.OPTIONS -> tcpStreamSocket.withLock {
                    writeAndFlush(commandsManager.createOptionsResponse(cSeq))
                }

                RtspMessagesBase.Method.DESCRIBE -> tcpStreamSocket.withLock {
                    val v = videoParams.get()
                    if (v == null) {
                        writeAndFlush(commandsManager.createServiceUnavailableResponse(cSeq))
                    } else {
                        val resp = commandsManager.createDescribeResponse(cSeq, v, audioParams.get())
                        writeAndFlush(resp)
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
                        rtpTransport = InterleavedTcpTransport(tcpStreamSocket) { tid ->
                            if (tid == RtpFrame.VIDEO_TRACK_ID) videoCh.first else audioCh.first
                        }
                        tcpStreamSocket.withLock {
                            val resp = commandsManager.createSetupResponse(cSeq, transport, 0, 0, sessionId)
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
                        rtpTransport = UdpTransport(
                            getVideoRtp = { videoRtpSocket },
                            getAudioRtp = { audioRtpSocket }
                        )
                        tcpStreamSocket.withLock {
                            val resp = commandsManager.createSetupResponse(cSeq, transport, serverRtp, serverRtcp, sessionId)
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

                RtspMessagesBase.Method.PLAY -> tcpStreamSocket.withLock {
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
                    val trackInfo = mutableMapOf<Int, RtspServerMessages.PlayTrackInfo>()
                    val nowUs = MasterClock.relativeTimeUs()
                    if (videoPacketizer != null) {
                        val seqV = ((initialVideoSeq ?: 0) + 1) and 0xFFFF
                        val tsV = RtpTimestampCalculator.videoRtpTime(nowUs)
                        trackInfo[RtpFrame.VIDEO_TRACK_ID] = RtspServerMessages.PlayTrackInfo(seq = seqV, rtptime = tsV)
                    }
                    if (audioPacketizer != null) {
                        val seqA = ((initialAudioSeq ?: 0) + 1) and 0xFFFF
                        val sr = audioParams.get()?.sampleRate ?: 48000
                        val tsA = RtpTimestampCalculator.audioRtpTime(nowUs, sr)
                        trackInfo[RtpFrame.AUDIO_TRACK_ID] = RtspServerMessages.PlayTrackInfo(seq = seqA, rtptime = tsA)
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

                else -> tcpStreamSocket.withLock { writeAndFlush(commandsManager.createErrorResponse(400, cSeq)) }
            }
        }
    }

    private suspend fun videoWriterLoop() {
        while (scope.isActive) {
            // Do not consume queue until PLAY; preserve first keyframe for the client
            if (!isStreaming) {
                delay(10)
                continue
            }
            val blob = videoQueue.receiveCatching().getOrNull() ?: break
            videoParams.get() ?: continue
            if (videoPacketizer == null) prepareVideoPacketizerIfNeeded()

            // Drop non-keyframes until the first IDR to help HEVC clients sync cleanly
            if (waitingForKeyframe && !blob.isKeyFrame) {
                blob.buf.releaseOne()
                continue
            }
            if (blob.isKeyFrame) waitingForKeyframe = false

            val buffer = ByteBuffer.wrap(blob.buf.bytes, 0, blob.length)
            val info = MediaFrame.Info(offset = 0, size = blob.length, timestamp = blob.timestampUs, isKeyFrame = blob.isKeyFrame)
            val frame = MediaFrame.VideoFrame(buffer, info) {}

            // Periodically prepend VPS/SPS/PPS on non‑IDR to help resync strict players.
            when (val vp = videoPacketizer) {
                is H264Packet -> paramInjector.maybeInjectForH264(vp, blob.isKeyFrame)
                is H265Packet -> paramInjector.maybeInjectForH265(vp, blob.isKeyFrame)
            }

            val packets = videoPacketizer!!.createPacket(frame)
            try {
                sendPackets(RtpFrame.VIDEO_TRACK_ID, packets)
                val bytes = packets.sumOf { it.length }
                statsReporter.onVideoSent(packets.size, bytes)
            } catch (t: Throwable) {
                break
            }

            blob.buf.releaseOne()
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
                sendPackets(RtpFrame.AUDIO_TRACK_ID, packets)
                val bytes = packets.sumOf { it.length }
                statsReporter.onAudioSent(packets.size, bytes)
            } catch (t: Throwable) {
                break
            }
            blob.buf.releaseOne()
        }
    }

    private suspend fun sendPackets(trackId: Int, packets: List<RtpFrame>) {
        rtpTransport?.sendRtpPackets(trackId, packets)
        for (p in packets) rtcpReporter?.update(p)
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
            val v = videoQueue.tryReceive().getOrNull() ?: break
            v.buf.releaseOne()
        }
        while (true) {
            val a = audioQueue.tryReceive().getOrNull() ?: break
            a.buf.releaseOne()
        }

        scope.cancel()
        state = State.Closed
        onEvent(RtspStreamingService.InternalEvent.RtspServerOnClientDisconnected(this))
    }

    override fun toString(): String = "RtspServerConnection(address='${tcpStreamSocket.remoteHost}')"

    private suspend fun prepareVideoPacketizerIfNeeded() {
        if (videoPacketizer != null) return
        val v = videoParams.get() ?: return

        videoPacketizer = when (v.codec) {
            Codec.Video.H264 -> H264Packet().apply { setVideoInfo(v.sps, v.pps!!) }
            Codec.Video.H265 -> H265Packet().apply { setVideoInfo(v.sps, v.pps!!, v.vps!!) }
            Codec.Video.AV1 -> Av1Packet().apply { setSequenceHeader(v.sps) }
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
