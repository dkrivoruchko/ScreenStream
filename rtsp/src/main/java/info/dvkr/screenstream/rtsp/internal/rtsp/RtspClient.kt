package info.dvkr.screenstream.rtsp.internal.rtsp

import androidx.annotation.AnyThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.RtpFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService.InternalEvent
import info.dvkr.screenstream.rtsp.internal.audio.AudioSource
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
    private val onEvent: (InternalEvent) -> Unit
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
        }
    }

    internal class SelectedPorts(val videoClient: Ports, val videoServer: Ports, val audioClient: Ports, val audioServer: Ports)

    private val commandsManager = CommandsManager(appVersion, rtspUrl.host, rtspUrl.port, rtspUrl.fullPath, rtspUrl.user, rtspUrl.password)
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
        val params = VideoParams(videoCodec, sps, pps, vps)
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

                onEvent(InternalEvent.RtspClientOnConnectionSuccess)

                val sendingJob = launch { sendingLoop(tcp, ports, videoParams, audioParams) }
                launch { keepAliveLoop(tcp) }.join()
                sendingJob.join()
            } catch (e: ConnectException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(InternalEvent.RtspClientOnError(ConnectionError.Failed(e.message)))
                error = e
            } catch (e: CertificateException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(InternalEvent.RtspClientOnError(ConnectionError.Failed(e.message)))
                error = e
            } catch (e: TimeoutCancellationException) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(InternalEvent.RtspClientOnError(ConnectionError.Failed("Timeout")))
                error = e
            } catch (e: ConnectionError) {
                XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                onEvent(InternalEvent.RtspClientOnError(e))
                error = e
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                XLog.w(getLog("connect", "Unknown error: ${e.message}"), e)
                onEvent(InternalEvent.Error(RtspError.UnknownError(e)))
                error = e
            } finally {
                XLog.w(getLog("connect", "finally"))

                withContext(NonCancellable) {
                    tcpSocket?.withLock {
                        if (isConnected()) {
                            runCatching {
                                writeAndFlush(commandsManager.createTeardown())
                                commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.TEARDOWN)
                            }
                        }
                        close()
                    }
                }

                videoParams.getAndSet(CompletableDeferred()).cancel()
                audioParams.set(null)
                synchronized(rtspLock) { currentState = State.IDLE }

                XLog.w(getLog("connect", "finally Done"))
                if (error == null) onEvent(InternalEvent.RtspClientOnDisconnect)
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
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.OPTIONS)
        }

        // 2) ANNOUNCE
        val announceResp = tcpSocket.withLock {
            writeAndFlush(commandsManager.createAnnounce(videoParams, audioParams))
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.ANNOUNCE)
        }

        when (announceResp.status) {
            200 -> XLog.d(getLog("doRtspHandshake", "ANNOUNCE success"))
            403 -> throw ConnectionError.AccessDenied
            401 -> when {
                rtspUrl.hasAuth().not() -> throw ConnectionError.NoCredentialsError
                else -> {
                    val announceWithAuth = tcpSocket.withLock {
                        writeAndFlush(commandsManager.createAnnounceWithAuth(videoParams, audioParams, announceResp.text))
                        commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.ANNOUNCE)
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
        var videoClientPorts = Ports.getRandom()
        var videoServerPorts = Ports.getRandom()
        setupTrack(tcpSocket, videoClientPorts, RtpFrame.VIDEO_TRACK_ID)?.also { (client, server) ->
            client?.let { videoClientPorts = it }
            server?.let { videoServerPorts = it }
        }

        // 4) SETUP for audio
        var audioClientPorts = Ports.getRandom()
        var audioServerPorts = Ports.getRandom()
        if (!onlyVideo && audioParams != null) {
            setupTrack(tcpSocket, audioClientPorts, RtpFrame.AUDIO_TRACK_ID)?.also { (client, server) ->
                client?.let { audioClientPorts = it }
                server?.let { audioServerPorts = it }
            }
        }

        // 5) RECORD
        val recordResp = tcpSocket.withLock {
            writeAndFlush(commandsManager.createRecord())
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.RECORD)
        }
        if (recordResp.status != 200) throw ConnectionError.Failed("RECORD: [${announceResp.status}] ${announceResp.text}")

        return SelectedPorts(videoClientPorts, videoServerPorts, audioClientPorts, audioServerPorts)
    }

    @Throws
    private suspend fun setupTrack(tcpSocket: TcpStreamSocket, clientPorts: Ports, trackId: Int): Pair<Ports?, Ports?>? {
        val setupRes = tcpSocket.withLock {
            writeAndFlush(commandsManager.createSetup(protocol, clientPorts.client, clientPorts.server, trackId))
            commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.SETUP)
        }

        if (setupRes.status != 200) throw ConnectionError.Failed("SETUP track $trackId: [${setupRes.status}] ${setupRes.text}")

        return when (protocol) {
            Protocol.TCP -> null
            Protocol.UDP -> commandsManager.getPorts(setupRes)
        }
    }

    private suspend fun keepAliveLoop(tcpSocket: TcpStreamSocket) {
        while (currentCoroutineContext().isActive && tcpSocket.withLock { isConnected() }) {
            delay(60_000)
            if (tcpSocket.withLock { isConnected() }.not()) return
            try {
                tcpSocket.withLock {
                    writeAndFlush(commandsManager.createOptions())
                    commandsManager.getResponseWithTimeout(::readLine, ::readBytes, CommandsManager.Method.OPTIONS)
                }
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                onEvent(InternalEvent.RtspClientOnError(ConnectionError.Failed("Keep-alive failed: ${error.message}")))
                currentCoroutineContext().cancel()
                return
            }
        }
    }

    private suspend fun sendingLoop(tcpSocket: TcpStreamSocket, ports: SelectedPorts, videoParams: VideoParams, audioParams: AudioParams?) {
        val ssrcVideo = Random.nextInt().toLong() and 0xFFFFFFFFL
        val ssrcAudio = Random.nextInt().toLong() and 0xFFFFFFFFL

        val videoUdpSocket = if (protocol == Protocol.TCP) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.videoServer.client, ports.videoClient.client).apply { connect() }

        val audioUdpSocket = if (protocol == Protocol.TCP || onlyVideo) null else
            UdpStreamSocket(selectorManager, rtspUrl.host, ports.audioServer.client, ports.audioClient.client).apply { connect() }

        val bitrateCalculator = BitrateCalculator(scope) { bitrate ->
            onEvent(InternalEvent.RtspClientOnBitrate(bitrate))
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
            Codec.Video.H264 -> H264Packet().apply { sentVideoInfo(videoParams.sps, videoParams.pps!!) }
            Codec.Video.H265 -> H265Packet()
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
                            val rtpFrames = when (val mediaFrame = queuedItem.frame) {
                                is MediaFrame.VideoFrame -> videoPacket.createPacket(mediaFrame)
                                is MediaFrame.AudioFrame -> audioPacket?.createPacket(mediaFrame) ?: emptyList()
                            }

                            for (rtpFrame in rtpFrames) {
                                when {
                                    protocol == Protocol.TCP -> tcpSocket.withLock {
                                        if (isConnected()) writeAndFlush(rtpFrame.getTcpHeader(), rtpFrame.buffer)
                                    }

                                    rtpFrame is RtpFrame.Video -> videoUdpSocket?.write(rtpFrame.buffer)
                                    rtpFrame is RtpFrame.Audio -> audioUdpSocket?.write(rtpFrame.buffer)
                                }

                                bitrateCalculator.addBytes(rtpFrame.length + if (protocol == Protocol.TCP) 4 else 0)
                                reporter.update(rtpFrame)
                            }
                        }

                        is QueuedItem.NewVideoParams -> {
                            if (queuedItem.videoParams.codec is Codec.Video.H264 && videoPacket is H264Packet) {
                                XLog.w(getLog("sendingLoop", "Setting new SPS/PPS to H264Packet."))
                                videoPacket.sentVideoInfo(queuedItem.videoParams.sps, queuedItem.videoParams.pps!!)
                            }
                        }
                    }
                } catch (_: CancellationException) {
                } catch (error: Throwable) {
                    XLog.w(getLog("sendingLoop", "Error sending packet: ${error.message}"), error)
                    onEvent(InternalEvent.RtspClientOnError(ConnectionError.Failed("Error sending packet: ${error.message}")))
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