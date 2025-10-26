package info.dvkr.screenstream.rtsp.internal.rtsp.client

import androidx.annotation.AnyThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
import info.dvkr.screenstream.rtsp.internal.rtsp.BitrateCalculator
import info.dvkr.screenstream.rtsp.internal.rtsp.RtcpReporter
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspClientMessages
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspMessagesBase
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.AacPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.G711Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.OpusPacket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.UdpStreamSocket
import info.dvkr.screenstream.rtsp.ui.ConnectionError
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
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

internal class RtspClient(
    private val appVersion: String,
    private val rtspUrl: RtspUrl,
    private val protocol: Protocol,
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

    internal class VideoParams(val codec: Codec.Video, val sps: ByteArray, val pps: ByteArray?, val vps: ByteArray?) {
        val isOk: Boolean
            get() = when (codec) {
                Codec.Video.H264 -> pps != null
                Codec.Video.H265 -> pps != null && vps != null
                Codec.Video.AV1 -> true
            }
    }

    internal class AudioParams(val codec: Codec.Audio, val sampleRate: Int, val isStereo: Boolean)

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

    internal class SelectedPorts(val videoClient: Ports, val videoServer: Ports, val audioClient: Ports, val audioServer: Ports)

    private val commandsManager = RtspClientMessages(
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
        XLog.w(getLog("setVideoData", "$videoCodec"))

        fun ByteArray.stripAnnexBStartCode(): ByteArray = when {
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte() ->
                copyOfRange(4, size)

            size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte() ->
                copyOfRange(3, size)

            else -> this
        }

        val params = when (videoCodec) {
            Codec.Video.H264 -> VideoParams(
                codec = videoCodec,
                sps = sps.stripAnnexBStartCode(),
                pps = pps?.stripAnnexBStartCode(),
                vps = null
            )

            Codec.Video.H265 -> VideoParams(
                codec = videoCodec,
                sps = sps.stripAnnexBStartCode(),
                pps = pps?.stripAnnexBStartCode(),
                vps = vps?.stripAnnexBStartCode()
            )

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
    internal fun setAudioData(audioCodec: Codec.Audio, params: AudioSource.Params) = synchronized(rtspLock) {
        XLog.w(getLog("setAudioData", "$audioCodec"))
        if (currentState == State.STREAMING) error("Cannot change audio codec while streaming")
        if (onlyVideo) error("Cannot change audio codec in only video mode")

        audioParams.set(AudioParams(audioCodec, params.sampleRate, params.isStereo))
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
                        withLock { connect() }
                    }

                val audioParams = if (onlyVideo) null else this@RtspClient.audioParams.get()

                val ports = doRtspHandshake(tcp, rtspUrl, videoParams, audioParams)

                onEvent(RtspStreamingService.InternalEvent.RtspClientOnConnectionSuccess)

                val sendingJob = launch { sendingLoop(tcp, ports, videoParams, audioParams) }
                launch { keepAliveLoop(tcp) }.join()
                sendingJob.cancelAndJoin()
            } catch (e: ConnectException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(ConnectionError.Failed(e.message)))
                error = e
            } catch (e: CertificateException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(ConnectionError.Failed(e.message)))
                error = e
            } catch (e: TimeoutCancellationException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(ConnectionError.Failed("Timeout")))
                error = e
            } catch (e: ConnectionError) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(e))
                error = e
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                XLog.w(getLog("connect", "Unknown error: ${e.message}"), e)
                onEvent(RtspStreamingService.InternalEvent.Error(RtspError.UnknownError(e)))
                error = e
            } finally {
                XLog.w(getLog("connect", "finally"))

                withContext(NonCancellable) {
                    tcpSocket?.withLock {
                        if (isConnected()) {
                            runCatching {
                                writeAndFlush(commandsManager.createTeardown())
                                commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.TEARDOWN)
                            }
                        }
                        close()
                    }
                }

                videoParams.getAndSet(CompletableDeferred()).cancel()
                audioParams.set(null)
                synchronized(rtspLock) { currentState = State.IDLE }

                XLog.w(getLog("connect", "finally Done"))
                if (error == null) onEvent(RtspStreamingService.InternalEvent.RtspClientOnDisconnect)
            }
        }.also {
            connectionJob.set(it)
        }
    }

    internal fun disconnect() = runBlocking {
        XLog.w(this@RtspClient.getLog("disconnect"))
        connectionJob.getAndSet(null)?.cancelAndJoin()
        XLog.w(this@RtspClient.getLog("disconnect", "Done"))
    }

    internal fun destroy() = runBlocking {
        XLog.w(this@RtspClient.getLog("destroy"))
        connectionJob.getAndSet(null)?.cancelAndJoin()
        runCatching { selectorManager.close() }
        scope.cancel()
        XLog.w(this@RtspClient.getLog("destroy", "Done"))
    }

    @Throws
    private suspend fun doRtspHandshake(
        tcpSocket: TcpStreamSocket, rtspUrl: RtspUrl, videoParams: VideoParams, audioParams: AudioParams?
    ): SelectedPorts {
        commandsManager.reset()

        // 1) OPTIONS
        tcpSocket.withLock {
            writeAndFlush(commandsManager.createOptions())
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.OPTIONS)
        }

        // 2) ANNOUNCE
        val announceResp = tcpSocket.withLock {
            writeAndFlush(commandsManager.createAnnounce(videoParams, audioParams))
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.ANNOUNCE)
        }

        when (announceResp.status) {
            200 -> XLog.d(getLog("doRtspHandshake", "ANNOUNCE success"))
            403 -> throw ConnectionError.AccessDenied
            401 -> when {
                rtspUrl.hasAuth().not() -> throw ConnectionError.NoCredentialsError
                else -> {
                    val announceWithAuth = tcpSocket.withLock {
                        commandsManager.applyAuthFor(RtspMessagesBase.Method.ANNOUNCE, rtspUrl.fullPath, announceResp.text)
                        writeAndFlush(commandsManager.createAnnounce(videoParams, audioParams))
                        commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.ANNOUNCE)
                    }
                    when (announceWithAuth.status) {
                        200 -> XLog.d(getLog("doRtspHandshake", "ANNOUNCE with auth success"))
                        401 -> throw ConnectionError.AuthError
                        else -> throw ConnectionError.Failed("ANNOUNCE: [${announceWithAuth.status}] ${announceWithAuth.text}")
                    }
                }
            }

            else -> throw ConnectionError.Failed("ANNOUNCE: [${announceResp.status}] ${announceResp.text}")
        }

        // 3) SETUP for video
        var videoClientPorts = if (protocol == Protocol.UDP) Ports.getEvenOddPair() else Ports.getRandom()
        var videoServerPorts = Ports.getRandom()
        setupTrack(tcpSocket, videoClientPorts, RtpFrame.Companion.VIDEO_TRACK_ID)?.also { (client, server) ->
            client?.let { videoClientPorts = it }
            server?.let { videoServerPorts = it }
        }

        // 4) SETUP for audio
        var audioClientPorts = if (protocol == Protocol.UDP) Ports.getEvenOddPair() else Ports.getRandom()
        var audioServerPorts = Ports.getRandom()
        if (!onlyVideo && audioParams != null) {
            setupTrack(tcpSocket, audioClientPorts, RtpFrame.Companion.AUDIO_TRACK_ID)?.also { (client, server) ->
                client?.let { audioClientPorts = it }
                server?.let { audioServerPorts = it }
            }
        }

        // 5) RECORD
        val recordResp = tcpSocket.withLock {
            writeAndFlush(commandsManager.createRecord())
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.RECORD)
        }
        if (recordResp.status == 401) {
            if (rtspUrl.hasAuth().not()) throw ConnectionError.NoCredentialsError
            val retry = tcpSocket.withLock {
                commandsManager.applyAuthFor(RtspMessagesBase.Method.RECORD, rtspUrl.fullPath, recordResp.text)
                writeAndFlush(commandsManager.createRecord())
                commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.RECORD)
            }
            if (retry.status != 200) throw ConnectionError.Failed("RECORD: [${retry.status}] ${retry.text}")
        } else if (recordResp.status != 200) {
            throw ConnectionError.Failed("RECORD: [${recordResp.status}] ${recordResp.text}")
        }

        return SelectedPorts(videoClientPorts, videoServerPorts, audioClientPorts, audioServerPorts)
    }

    @Throws
    private suspend fun setupTrack(tcpSocket: TcpStreamSocket, clientPorts: Ports, trackId: Int): Pair<Ports?, Ports?>? {
        val setupUriPath = "${rtspUrl.fullPath}/trackID=$trackId"
        var setupRes = tcpSocket.withLock {
            writeAndFlush(commandsManager.createSetup(protocol, clientPorts.client, clientPorts.server, trackId))
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.SETUP)
        }

        if (setupRes.status == 401) {
            if (rtspUrl.hasAuth().not()) throw ConnectionError.NoCredentialsError
            setupRes = tcpSocket.withLock {
                commandsManager.applyAuthFor(RtspMessagesBase.Method.SETUP, setupUriPath, setupRes.text)
                writeAndFlush(commandsManager.createSetup(protocol, clientPorts.client, clientPorts.server, trackId))
                commandsManager.getResponseWithTimeout(::readLine, ::readBytes, RtspMessagesBase.Method.SETUP)
            }
        }

        if (setupRes.status != 200) throw ConnectionError.Failed("SETUP track $trackId: [${setupRes.status}] ${setupRes.text}")

        return when (protocol) {
            Protocol.TCP -> null
            Protocol.UDP -> {
                val pair = commandsManager.getPorts(setupRes)
                if (pair.first == null && pair.second == null) {
                    throw ConnectionError.Failed("SETUP track $trackId: Missing/invalid Transport header")
                }
                pair
            }
        }
    }

    private suspend fun keepAliveLoop(tcpSocket: TcpStreamSocket) {
        while (currentCoroutineContext().isActive && tcpSocket.withLock { isConnected() }) {
            delay(commandsManager.getSuggestedKeepAliveDelayMs())
            if (tcpSocket.withLock { isConnected() }.not()) return
            try {
                tcpSocket.withLock {
                    val hasSession = commandsManager.hasSession()
                    val req = if (hasSession) commandsManager.createGetParameter() else commandsManager.createOptions()
                    writeAndFlush(req)
                    val m = if (hasSession) RtspMessagesBase.Method.GET_PARAMETER else RtspMessagesBase.Method.OPTIONS
                    commandsManager.getResponseWithTimeout(::readLine, ::readBytes, m)
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(ConnectionError.Failed("Keep-alive failed: ${e.message}")))
                tcpSocket.withLock { close() }
                currentCoroutineContext().cancel()
                return
            }
        }
    }

    private suspend fun sendingLoop(tcpSocket: TcpStreamSocket, ports: SelectedPorts, videoParams: VideoParams, audioParams: AudioParams?) {
        var ssrcVideo = Random.Default.nextInt().toLong() and 0xFFFFFFFFL
        val ssrcAudio = Random.Default.nextInt().toLong() and 0xFFFFFFFFL

        val videoUdpSocket = if (protocol == Protocol.TCP) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.videoServer.client, ports.videoClient.client).apply { connect() }

        val audioUdpSocket = if (protocol == Protocol.TCP || onlyVideo) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.audioServer.client, ports.audioClient.client).apply { connect() }

        val bitrateCalculator = BitrateCalculator(scope) { bitrate ->
            onEvent(RtspStreamingService.InternalEvent.RtspClientOnBitrate(bitrate))
        }

        val reporter = RtcpReporter(
            scope = scope,
            protocol = protocol,
            writeToTcpSocket = { tcpHeader, data -> tcpSocket.withLock { if (isConnected()) writeAndFlush(tcpHeader, data) } },
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
                                    protocol == Protocol.TCP -> tcpSocket.withLock {
                                        if (isConnected()) writeAndFlush(rtpFrame.getTcpHeader(), rtpFrame.buffer, 0, rtpFrame.length)
                                    }

                                    rtpFrame is RtpFrame.Video -> videoUdpSocket?.write(rtpFrame.buffer, 0, rtpFrame.length)
                                    rtpFrame is RtpFrame.Audio -> audioUdpSocket?.write(rtpFrame.buffer, 0, rtpFrame.length)
                                }

                                bitrateCalculator.addBytes(rtpFrame.length + if (protocol == Protocol.TCP) 4 else 0)
                                reporter.update(rtpFrame)
                            }
                        }

                        is QueuedItem.NewVideoParams -> {
                            when {
                                queuedItem.videoParams.codec is Codec.Video.H264 && videoPacket is H264Packet -> {
                                    XLog.w(getLog("sendingLoop", "Applying new SPS/PPS for H264"))
                                    videoPacket.reset()
                                    ssrcVideo = Random.Default.nextInt().toLong() and 0xFFFFFFFFL
                                    videoPacket.setSSRC(ssrcVideo)
                                    reporter.setSsrcVideo(ssrcVideo)
                                    videoPacket.setVideoInfo(queuedItem.videoParams.sps, queuedItem.videoParams.pps!!)
                                }

                                queuedItem.videoParams.codec is Codec.Video.H265 && videoPacket is H265Packet -> {
                                    XLog.w(getLog("sendingLoop", "Applying new VPS/SPS/PPS for H265"))
                                    videoPacket.reset()
                                    ssrcVideo = Random.Default.nextInt().toLong() and 0xFFFFFFFFL
                                    videoPacket.setSSRC(ssrcVideo)
                                    reporter.setSsrcVideo(ssrcVideo)
                                    videoPacket.setVideoInfo(
                                        queuedItem.videoParams.sps,
                                        queuedItem.videoParams.pps!!,
                                        queuedItem.videoParams.vps!!
                                    )
                                }
                            }
                        }
                    }
                } catch (_: CancellationException) {
                } catch (error: Throwable) {
                    XLog.w(getLog("sendingLoop", "Error sending packet: ${error.message}"), error)
                    onEvent(RtspStreamingService.InternalEvent.RtspClientOnError(ConnectionError.Failed("Error sending packet: ${error.message}")))
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
