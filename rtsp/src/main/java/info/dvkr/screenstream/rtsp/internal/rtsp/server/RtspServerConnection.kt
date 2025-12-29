package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.AudioParams
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.VideoParams
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspBaseMessageHandler
import info.dvkr.screenstream.rtsp.internal.rtsp.core.TransportHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.BaseRtpPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import info.dvkr.screenstream.rtsp.settings.RtspSettings
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal class RtspServerConnection(
    parentJob: Job,
    private val tcpStreamSocket: TcpStreamSocket,
    private val serverMessageHandler: RtspServerMessageHandler,
    private val videoParams: AtomicReference<VideoParams?>,
    private val audioParams: AtomicReference<AudioParams?>,
    private val serverProtocolPolicy: RtspSettings.Values.ProtocolPolicy,
    private val onRequestKeyFrame: () -> Unit,
    private val onClosed: (RtspServerConnection) -> Unit,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val TRACK_ID_REGEX = Regex("trackID=(\\d+)")
    private val stateLock = Mutex()

    private sealed interface State {
        data object Init : State
        data object Ready : State
        data object Playing : State
        data object Closed : State
    }

    private data class VideoStateSnapshot(
        val streaming: Boolean,
        val setupDone: Boolean,
        val waitingForKeyframe: Boolean,
        val sender: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit
    )

    private data class AudioStateSnapshot(
        val streaming: Boolean,
        val setupDone: Boolean,
        val sender: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit
    )

    private var state: State = State.Init

    private var protocol: Protocol = Protocol.TCP
    private var sessionId: String = generateSessionId()

    @Volatile
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
    private val setupUrls: MutableMap<Int, String> = mutableMapOf()

    @Volatile
    private var videoSetupDone: Boolean = false

    @Volatile
    private var audioSetupDone: Boolean = false

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

        fun reset() {
            lastInjectNs = 0L
        }
    }

    private val paramInjector = ParamInjector()
    private var waitingForKeyframe: Boolean = false
    private var sendRtpPackets: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit = { _, _ -> }
    @Volatile private var videoParamsChanged: Boolean = false

    private val videoQueue = Channel<VideoBlob>(capacity = 32) { it.buf.releaseOne() }
    private val audioQueue = Channel<AudioBlob>(capacity = 64) { it.buf.releaseOne() }
    private val videoQueueSize = AtomicInteger(0)
    private val audioQueueSize = AtomicInteger(0)

    private val negotiatedInterleavedChannels: MutableSet<Int> = mutableSetOf()
    private fun allowedInterleavedChannels(): Set<Int> =
        if (protocol == Protocol.TCP) negotiatedInterleavedChannels.toSet() else emptySet()

    private suspend fun onInterleavedChunk(channel: Int, data: ByteArray, length: Int, isLast: Boolean) {
        val isRtcp = channel == videoCh.second || channel == audioCh.second
        if (!isRtcp) return
        // Currently no RTCP parsing; hook kept to allow future receiver-report handling.
        if (data.isEmpty() || length == 0 || !isLast) return
    }

    private val statsReporter = ClientStatsReporter(
        sessionId = sessionId,
        remoteHost = tcpStreamSocket.remoteHost,
        protocol = protocol,
        queueCapVideo = 32,
        queueCapAudio = 64
    )
    internal val stats: StateFlow<ClientStats> = statsReporter.stats

    private fun updateQueueStats() = statsReporter.setQueueSizes(videoQueueSize.get(), audioQueueSize.get())

    internal fun enqueueVideo(blob: VideoBlob): Boolean {
        if (!isStreaming || !videoSetupDone) return false
        if (!videoQueue.trySend(blob).isSuccess) {
            if (blob.isKeyFrame) {
                while (true) {
                    val drained = videoQueue.tryReceive().getOrNull() ?: break
                    drained.buf.releaseOne()
                    videoQueueSize.decrementAndGet()
                }
                val ok = videoQueue.trySend(blob).isSuccess
                if (!ok) {
                    statsReporter.onVideoDrop()
                } else {
                    videoQueueSize.incrementAndGet()
                    updateQueueStats()
                    statsReporter.onVideoEnqueue()
                }
                return ok
            } else {
                statsReporter.onVideoDrop()
                return false
            }
        } else {
            videoQueueSize.incrementAndGet()
            updateQueueStats()
            statsReporter.onVideoEnqueue()
            return true
        }
    }

    internal fun enqueueAudio(blob: AudioBlob): Boolean {
        if (!isStreaming || !audioSetupDone) return false
        if (!audioQueue.trySend(blob).isSuccess) {
            val evicted = audioQueue.tryReceive().getOrNull()
            if (evicted != null) {
                evicted.buf.releaseOne()
                audioQueueSize.decrementAndGet()
            }
            if (!audioQueue.trySend(blob).isSuccess) {
                statsReporter.onAudioDrop()
                return false
            }
        }
        audioQueueSize.incrementAndGet()
        updateQueueStats()
        statsReporter.onAudioEnqueue()
        return true
    }

    internal fun start() {
        clientJob = scope.launch {
            try {
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
            val request = tcpStreamSocket.readRtspMessage(
                allowedInterleavedChannels = allowedInterleavedChannels(),
                onInterleavedChunk = ::onInterleavedChunk
            )?.let { String(it.header, Charsets.ISO_8859_1) } ?: break
            val (method, cSeq) = serverMessageHandler.parseRequest(request)

            if (cSeq < 0) {
                tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(400)) }
                continue
            }

            when (method) {
                RtspBaseMessageHandler.Method.OPTIONS -> tcpStreamSocket.withWriteLock {
                    writeAndFlush(serverMessageHandler.createOptionsResponse(cSeq))
                }

                RtspBaseMessageHandler.Method.DESCRIBE -> tcpStreamSocket.withWriteLock {
                    val videoParams = this@RtspServerConnection.videoParams.get()
                    if (videoParams == null) {
                        writeAndFlush(serverMessageHandler.createErrorResponse(503, cSeq))
                    } else {
                        writeAndFlush(serverMessageHandler.createDescribeResponse(cSeq, videoParams, audioParams.get()))
                    }
                }

                RtspBaseMessageHandler.Method.SETUP -> {
                    if (state == State.Playing) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(455, cSeq)) }
                        continue
                    }
                    if (this@RtspServerConnection.videoParams.get() == null) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(503, cSeq)) }
                        continue
                    }
                    val transportHeader = serverMessageHandler.getTransport(request)
                    val trackId = TRACK_ID_REGEX.find(request)?.groups?.get(1)?.value?.toIntOrNull() ?: -1
                    serverMessageHandler.getRequestUri(request)?.let { uri ->
                        if (trackId in 0..1) setupUrls[trackId] = uri
                    }
                    if (trackId !in 0..1) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(400, cSeq)) }
                        continue
                    }
                    if (trackId == RtpFrame.AUDIO_TRACK_ID && this@RtspServerConnection.audioParams.get() == null) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(404, cSeq)) }
                        continue
                    }

                    val transportSpecs = TransportHeader.parseList(transportHeader)
                    val selected: TransportHeader.Companion.ParsedSpec? = when (serverProtocolPolicy) {
                        RtspSettings.Values.ProtocolPolicy.TCP -> transportSpecs.firstOrNull { it.isTcp }
                        RtspSettings.Values.ProtocolPolicy.UDP -> transportSpecs.firstOrNull {
                            it.isTcp.not() && serverMessageHandler.parseClientPorts(it.raw) != null
                        }

                        RtspSettings.Values.ProtocolPolicy.AUTO -> transportSpecs.firstOrNull { it.isTcp }
                            ?: transportSpecs.firstOrNull { it.isTcp.not() && serverMessageHandler.parseClientPorts(it.raw) != null }
                    }
                    if (selected == null) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                        continue
                    }
                    val spec = selected.raw
                    val selectedParsed = selected.header
                    val selectedTcp = selected.isTcp
                    if (!selectedTcp && serverMessageHandler.parseClientPorts(spec) == null) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                        continue
                    }
                    val hasSetup = stateLock.withLock { videoSetupDone || audioSetupDone }
                    if (hasSetup && (protocol == Protocol.TCP) != selectedTcp) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                        continue
                    }

                    if (selectedTcp) {
                        // TCP selected (AUTO prefers TCP when offered).
                        protocol = Protocol.TCP
                        statsReporter.setProtocol(protocol)
                        val channelPair = selectedParsed.interleaved
                            ?: Regex("interleaved=([0-9]+)-([0-9]+)").find(spec)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
                        if (channelPair == null) {
                            tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                            continue
                        }
                        if (channelPair.first == channelPair.second || channelPair.first !in 0..255 || channelPair.second !in 0..255) {
                            tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                            continue
                        }
                        val currentPair = if (trackId == RtpFrame.VIDEO_TRACK_ID && videoSetupDone) {
                            videoCh
                        } else if (trackId == RtpFrame.AUDIO_TRACK_ID && audioSetupDone) {
                            audioCh
                        } else {
                            null
                        }
                        val sameAsCurrent = currentPair?.let { it.first == channelPair.first && it.second == channelPair.second } == true
                        if (!sameAsCurrent &&
                            (negotiatedInterleavedChannels.contains(channelPair.first) ||
                                negotiatedInterleavedChannels.contains(channelPair.second))
                        ) {
                            tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(461, cSeq)) }
                            continue
                        }
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) videoCh = channelPair else audioCh = channelPair
                        negotiatedInterleavedChannels += channelPair.first
                        negotiatedInterleavedChannels += channelPair.second

                        val sender: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit = { tid, packets ->
                            val ch = if (tid == RtpFrame.VIDEO_TRACK_ID) videoCh.first else audioCh.first
                            var i = 0
                            val n = packets.size
                            for (packet in packets) {
                                tcpStreamSocket.withWriteLock {
                                    write(interleavedHeader(ch, packet.length), packet.buffer, 0, packet.length)
                                    val isAudio = (tid == RtpFrame.AUDIO_TRACK_ID)
                                    val shouldFlush = isAudio || i == n - 1 || (i and 0x7) == 0
                                    if (shouldFlush) flush()
                                }
                                i++
                            }
                        }
                        stateLock.withLock { sendRtpPackets = sender }

                        tcpStreamSocket.withWriteLock {
                            writeAndFlush(serverMessageHandler.createSetupResponse(cSeq, spec, 0, 0, sessionId, channelPair))
                        }
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            prepareVideoPacketizerIfNeeded()
                            stateLock.withLock { videoSetupDone = true }
                        } else {
                            prepareAudioPacketizerIfNeeded()
                            stateLock.withLock { audioSetupDone = true }
                        }
                        state = State.Ready
                    } else {
                        // UDP selected per server policy.
                        protocol = Protocol.UDP
                        statsReporter.setProtocol(protocol)
                        val clientPorts = serverMessageHandler.parseClientPorts(spec)
                        if (clientPorts == null) {
                            tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(400, cSeq)) }
                            continue
                        }
                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            runCatching { videoRtpSocket?.close() }
                            runCatching { videoRtcpSocket?.close() }
                            videoRtpSocket = null
                            videoRtcpSocket = null
                        } else {
                            runCatching { audioRtpSocket?.close() }
                            runCatching { audioRtcpSocket?.close() }
                            audioRtpSocket = null
                            audioRtcpSocket = null
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
                            scope.launch { drainRtcp(videoRtcpSocket) }
                        } else {
                            audioRtpSocket = rtp; audioRtcpSocket = rtcp
                            scope.launch { drainRtcp(audioRtcpSocket) }
                        }

                        val sender: suspend (trackId: Int, packets: List<RtpFrame>) -> Unit = { tid, packets ->
                            val sock = if (tid == RtpFrame.VIDEO_TRACK_ID) videoRtpSocket else audioRtpSocket
                            if (sock != null) {
                                for (packet in packets) sock.write(packet.buffer, 0, packet.length)
                            }
                        }
                        stateLock.withLock { sendRtpPackets = sender }

                        tcpStreamSocket.withWriteLock {
                            writeAndFlush(serverMessageHandler.createSetupResponse(cSeq, spec, serverRtp, serverRtcp, sessionId))
                        }

                        if (trackId == RtpFrame.VIDEO_TRACK_ID) {
                            prepareVideoPacketizerIfNeeded()
                            stateLock.withLock { videoSetupDone = true }
                        } else {
                            prepareAudioPacketizerIfNeeded()
                            stateLock.withLock { audioSetupDone = true }
                        }
                        state = State.Ready
                    }
                }

                RtspBaseMessageHandler.Method.PLAY -> {
                    val sess = serverMessageHandler.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(454, cSeq)) }
                        continue
                    }
                    if (this@RtspServerConnection.videoParams.get() == null) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(503, cSeq)) }
                        continue
                    }
                    if (state != State.Ready || !videoSetupDone) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(455, cSeq)) }
                        continue
                    }

                    if (videoSetupDone) prepareVideoPacketizerIfNeeded()
                    if (audioSetupDone) prepareAudioPacketizerIfNeeded()

                    tcpStreamSocket.withWriteLock {
                        runCatching {
                            val videoParams = this@RtspServerConnection.videoParams.get()
                            val shouldWait = when (videoParams?.codec) {
                                Codec.Video.H265 -> true
                                Codec.Video.H264 -> true
                                else -> false
                            }
                            stateLock.withLock { waitingForKeyframe = shouldWait }
                        }
                        if (rtcpReporter == null) {
                            val socket = this@RtspServerConnection.tcpStreamSocket
                            rtcpReporter = RtcpReporter(
                                scope = scope,
                                protocol = protocol,
                                writeToTcpSocket = { header, data ->
                                    socket.withWriteLock {
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
                        val trackInfo = mutableMapOf<Int, RtspServerMessageHandler.PlayTrackInfo>()
                        val nowUs = MasterClock.relativeTimeUs()
                        if (videoPacketizer != null && videoSetupDone) {
                            val seqV = videoPacketizer!!.peekNextSeq()
                            val tsV = videoPacketizer!!.rtpTimestampFromUs(nowUs)
                            trackInfo[RtpFrame.VIDEO_TRACK_ID] = RtspServerMessageHandler.PlayTrackInfo(seqV, tsV, videoSsrc)
                        }
                        if (audioPacketizer != null && audioSetupDone) {
                            val seqA = audioPacketizer!!.peekNextSeq()
                            val tsA = audioPacketizer!!.rtpTimestampFromUs(nowUs)
                            trackInfo[RtpFrame.AUDIO_TRACK_ID] = RtspServerMessageHandler.PlayTrackInfo(seqA, tsA, audioSsrc)
                        }
                        writeAndFlush(serverMessageHandler.createPlayResponse(cSeq, sessionId, trackInfo, setupUrls))
                        stateLock.withLock { isStreaming = true }
                        state = State.Playing
                    }
                    if (videoSetupDone) onRequestKeyFrame()
                }

                RtspBaseMessageHandler.Method.PAUSE -> {
                    val sess = serverMessageHandler.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(454, cSeq)) }
                        continue
                    }
                    if (state != State.Playing) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(455, cSeq)) }
                        continue
                    }
                    tcpStreamSocket.withWriteLock {
                        stateLock.withLock { isStreaming = false }
                        state = State.Ready
                        rtcpReporter?.close()
                        rtcpReporter = null
                        writeAndFlush(serverMessageHandler.createPauseResponse(cSeq, sessionId))
                    }
                }

                RtspBaseMessageHandler.Method.TEARDOWN -> {
                    val sess = serverMessageHandler.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(454, cSeq)) }
                        continue
                    }
                    tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createTeardownResponse(cSeq, sessionId)) }
                    break
                }

                RtspBaseMessageHandler.Method.GET_PARAMETER -> {
                    val sess = serverMessageHandler.getSessionFromRequest(request)
                    if (sess != null && sess != sessionId) {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(454, cSeq)) }
                    } else {
                        tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createGetParameterResponse(cSeq, sessionId)) }
                    }
                }

                else -> tcpStreamSocket.withWriteLock { writeAndFlush(serverMessageHandler.createErrorResponse(405, cSeq)) }
            }
        }
    }

    private suspend fun handleVideoParamsChanged() {
        videoParamsChanged = false
        stateLock.withLock { waitingForKeyframe = true }
        videoPacketizer = null
        paramInjector.reset()
        while (true) {
            val drained = videoQueue.tryReceive().getOrNull() ?: break
            drained.buf.releaseOne()
            videoQueueSize.decrementAndGet()
        }
        updateQueueStats()
    }

    private suspend fun videoWriterLoop() {
        while (scope.isActive) {
            if (videoParamsChanged) {
                handleVideoParamsChanged()
                continue
            }
            val (streaming, setupDone, waitKey, sender) = stateLock.withLock {
                VideoStateSnapshot(isStreaming, videoSetupDone, waitingForKeyframe, sendRtpPackets)
            }
            if (!streaming) {
                delay(10)
                continue
            }
            val videoBlob = videoQueue.receiveCatching().getOrNull() ?: break
            videoQueueSize.decrementAndGet()
            updateQueueStats()

            if (videoParamsChanged) {
                videoBlob.buf.releaseOne()
                handleVideoParamsChanged()
                continue
            }

            if (videoParams.get() == null) {
                videoBlob.buf.releaseOne()
                continue
            }

            if (!setupDone) {
                videoBlob.buf.releaseOne()
                continue
            }

            // Drop non-keyframes until the first IDR to help HEVC clients sync cleanly
            if (waitKey && !videoBlob.isKeyFrame) {
                videoBlob.buf.releaseOne()
                continue
            }

            if (videoBlob.isKeyFrame) stateLock.withLock { waitingForKeyframe = false }

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
                sender(RtpFrame.VIDEO_TRACK_ID, packets)
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
            val (streaming, setupDone, sender) = stateLock.withLock {
                AudioStateSnapshot(isStreaming, audioSetupDone, sendRtpPackets)
            }
            if (!streaming) {
                delay(10)
                continue
            }
            val audioBlob = audioQueue.receiveCatching().getOrNull() ?: break
            audioQueueSize.decrementAndGet()
            updateQueueStats()

            if (audioParams.get() == null) {
                audioBlob.buf.releaseOne()
                continue
            }

            if (!setupDone) {
                audioBlob.buf.releaseOne()
                continue
            }

            val buffer = ByteBuffer.wrap(audioBlob.buf.bytes, 0, audioBlob.length)
            val info = MediaFrame.Info(offset = 0, size = audioBlob.length, timestamp = audioBlob.timestampUs, isKeyFrame = false)
            val frame = MediaFrame.AudioFrame(buffer, info) {}

            if (audioPacketizer == null) prepareAudioPacketizerIfNeeded()
            val packets = audioPacketizer!!.createPacket(frame)
            try {
                sender(RtpFrame.AUDIO_TRACK_ID, packets)
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
        stateLock.withLock {
            isStreaming = false
            waitingForKeyframe = false
            sendRtpPackets = { _, _ -> }
            videoSetupDone = false
            audioSetupDone = false
        }
        negotiatedInterleavedChannels.clear()
        runCatching { tcpStreamSocket.close() }
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
            videoQueueSize.decrementAndGet()
        }
        while (true) {
            val audioBlob = audioQueue.tryReceive().getOrNull() ?: break
            audioBlob.buf.releaseOne()
            audioQueueSize.decrementAndGet()
        }
        updateQueueStats()

        scope.cancel()
        state = State.Closed
        onClosed(this)
    }

    internal fun onVideoParamsChanged() {
        videoParamsChanged = true
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

    private suspend fun drainRtcp(sock: UdpStreamSocket?) {
        val buffer = ByteArray(1500)
        while (scope.isActive) {
            runCatching { sock?.readInto(buffer)?.let { if (it < 0) break } }
        }
    }

    override fun toString(): String = "RtspServerConnection(address='${tcpStreamSocket.remoteHost}')"

    private fun generateSessionId(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        val chars = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            chars[i++] = HEX_CHARS[v ushr 4]
            chars[i++] = HEX_CHARS[v and 0x0F]
        }
        return String(chars)
    }

    private companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
