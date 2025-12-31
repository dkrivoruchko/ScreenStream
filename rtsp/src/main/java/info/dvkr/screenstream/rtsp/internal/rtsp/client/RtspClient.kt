package info.dvkr.screenstream.rtsp.internal.rtsp.client

import androidx.annotation.AnyThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.AudioParams
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.VideoParams
import info.dvkr.screenstream.rtsp.internal.interleavedHeader
import info.dvkr.screenstream.rtsp.internal.rtsp.BitrateCalculator
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspBaseMessageHandler
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspError
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.random.Random

internal class RtspClient(
    private val appVersion: String,
    private val generation: Long,
    private val rtspUrl: RtspUrl,
    private val protocolPolicy: RtspSettings.Values.ProtocolPolicy,
    private val onlyVideo: Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit
) {

    private enum class State { IDLE, CONNECTING, STREAMING }

    private sealed class QueuedItem {
        class Frame(val frame: MediaFrame) : QueuedItem()
        class NewVideoParams(val videoParams: VideoParams) : QueuedItem()
    }

    private class MediaFramesBuffer(val capacity: Int = 32) {
        private var itemsChannel = Channel<QueuedItem>(capacity = capacity) { item ->
            if (item is QueuedItem.Frame) {
                XLog.v(getLog("onUndeliveredElement", "Releasing frame: ${item.frame}"))
                item.frame.release()
            }
        }

        private val bufferedFrameCount = AtomicInteger(0)
        private val sendVideoFrames = AtomicLong(0)
        private val sendAudioFrames = AtomicLong(0)
        private val droppedVideoFrames = AtomicLong(0)
        private val droppedAudioFrames = AtomicLong(0)

        // Guarded by rtspLock
        fun trySendFrame(frame: MediaFrame): ChannelResult<Unit> =
            itemsChannel.trySend(QueuedItem.Frame(frame))
                .onSuccess { bufferedFrameCount.incrementAndGet() }
                .onFailure {
                    XLog.w(getLog("trySendFrame", "Frame discarded"))
                    frame.release()
                    when (frame) {
                        is MediaFrame.VideoFrame -> droppedVideoFrames.incrementAndGet()
                        is MediaFrame.AudioFrame -> droppedAudioFrames.incrementAndGet()
                    }
                }

        fun trySendNewVideoParams(videoParams: VideoParams): ChannelResult<Unit> =
            itemsChannel.trySend(QueuedItem.NewVideoParams(videoParams))
                .onFailure { XLog.w(getLog("trySendNewVideoParams", "NewVideoParams discarded")) }

        suspend fun receiveItemWithTimeoutOrNull(timeMillis: Long = 100): QueuedItem? {
            val item = withTimeoutOrNull(timeMillis) { itemsChannel.receive() } ?: return null
            if (item is QueuedItem.Frame) {
                bufferedFrameCount.decrementAndGet()
                when (item.frame) {
                    is MediaFrame.VideoFrame -> sendVideoFrames.incrementAndGet()
                    is MediaFrame.AudioFrame -> sendAudioFrames.incrementAndGet()
                }
            }
            return item
        }

        fun hasCongestion(percentUsed: Float = 20f): Boolean {
            require(percentUsed in 0f..100f)
            val currentSize = bufferedFrameCount.get().toFloat()
            val totalCapacity = capacity.toFloat()
            return currentSize >= totalCapacity * (percentUsed / 100f)
        }

        // Guarded by rtspLock
        fun clear() {
            itemsChannel.close()
            itemsChannel = Channel<QueuedItem>(capacity = capacity) { item ->
                if (item is QueuedItem.Frame) {
                    XLog.v(getLog("onUndeliveredElement", "Releasing frame: ${item.frame}"))
                    item.frame.release()
                }
            }
            bufferedFrameCount.set(0)
            sendVideoFrames.set(0)
            sendAudioFrames.set(0)
            droppedVideoFrames.set(0)
            droppedAudioFrames.set(0)
        }
    }

    internal class Ports(val client: Int, val server: Int) {
        companion object {
            fun getRandom() = Ports(ServerSocket(0).use { it.localPort }, ServerSocket(0).use { it.localPort })
            fun getEvenOddPair(): Ports {
                while (true) {
                    var s1: DatagramSocket? = null
                    var s2: DatagramSocket? = null
                    try {
                        s1 = DatagramSocket(0)
                        val p1 = s1.localPort
                        val even = if (p1 % 2 == 0) p1 else p1 - 1
                        if (even <= 0) continue
                        if (even != p1) {
                            s1.close()
                            s1 = DatagramSocket(even)
                        }
                        s2 = DatagramSocket(even + 1)
                        return Ports(even, even + 1)
                    } catch (_: Throwable) {
                        // try again
                    } finally {
                        runCatching { s1?.close() }
                        runCatching { s2?.close() }
                    }
                }
            }
        }
    }

    internal class SelectedPorts(
        val videoClient: Ports,
        val videoServer: Ports,
        val audioClient: Ports,
        val audioServer: Ports,
        val videoInterleaved: Pair<Int, Int>,
        val audioInterleaved: Pair<Int, Int>,
        val protocol: Protocol
    )

    private data class SetupResult(
        val client: Ports? = null,
        val server: Ports? = null,
        val interleaved: Pair<Int, Int>? = null,
        val protocol: Protocol
    )

    private val commandsManager = RtspClientMessageHandler(
        appVersion, rtspUrl.host, rtspUrl.port, rtspUrl.fullPath, rtspUrl.user, rtspUrl.password
    )
    private val selectorManager = SelectorManager(scope.coroutineContext)
    private val mediaFramesBuffer = MediaFramesBuffer()
    private val videoParams = AtomicReference<CompletableDeferred<VideoParams>>(CompletableDeferred())
    private val audioParams = AtomicReference<AudioParams?>(null)

    private val rtspLock = Any()

    // Guarded by rtspLock
    private var currentState = State.IDLE
        set(value) {
            field = value
            XLog.v(getLog("currentState", "State changed to: $value"))
        }

    private var connectionJob = AtomicReference<Job?>(null)

    @Throws
    @AnyThread
    internal fun setVideoData(videoCodec: Codec.Video, sps: ByteArray, pps: ByteArray?, vps: ByteArray?) = synchronized(rtspLock) {
        XLog.d(getLog("setVideoData", "$videoCodec"))

        val params = when (videoCodec) {
            Codec.Video.H264 -> VideoParams(codec = videoCodec, sps = sps, pps = pps, vps = null)
            Codec.Video.H265 -> VideoParams(codec = videoCodec, sps = sps, pps = pps, vps = vps)
            Codec.Video.AV1 -> VideoParams(videoCodec, sps, pps, vps)
        }
        if (currentState == State.STREAMING) {
            mediaFramesBuffer.trySendNewVideoParams(params)
        } else {
            videoParams.get().complete(params)
        }
    }

    @Throws
    @AnyThread
    internal fun setAudioData(params: AudioParams) = synchronized(rtspLock) {
        XLog.d(getLog("setAudioData", "${params.codec}"))
        if (currentState == State.STREAMING) error("Cannot change audio codec while streaming")
        if (onlyVideo) error("Cannot change audio codec in only video mode")

        audioParams.set(params)
    }

    @AnyThread
    internal fun enqueueFrame(frame: MediaFrame) = synchronized(rtspLock) {
        if (currentState != State.STREAMING || frame is MediaFrame.AudioFrame && onlyVideo) {
            frame.release()
        } else {
            mediaFramesBuffer.trySendFrame(frame)
        }
    }

    internal fun connect() {
        scope.launch {
            var tcpSocket: TcpStreamSocket? = null
            var error: Throwable? = null
            try {
                synchronized(rtspLock) {
                    if (currentState != State.IDLE) error("Cannot connect while streaming")
                    currentState = State.CONNECTING
                }

                val videoParams = withTimeoutOrNull(5_000) { this@RtspClient.videoParams.get().await() }
                if (videoParams?.isOk != true) error("SPS/PPS/VPS not set or incomplete for video codec.")

                val tcp = TcpStreamSocket(Dispatchers.Default, selectorManager, rtspUrl.host, rtspUrl.port, rtspUrl.tlsEnabled)
                    .apply {
                        tcpSocket = this
                        withWriteLock { connect() }
                    }

                val audioParams = if (onlyVideo) null else this@RtspClient.audioParams.get()

                val ports = doRtspHandshake(tcp, rtspUrl, videoParams, audioParams)

                onEvent(RtspStreamingService.InternalEvent.RtspClient.OnConnectionSuccess(generation))

                val sendingJob = launch { sendingLoop(tcp, ports, videoParams, audioParams) }
                launch { keepAliveLoop(tcp) }.join()
                sendingJob.cancelAndJoin()
            } catch (e: Throwable) {
                val mappedMessage = when (e) {
                    is UnresolvedAddressException -> "Invalid address"
                    is TimeoutCancellationException, is SocketTimeoutException -> "Timeout"
                    is SSLHandshakeException, is CertificateException -> "TLS error"
                    is IOException -> e.message ?: "Connection failed"
                    else -> null
                }
                when {
                    e is CancellationException -> Unit

                    e is RtspError.ClientError -> {
                        XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                        onEvent(RtspStreamingService.InternalEvent.RtspClient.OnError(generation, e))
                        error = e
                    }

                    mappedMessage != null -> {
                        XLog.w(getLog("connect", "Connection error: $mappedMessage"), e)
                        onEvent(
                            RtspStreamingService.InternalEvent.RtspClient.OnError(
                                generation, RtspError.ClientError.Failed(mappedMessage)
                            )
                        )
                        error = e
                    }

                    else -> {
                        XLog.w(getLog("connect", "Unknown error: ${e.message}"), e)
                        onEvent(RtspStreamingService.InternalEvent.Error(RtspError.UnknownError(e)))
                        error = e
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    tcpSocket?.let { socket ->
                        socket.withWriteLock {
                            if (isConnected()) {
                                runCatching { writeAndFlush(commandsManager.createTeardown()) }
                            }
                        }
                        runCatching { commandsManager.getResponseWithTimeout(socket, RtspBaseMessageHandler.Method.TEARDOWN) }
                        socket.close()
                    }
                }

                videoParams.getAndSet(CompletableDeferred()).cancel()
                audioParams.set(null)
                synchronized(rtspLock) { currentState = State.IDLE }

                if (error == null) onEvent(RtspStreamingService.InternalEvent.RtspClient.OnDisconnect(generation))
            }
        }.also {
            connectionJob.set(it)
        }
    }

    internal fun disconnect() = runBlocking {
        XLog.d(this@RtspClient.getLog("disconnect"))
        connectionJob.getAndSet(null)?.cancelAndJoin()
        XLog.d(this@RtspClient.getLog("disconnect", "Done"))
    }

    internal fun destroy() = runBlocking {
        XLog.d(this@RtspClient.getLog("destroy"))
        connectionJob.getAndSet(null)?.cancelAndJoin()
        runCatching { selectorManager.close() }
        scope.cancel()
        XLog.d(this@RtspClient.getLog("destroy", "Done"))
    }

    @Throws
    private suspend fun doRtspHandshake(
        tcpSocket: TcpStreamSocket, rtspUrl: RtspUrl, videoParams: VideoParams, audioParams: AudioParams?
    ): SelectedPorts {
        commandsManager.reset()

        // 1) OPTIONS
        tcpSocket.withWriteLock { writeAndFlush(commandsManager.createOptions()) }
        var optionsResp = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.OPTIONS)
        if (optionsResp.status == 401) {
            if (rtspUrl.hasAuth().not()) throw RtspError.ClientError.NoCredentialsError()
            commandsManager.applyAuthFor(RtspBaseMessageHandler.Method.OPTIONS, rtspUrl.fullPath, optionsResp.text)
            tcpSocket.withWriteLock { writeAndFlush(commandsManager.createOptions()) }
            optionsResp = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.OPTIONS)
        }
        when (optionsResp.status) {
            200 -> Unit
            401 -> throw RtspError.ClientError.AuthError()
            403 -> throw RtspError.ClientError.AccessDenied()
            else -> throw RtspError.ClientError.Failed("OPTIONS: [${optionsResp.status}] ${optionsResp.text}")
        }

        // 2) ANNOUNCE
        tcpSocket.withWriteLock { writeAndFlush(commandsManager.createAnnounce(videoParams, audioParams)) }
        val announceResp = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.ANNOUNCE)

        when (announceResp.status) {
            200 -> XLog.d(getLog("doRtspHandshake", "ANNOUNCE success"))
            403 -> throw RtspError.ClientError.AccessDenied()
            401 -> when {
                rtspUrl.hasAuth().not() -> throw RtspError.ClientError.NoCredentialsError()
                else -> {
                    commandsManager.applyAuthFor(
                        RtspBaseMessageHandler.Method.ANNOUNCE,
                        rtspUrl.fullPath,
                        announceResp.text,
                        videoParams,
                        audioParams
                    )
                    tcpSocket.withWriteLock { writeAndFlush(commandsManager.createAnnounce(videoParams, audioParams)) }
                    val announceWithAuth = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.ANNOUNCE)
                    when (announceWithAuth.status) {
                        200 -> XLog.d(getLog("doRtspHandshake", "ANNOUNCE with auth success"))
                        401 -> throw RtspError.ClientError.AuthError()
                        else -> throw RtspError.ClientError.Failed("ANNOUNCE: [${announceWithAuth.status}] ${announceWithAuth.text}")
                    }
                }
            }

            else -> throw RtspError.ClientError.Failed("ANNOUNCE: [${announceResp.status}] ${announceResp.text}")
        }

        // 3) SETUP for video
        var videoClientPorts = if (protocolPolicy == RtspSettings.Values.ProtocolPolicy.TCP) Ports.getRandom() else Ports.getEvenOddPair()
        var videoServerPorts = Ports.getRandom()
        var videoInterleaved = (RtpFrame.VIDEO_TRACK_ID shl 1) to ((RtpFrame.VIDEO_TRACK_ID shl 1) + 1)
        val videoResult = setupTrack(tcpSocket, videoClientPorts, RtpFrame.VIDEO_TRACK_ID, protocolPolicy)
        videoResult.client?.let { videoClientPorts = it }
        videoResult.server?.let { videoServerPorts = it }
        videoResult.interleaved?.let { videoInterleaved = it }

        if (protocolPolicy == RtspSettings.Values.ProtocolPolicy.AUTO) {
            XLog.i(getLog("doRtspHandshake", "AUTO selected transport: ${videoResult.protocol}"))
        }

        // 4) SETUP for audio
        val audioPolicy = when (protocolPolicy) {
            RtspSettings.Values.ProtocolPolicy.AUTO ->
                if (videoResult.protocol == Protocol.UDP) RtspSettings.Values.ProtocolPolicy.UDP
                else RtspSettings.Values.ProtocolPolicy.TCP

            else -> protocolPolicy
        }
        var audioClientPorts = if (audioPolicy == RtspSettings.Values.ProtocolPolicy.TCP) Ports.getRandom() else Ports.getEvenOddPair()

        var audioServerPorts = Ports.getRandom()
        var audioInterleaved = (RtpFrame.AUDIO_TRACK_ID shl 1) to ((RtpFrame.AUDIO_TRACK_ID shl 1) + 1)
        if (!onlyVideo && audioParams != null) {
            val audioResult = setupTrack(tcpSocket, audioClientPorts, RtpFrame.AUDIO_TRACK_ID, audioPolicy)
            audioResult.client?.let { audioClientPorts = it }
            audioResult.server?.let { audioServerPorts = it }
            audioResult.interleaved?.let { audioInterleaved = it }
        }

        // 5) RECORD
        tcpSocket.withWriteLock { writeAndFlush(commandsManager.createRecord()) }
        val recordResp = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.RECORD)
        if (recordResp.status == 401) {
            if (rtspUrl.hasAuth().not()) throw RtspError.ClientError.NoCredentialsError()
            commandsManager.applyAuthFor(RtspBaseMessageHandler.Method.RECORD, rtspUrl.fullPath, recordResp.text)
            tcpSocket.withWriteLock { writeAndFlush(commandsManager.createRecord()) }
            val retry = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.RECORD)
            when (retry.status) {
                200 -> Unit
                401 -> throw RtspError.ClientError.AuthError()
                403 -> throw RtspError.ClientError.AccessDenied()
                else -> throw RtspError.ClientError.Failed("RECORD: [${retry.status}] ${retry.text}")
            }
        } else {
            when (recordResp.status) {
                200 -> Unit
                403 -> throw RtspError.ClientError.AccessDenied()
                else -> throw RtspError.ClientError.Failed("RECORD: [${recordResp.status}] ${recordResp.text}")
            }
        }

        return SelectedPorts(
            videoClientPorts, videoServerPorts, audioClientPorts, audioServerPorts, videoInterleaved, audioInterleaved, videoResult.protocol
        )
    }

    @Throws
    private suspend fun setupTrack(
        tcpSocket: TcpStreamSocket,
        clientPorts: Ports,
        trackId: Int,
        protocolPolicy: RtspSettings.Values.ProtocolPolicy
    ): SetupResult {
        val setupUriPath = "${rtspUrl.fullPath}/trackID=$trackId"
        tcpSocket.withWriteLock {
            writeAndFlush(
                commandsManager.createSetup(protocolPolicy, clientPorts.client, clientPorts.server, trackId)
            )
        }
        var setupRes = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.SETUP)

        if (setupRes.status == 401) {
            if (rtspUrl.hasAuth().not()) throw RtspError.ClientError.NoCredentialsError()
            commandsManager.applyAuthFor(RtspBaseMessageHandler.Method.SETUP, setupUriPath, setupRes.text)
            tcpSocket.withWriteLock {
                writeAndFlush(commandsManager.createSetup(protocolPolicy, clientPorts.client, clientPorts.server, trackId))
            }
            setupRes = commandsManager.getResponseWithTimeout(tcpSocket, RtspBaseMessageHandler.Method.SETUP)
        }

        when (setupRes.status) {
            200 -> Unit
            401 -> throw RtspError.ClientError.AuthError()
            403 -> throw RtspError.ClientError.AccessDenied()
            else -> throw RtspError.ClientError.Failed("SETUP track $trackId: [${setupRes.status}] ${setupRes.text}")
        }

        val interleaved = commandsManager.getInterleaved(setupRes)
        val pair = if (interleaved == null) commandsManager.getPorts(setupRes) else null
        return when (protocolPolicy) {
            RtspSettings.Values.ProtocolPolicy.TCP -> interleaved?.let {
                SetupResult(interleaved = it, protocol = Protocol.TCP)
            } ?: throw RtspError.ClientError.Failed("SETUP track $trackId: Missing/invalid Transport header")

            RtspSettings.Values.ProtocolPolicy.UDP -> {
                if (interleaved != null) throw RtspError.ClientError.Failed("SETUP track $trackId: Unexpected TCP Transport")
                if (pair?.first == null && pair?.second == null) {
                    throw RtspError.ClientError.Failed("SETUP track $trackId: Missing/invalid Transport header")
                }
                SetupResult(client = pair.first, server = pair.second, protocol = Protocol.UDP)
            }

            RtspSettings.Values.ProtocolPolicy.AUTO -> {
                if (interleaved != null) return SetupResult(interleaved = interleaved, protocol = Protocol.TCP)
                if (pair?.first == null && pair?.second == null) {
                    throw RtspError.ClientError.Failed("SETUP track $trackId: Missing/invalid Transport header")
                }
                SetupResult(client = pair.first, server = pair.second, protocol = Protocol.UDP)
            }
        }
    }

    private suspend fun keepAliveLoop(tcpSocket: TcpStreamSocket) {
        while (currentCoroutineContext().isActive) {
            delay(commandsManager.getSuggestedKeepAliveDelayMs())
            try {
                val hasSession = commandsManager.hasSession()
                val method = if (hasSession) RtspBaseMessageHandler.Method.GET_PARAMETER else RtspBaseMessageHandler.Method.OPTIONS
                var message = if (hasSession) commandsManager.createGetParameter() else commandsManager.createOptions()
                tcpSocket.withWriteLock { writeAndFlush(message) }
                var response = commandsManager.getResponseWithTimeout(tcpSocket, method)
                if (response.status == 401) {
                    if (rtspUrl.hasAuth().not()) throw RtspError.ClientError.NoCredentialsError()
                    commandsManager.applyAuthFor(method, rtspUrl.fullPath, response.text)
                    message = if (hasSession) commandsManager.createGetParameter() else commandsManager.createOptions()
                    tcpSocket.withWriteLock { writeAndFlush(message) }
                    response = commandsManager.getResponseWithTimeout(tcpSocket, method)
                }
                when (response.status) {
                    200 -> Unit
                    401 -> throw RtspError.ClientError.AuthError()
                    403 -> throw RtspError.ClientError.AccessDenied()
                    else -> throw RtspError.ClientError.Failed("${method.name}: [${response.status}] ${response.text}")
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                if (currentCoroutineContext().isActive.not()) return
                if (tcpSocket.isConnected().not()) return
                if (e is RtspError.ClientError) {
                    onEvent(RtspStreamingService.InternalEvent.RtspClient.OnError(generation, e))
                } else {
                    onEvent(
                        RtspStreamingService.InternalEvent.RtspClient.OnError(
                            generation, RtspError.ClientError.Failed("Keep-alive failed: ${e.message}")
                        )
                    )
                }
                tcpSocket.close()
                currentCoroutineContext().cancel()
                return
            }
        }
    }

    private suspend fun sendingLoop(tcpSocket: TcpStreamSocket, ports: SelectedPorts, videoParams: VideoParams, audioParams: AudioParams?) {
        var ssrcVideo = Random.nextInt().toLong() and 0xFFFFFFFFL
        val ssrcAudio = Random.nextInt().toLong() and 0xFFFFFFFFL
        val protocol = ports.protocol

        val videoUdpSocket = if (protocol == Protocol.TCP) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.videoServer.client, ports.videoClient.client).apply { connect() }

        val audioUdpSocket = if (protocol == Protocol.TCP || onlyVideo) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.audioServer.client, ports.audioClient.client).apply { connect() }

        val bitrateCalculator = BitrateCalculator(scope) { bitrate ->
            onEvent(RtspStreamingService.InternalEvent.RtspClient.OnBitrate(generation, bitrate))
        }

        val videoRtcpChannel = ports.videoInterleaved.second
        val audioRtcpChannel = ports.audioInterleaved.second
        val reporter = RtcpReporter(
            scope = scope,
            protocol = protocol,
            writeToTcpSocket = { tcpHeader, data ->
                if (protocol == Protocol.TCP) {
                    val channelId = tcpHeader[1].toInt() and 0xFF
                    val mapped = when (channelId) {
                        1 -> videoRtcpChannel
                        3 -> audioRtcpChannel
                        else -> channelId
                    }
                    tcpHeader[1] = mapped.toByte()
                }
                tcpSocket.withWriteLock { if (isConnected()) writeAndFlush(tcpHeader, data) }
            },
            videoUdpSocket = if (protocol == Protocol.TCP) null else
                UdpStreamSocket(selectorManager, rtspUrl.host, ports.videoServer.server, ports.videoClient.server).apply { connect() },
            audioUdpSocket = if (protocol == Protocol.TCP || onlyVideo) null else
                UdpStreamSocket(selectorManager, rtspUrl.host, ports.audioServer.server, ports.audioClient.server).apply { connect() },
            ssrcVideo = ssrcVideo,
            ssrcAudio = ssrcAudio
        )

        val videoPacket = when (videoParams.codec) {
            Codec.Video.H264 -> H264Packet().apply { setVideoInfo(videoParams.sps, videoParams.pps!!) }
            Codec.Video.H265 -> H265Packet().apply { setVideoInfo(videoParams.sps, videoParams.pps!!, videoParams.vps!!) }
            Codec.Video.AV1 -> Av1Packet()
        }.apply { setSSRC(ssrcVideo) }

        val audioPacket = audioParams?.run {
            when (audioParams.codec) {
                Codec.Audio.G711 -> G711Packet().apply { setAudioInfo(audioParams.sampleRate) }
                Codec.Audio.AAC -> AacPacket().apply { setAudioInfo(audioParams.sampleRate) }
                Codec.Audio.OPUS -> OpusPacket().apply { setAudioInfo(audioParams.sampleRate) }
            }.apply { setSSRC(ssrcAudio) }
        }

        try {
            synchronized(rtspLock) {
                currentState = State.STREAMING
                mediaFramesBuffer.clear()
            }
            bitrateCalculator.start()

            while (currentCoroutineContext().isActive) {
                val queuedItem = mediaFramesBuffer.receiveItemWithTimeoutOrNull() ?: continue

                try {
                    when (queuedItem) {
                        is QueuedItem.Frame -> {
                            // If queue is congested, drop non-key video frames to reduce latency
                            if (queuedItem.frame is MediaFrame.VideoFrame && mediaFramesBuffer.hasCongestion(75f) && queuedItem.frame.info.isKeyFrame.not()) {
                                queuedItem.frame.release()
                                continue
                            }
                            val rtpFrames = when (val mediaFrame = queuedItem.frame) {
                                is MediaFrame.VideoFrame -> videoPacket.createPacket(mediaFrame)
                                is MediaFrame.AudioFrame -> audioPacket?.createPacket(mediaFrame) ?: emptyList()
                            }

                            for (rtpFrame in rtpFrames) {
                                when {
                                    protocol == Protocol.TCP -> tcpSocket.withWriteLock {
                                        if (isConnected()) {
                                            val channel = when (rtpFrame) {
                                                is RtpFrame.Video -> ports.videoInterleaved.first
                                                is RtpFrame.Audio -> ports.audioInterleaved.first
                                            }
                                            val tcpHeader = interleavedHeader(channel, rtpFrame.length)
                                            writeAndFlush(tcpHeader, rtpFrame.buffer, 0, rtpFrame.length)
                                        }
                                    }

                                    rtpFrame is RtpFrame.Video -> videoUdpSocket?.write(rtpFrame.buffer, 0, rtpFrame.length)
                                    rtpFrame is RtpFrame.Audio -> audioUdpSocket?.write(rtpFrame.buffer, 0, rtpFrame.length)
                                }

                                bitrateCalculator.addBytes(rtpFrame.length + if (protocol == Protocol.TCP) 4 else 0)
                                reporter.update(rtpFrame)
                            }
                        }

                        is QueuedItem.NewVideoParams -> {
                            val vParams = queuedItem.videoParams
                            when {
                                vParams.codec is Codec.Video.H264 && videoPacket is H264Packet -> {
                                    XLog.w(getLog("sendingLoop", "Applying new SPS/PPS for H264"))
                                    videoPacket.reset()
                                    ssrcVideo = Random.nextInt().toLong() and 0xFFFFFFFFL
                                    videoPacket.setSSRC(ssrcVideo)
                                    reporter.setSsrcVideo(ssrcVideo)
                                    videoPacket.setVideoInfo(vParams.sps, vParams.pps!!)
                                }

                                vParams.codec is Codec.Video.H265 && videoPacket is H265Packet -> {
                                    XLog.w(getLog("sendingLoop", "Applying new VPS/SPS/PPS for H265"))
                                    videoPacket.reset()
                                    ssrcVideo = Random.nextInt().toLong() and 0xFFFFFFFFL
                                    videoPacket.setSSRC(ssrcVideo)
                                    reporter.setSsrcVideo(ssrcVideo)
                                    videoPacket.setVideoInfo(vParams.sps, vParams.pps!!, vParams.vps!!)
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                } catch (error: Throwable) {
                    if (currentCoroutineContext().isActive.not()) return
                    XLog.w(getLog("sendingLoop", "Error sending packet: ${error.message}"), error)
                    onEvent(
                        RtspStreamingService.InternalEvent.RtspClient.OnError(
                            generation, RtspError.ClientError.Failed("Error sending packet: ${error.message}")
                        )
                    )
                    currentCoroutineContext().cancel()
                } finally {
                    if (queuedItem is QueuedItem.Frame) {
                        queuedItem.frame.release()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                bitrateCalculator.stop()
                reporter.close()
                videoUdpSocket?.close()
                audioUdpSocket?.close()
            }
            synchronized(rtspLock) {
                currentState = State.IDLE
                mediaFramesBuffer.clear()
            }
        }
    }
}
