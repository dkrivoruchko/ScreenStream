package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtspNetInterface
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.client.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.core.RtspServerMessageHandler
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspError
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal class RtspServer(
    private val appVersion: String,
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit
) {
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var serverJob: Job? = null
    private val serverSockets: MutableList<Pair<ServerSocket, String>> = mutableListOf()

    private val rtspServerConnections = mutableListOf<RtspServerConnection>()
    private val videoParams = AtomicReference<RtspClient.VideoParams?>()
    private val audioParams = AtomicReference<RtspClient.AudioParams?>()

    internal fun setVideoData(videoParams: RtspClient.VideoParams) {
        this.videoParams.set(videoParams)
    }

    internal fun setAudioData(audioParams: RtspClient.AudioParams) {
        this.audioParams.set(audioParams)
    }

    internal fun getClientStatsSnapshot(): List<ClientStats> =
        synchronized(rtspServerConnections) { rtspServerConnections.map { it.stats.value } }

    internal fun start(
        addresses: List<RtspNetInterface>,
        port: Int,
        path: String,
        protocol: RtspSettings.Values.ServerProtocolPolicy
    ) {
        if (serverJob?.isActive == true) stop()

        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }

        serverJob = scope.launch {
            val selectorManager = SelectorManager(coroutineContext).also { this@RtspServer.selectorManager = it }
            runCatching {
                serverSockets.clear()
                val bindFailures = mutableListOf<String>()

                addresses.forEach { networkInterface ->
                    runCatching {
                        val host = networkInterface.address.hostAddress!!.substringBefore('%')
                        val serverSocket = aSocket(selectorManager).tcp().bind(host, port)
                        serverSockets.add(serverSocket to host)
                    }.onFailure { error ->
                        val host = networkInterface.address.hostAddress?.substringBefore('%') ?: "unknown"
                        bindFailures += "$host: ${error.message ?: error::class.simpleName}"
                    }
                }

                if (serverSockets.isEmpty() || bindFailures.isNotEmpty()) {
                    val hosts = addresses.mapNotNull { it.address.hostAddress?.substringBefore('%') }.joinToString(", ")
                    val details = if (bindFailures.isNotEmpty()) bindFailures.joinToString("; ") else "no interfaces"
                    onEvent(
                        RtspStreamingService.InternalEvent.Error(
                            RtspError.UnknownError(IllegalStateException("RTSP bind failed (port=$port, hosts=$hosts): $details"))
                        )
                    )
                    serverSockets.forEach { runCatching { it.first.close() } }
                    serverSockets.clear()
                    runCatching { selectorManager.close() }
                    this@RtspServer.selectorManager = null
                    return@launch
                }

                onEvent(RtspStreamingService.InternalEvent.RtspServerOnStart)

                serverSockets.forEach { (serverSocket, boundHost) ->
                    launch {
                        while (isActive) {
                            val clientSocket = try {
                                serverSocket.accept()
                            } catch (_: Throwable) {
                                if (!isActive) break
                                delay(50)
                                continue
                            }
                            val serverConnection = RtspServerConnection(
                                tcpStreamSocket = TcpStreamSocket(scope.coroutineContext, selectorManager, clientSocket),
                                serverMessageHandler = RtspServerMessageHandler(appVersion, boundHost, port, path),
                                videoParams,
                                audioParams,
                                ::onClientEvent,
                                protocol
                            )
                            synchronized(rtspServerConnections) { rtspServerConnections.add(serverConnection) }
                            serverConnection.start()
                        }
                    }
                }
            }
        }
    }

    internal fun stop() {
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) { stopSuspend() }
        }
    }

    internal fun disconnectAllClients() {
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) { disconnectAllClientsSuspend() }
        }
    }

    private suspend fun stopSuspend() {
        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList().also { rtspServerConnections.clear() } }
        snapshot.forEach { runCatching { it.stop() } }

        runCatching { serverJob?.cancelAndJoin() }
        serverSockets.forEach { runCatching { it.first.close() } }
        serverSockets.clear()
        serverJob = null
        runCatching { selectorManager?.close() }
        selectorManager = null
        runCatching { scopeJob.cancel() }
        onEvent(RtspStreamingService.InternalEvent.RtspServerOnStop)
    }

    private suspend fun disconnectAllClientsSuspend() {
        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList().also { rtspServerConnections.clear() } }
        snapshot.forEach { runCatching { it.stop() } }
    }

    internal fun onVideoFrame(frame: MediaFrame.VideoFrame) {
        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList() }
        if (snapshot.isEmpty()) {
            frame.release()
            return
        }

        val buffer = frame.data
        val size = frame.info.size
        val bytes = ByteArrayPool.get(size)
        buffer.limit(frame.info.offset + size)
        buffer.position(frame.info.offset)
        buffer.get(bytes, 0, size)
        frame.release()

        val shared = SharedBuffer(bytes).also { it.retain(1) }
        val blob = VideoBlob(shared, size, frame.info.timestamp, frame.info.isKeyFrame)

        snapshot.forEach { conn ->
            shared.retain(1)
            if (!conn.enqueueVideo(blob)) shared.releaseOne()
        }

        shared.releaseOne()
    }

    internal fun onAudioFrame(frame: MediaFrame.AudioFrame) {
        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList() }
        if (snapshot.isEmpty()) {
            frame.release()
            return
        }

        val buffer = frame.data
        val size = frame.info.size
        val bytes = ByteArrayPool.get(size)
        buffer.limit(frame.info.offset + size)
        buffer.position(frame.info.offset)
        buffer.get(bytes, 0, size)
        frame.release()

        val shared = SharedBuffer(bytes).also { it.retain(1) }
        val blob = AudioBlob(shared, size, frame.info.timestamp)

        snapshot.forEach { conn ->
            shared.retain(1)
            if (!conn.enqueueAudio(blob)) shared.releaseOne()
        }

        shared.releaseOne()
    }

    private fun onClientEvent(event: RtspStreamingService.InternalEvent) {
        if (event is RtspStreamingService.InternalEvent.RtspServerOnClientDisconnected) {
            synchronized(rtspServerConnections) { rtspServerConnections.remove(event.rtspServerConnection) }
        }
        onEvent(event)
    }
}
