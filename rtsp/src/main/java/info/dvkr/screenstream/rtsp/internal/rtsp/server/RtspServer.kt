package info.dvkr.screenstream.rtsp.internal.rtsp.server

import info.dvkr.screenstream.rtsp.internal.AudioParams
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtspNetInterface
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.VideoParams
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspBindError
import info.dvkr.screenstream.rtsp.ui.RtspError
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
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
import java.net.BindException
import java.util.concurrent.atomic.AtomicReference

internal class RtspServer(
    private val appVersion: String,
    private val generation: Long,
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit,
    private val onRequestKeyFrame: () -> Unit
) {
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var serverJob: Job? = null
    private val serverSocketsLock = Any()
    private val serverSockets: MutableList<BoundSocket> = mutableListOf()

    private val rtspServerConnections = mutableListOf<RtspServerConnection>()
    private val videoParams = AtomicReference<VideoParams?>()
    private val audioParams = AtomicReference<AudioParams?>()

    private data class BoundSocket(val socket: ServerSocket, val advertisedHost: String)
    private data class BindFailure(val key: String, val host: String, val bindError: RtspBindError, val technicalDetails: String?)
    private data class BindResult(val boundSockets: List<BoundSocket>, val bindFailures: List<BindFailure>)

    internal fun setVideoData(videoCodec: Codec.Video, sps: ByteArray, pps: ByteArray?, vps: ByteArray?) {
        val newParams = VideoParams(videoCodec, sps, pps, vps)
        if (videoParams.get()?.contentEquals(newParams) == true) return
        this.videoParams.set(newParams)

        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList() }
        if (snapshot.isNotEmpty()) {
            onRequestKeyFrame()
            snapshot.forEach { it.onVideoParamsChanged() }
        }
    }

    internal fun setAudioData(audioParams: AudioParams?) {
        this.audioParams.set(audioParams)
    }

    internal fun clearMediaParams() {
        videoParams.set(null)
        audioParams.set(null)
    }

    internal fun getClientStatsSnapshot(): List<ClientStats> =
        synchronized(rtspServerConnections) { rtspServerConnections.map { it.stats.value } }

    internal fun start(
        addresses: List<RtspNetInterface>,
        port: Int,
        path: String,
        protocol: RtspSettings.Values.ProtocolPolicy
    ) {
        if (serverJob?.isActive == true) stop()

        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }

        serverJob = scope.launch {
            val selectorManager = SelectorManager(coroutineContext).also { this@RtspServer.selectorManager = it }
            var boundSockets: List<BoundSocket> = emptyList()
            var publishedSockets = false
            try {
                val bindResult = bindAll(addresses, port, selectorManager)
                boundSockets = bindResult.boundSockets

                if (bindResult.bindFailures.isNotEmpty()) {
                    onEvent(
                        RtspStreamingService.InternalEvent.RtspServer.OnBindFailures(
                            generation = generation,
                            failures = bindResult.bindFailures.associate { it.key to it.bindError }
                        )
                    )
                }

                if (bindResult.boundSockets.isEmpty()) {
                    val error = RtspError.UnknownError(buildBindFailureException(addresses, port, bindResult.bindFailures))
                    onEvent(RtspStreamingService.InternalEvent.RtspServer.OnError(generation = generation, error = error))
                    return@launch
                }

                synchronized(serverSocketsLock) {
                    serverSockets.clear()
                    serverSockets.addAll(bindResult.boundSockets)
                }

                publishedSockets = true

                onEvent(RtspStreamingService.InternalEvent.RtspServer.OnStart(generation))

                bindResult.boundSockets.forEach { launchAcceptor(it, selectorManager, port, path, protocol) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onEvent(RtspStreamingService.InternalEvent.RtspServer.OnError(generation, RtspError.UnknownError(e)))
            } finally {
                if (!publishedSockets) {
                    boundSockets.forEach { runCatching { it.socket.close() } }
                    runCatching { selectorManager.close() }
                    if (this@RtspServer.selectorManager === selectorManager) this@RtspServer.selectorManager = null
                }
            }
        }
    }

    private suspend fun bindAll(addresses: List<RtspNetInterface>, port: Int, selectorManager: SelectorManager): BindResult {
        val boundSockets = mutableListOf<BoundSocket>()
        val bindFailures = mutableListOf<BindFailure>()

        addresses.forEach { networkInterface ->
            try {
                val bindHost = networkInterface.address.hostAddress
                    ?: throw IllegalStateException("Null hostAddress for ${networkInterface.label}")
                val serverSocket = aSocket(selectorManager).tcp().bind(bindHost, port) { reuseAddress = true }
                boundSockets += BoundSocket(serverSocket, bindHost.substringBefore('%'))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                bindFailures += BindFailure(
                    key = networkInterface.bindKey,
                    host = networkInterface.hostAddress,
                    bindError = classifyBindError(error),
                    technicalDetails = error.message ?: error::class.simpleName
                )
            }
        }

        return BindResult(boundSockets, bindFailures)
    }

    private fun buildBindFailureException(addresses: List<RtspNetInterface>, port: Int, bindFailures: List<BindFailure>): Throwable {
        val hosts = addresses.mapNotNull { it.address.hostAddress?.substringBefore('%') }.joinToString(", ")
        val details = bindFailures.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.host}: ${it.technicalDetails ?: it.bindError::class.simpleName}" }
            ?: "no interfaces"
        return IllegalStateException("RTSP bind failed (port=$port, hosts=$hosts): $details")
    }

    private fun CoroutineScope.launchAcceptor(
        boundSocket: BoundSocket,
        selectorManager: SelectorManager,
        port: Int,
        path: String,
        protocol: RtspSettings.Values.ProtocolPolicy
    ) {
        launch {
            while (isActive) {
                val clientSocket = try {
                    boundSocket.socket.accept()
                } catch (_: Throwable) {
                    if (!isActive) break
                    delay(50)
                    continue
                }

                val serverConnection = RtspServerConnection(
                    parentJob = scopeJob,
                    tcpStreamSocket = TcpStreamSocket(scope.coroutineContext, selectorManager, clientSocket),
                    serverMessageHandler = RtspServerMessageHandler(appVersion, boundSocket.advertisedHost, port, path),
                    videoParams,
                    audioParams,
                    serverProtocolPolicy = protocol,
                    onRequestKeyFrame = onRequestKeyFrame,
                    onClosed = { synchronized(rtspServerConnections) { rtspServerConnections.remove(it) } }
                )
                synchronized(rtspServerConnections) { rtspServerConnections.add(serverConnection) }
                serverConnection.start()
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

        val serverSocketsSnapshot = synchronized(serverSocketsLock) { serverSockets.toList().also { serverSockets.clear() } }
        serverSocketsSnapshot.forEach { runCatching { it.socket.close() } }
        runCatching { selectorManager?.close() }
        selectorManager = null
        runCatching { serverJob?.cancelAndJoin() }
        serverJob = null
        runCatching { scopeJob.cancel() }
        onEvent(RtspStreamingService.InternalEvent.RtspServer.OnStop(generation))
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

    private fun classifyBindError(error: Throwable): RtspBindError {
        val cause = rootCause(error)
        if (cause is SecurityException) return RtspBindError.PermissionDenied

        if (cause is BindException) {
            val message = cause.message?.lowercase().orEmpty()
            return when {
                message.contains("address already in use") || message.contains("eaddrinuse") ->
                    RtspBindError.PortInUse

                message.contains("cannot assign requested address") || message.contains("eaddrnotavail") ->
                    RtspBindError.AddressNotAvailable

                message.contains("permission denied") || message.contains("eacces") ->
                    RtspBindError.PermissionDenied

                else -> RtspBindError.Unknown(cause.message ?: cause::class.simpleName)
            }
        }

        return RtspBindError.Unknown(cause.message ?: cause::class.simpleName)
    }

    private tailrec fun rootCause(error: Throwable): Throwable {
        val cause = error.cause ?: return error
        return rootCause(cause)
    }
}
