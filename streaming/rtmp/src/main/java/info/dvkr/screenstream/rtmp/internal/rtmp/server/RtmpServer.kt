package info.dvkr.screenstream.rtmp.internal.rtmp.server

import android.system.ErrnoException
import android.system.OsConstants
import info.dvkr.screenstream.rtmp.internal.AudioParams
import info.dvkr.screenstream.rtmp.internal.Codec
import info.dvkr.screenstream.rtmp.internal.MediaFrame
import info.dvkr.screenstream.rtmp.internal.RtmpNetInterface
import info.dvkr.screenstream.rtmp.internal.RtmpServerEndpoint
import info.dvkr.screenstream.rtmp.internal.RtmpStreamingService
import info.dvkr.screenstream.rtmp.internal.VideoParams
import info.dvkr.screenstream.rtmp.internal.rtmp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.StreamMetadata
import info.dvkr.screenstream.rtmp.ui.RtmpBindError
import info.dvkr.screenstream.rtmp.ui.RtmpError
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
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

internal class RtmpServer(
    private val appVersion: String,
    private val generation: Long,
    private val onEvent: (RtmpStreamingService.InternalEvent) -> Unit,
    private val onRequestKeyFrame: () -> Unit
) {
    private var scopeJob: Job = SupervisorJob()
    private var scope: CoroutineScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var selectorManager: SelectorManager? = null
    private var serverJob: Job? = null
    private val serverSocketsLock = Any()
    private val serverSockets: MutableList<BoundSocket> = mutableListOf()

    private val rtmpServerConnections = mutableListOf<RtmpServerConnection>()
    private val videoParams = AtomicReference<VideoParams?>()
    private val audioParams = AtomicReference<AudioParams?>()
    private val metadata = AtomicReference(StreamMetadata())

    private data class BoundSocket(val socket: ServerSocket, val netInterface: RtmpNetInterface, val advertisedHost: String)
    private data class BindFailure(val key: String, val host: String, val bindError: RtmpBindError, val technicalDetails: String?)
    private data class BindResult(val boundSockets: List<BoundSocket>, val bindFailures: List<BindFailure>)

    internal fun setVideoData(videoCodec: Codec.Video, sps: ByteArray, pps: ByteArray?, vps: ByteArray?) {
        val newParams = VideoParams(videoCodec, sps, pps, vps)
        if (videoParams.get()?.contentEquals(newParams) == true) return
        this.videoParams.set(newParams)

        val snapshot = synchronized(rtmpServerConnections) { rtmpServerConnections.toList() }
        if (snapshot.isNotEmpty()) {
            onRequestKeyFrame()
            snapshot.forEach { it.onVideoParamsChanged() }
        }
    }

    internal fun setAudioData(audioParams: AudioParams?) {
        this.audioParams.set(audioParams)
    }

    internal fun setMetadata(value: StreamMetadata) {
        metadata.set(value)
    }

    internal fun clearMediaParams() {
        videoParams.set(null)
        audioParams.set(null)
    }

    internal fun getClientStatsSnapshot(): List<ClientStats> =
        synchronized(rtmpServerConnections) { rtmpServerConnections.map { it.stats.value } }

    internal fun start(
        addresses: List<RtmpNetInterface>,
        port: Int,
        path: String
    ) {
        if (serverJob?.isActive == true) stop()

        if (!scopeJob.isActive) {
            scopeJob = SupervisorJob()
            scope = CoroutineScope(scopeJob + Dispatchers.IO)
        }

        serverJob = scope.launch {
            val selectorManager = SelectorManager(coroutineContext).also { this@RtmpServer.selectorManager = it }
            var boundSockets: List<BoundSocket> = emptyList()
            var publishedSockets = false
            try {
                val bindResult = bindAll(addresses, port, selectorManager)
                boundSockets = bindResult.boundSockets

                if (bindResult.bindFailures.isNotEmpty()) {
                    onEvent(
                        RtmpStreamingService.InternalEvent.RtmpServer.OnBindFailures(
                            generation = generation,
                            failures = bindResult.bindFailures.associate { it.key to it.bindError }
                        )
                    )
                }

                if (bindResult.boundSockets.isEmpty()) {
                    val error = RtmpError.UnknownError(buildBindFailureException(addresses, port, bindResult.bindFailures))
                    onEvent(RtmpStreamingService.InternalEvent.RtmpServer.OnError(generation = generation, error = error))
                    return@launch
                }

                synchronized(serverSocketsLock) {
                    serverSockets.clear()
                    serverSockets.addAll(bindResult.boundSockets)
                }

                publishedSockets = true

                onEvent(
                    RtmpStreamingService.InternalEvent.RtmpServer.OnStart(
                        generation = generation,
                        endpoints = bindResult.boundSockets.map { boundSocket ->
                            RtmpServerEndpoint(
                                address = boundSocket.netInterface.address,
                                bindHost = boundSocket.netInterface.hostAddress,
                                rtmpUrl = boundSocket.netInterface.buildUrl(port, path)
                            )
                        }
                    )
                )

                bindResult.boundSockets.forEach { launchAcceptor(it, selectorManager, path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                onEvent(RtmpStreamingService.InternalEvent.RtmpServer.OnError(generation, RtmpError.UnknownError(e)))
            } finally {
                if (!publishedSockets) {
                    boundSockets.forEach { runCatching { it.socket.close() } }
                    runCatching { selectorManager.close() }
                    if (this@RtmpServer.selectorManager === selectorManager) this@RtmpServer.selectorManager = null
                }
            }
        }
    }

    private suspend fun bindAll(addresses: List<RtmpNetInterface>, port: Int, selectorManager: SelectorManager): BindResult {
        val boundSockets = mutableListOf<BoundSocket>()
        val bindFailures = mutableListOf<BindFailure>()

        addresses.forEach { networkInterface ->
            try {
                val bindHost = networkInterface.address.hostAddress
                    ?: throw IllegalStateException("Null hostAddress for ${networkInterface.label}")
                val serverSocket = aSocket(selectorManager).tcp().bind(bindHost, port) { reuseAddress = true }
                boundSockets += BoundSocket(serverSocket, networkInterface, bindHost.substringBefore('%'))
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

    private fun buildBindFailureException(addresses: List<RtmpNetInterface>, port: Int, bindFailures: List<BindFailure>): Throwable {
        val hosts = addresses.mapNotNull { it.address.hostAddress?.substringBefore('%') }.joinToString(", ")
        val details = bindFailures.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.host}: ${it.technicalDetails ?: it.bindError::class.simpleName}" }
            ?: "no interfaces"
        return IllegalStateException("RTMP bind failed (port=$port, hosts=$hosts): $details")
    }

    private fun CoroutineScope.launchAcceptor(
        boundSocket: BoundSocket,
        selectorManager: SelectorManager,
        path: String
    ) {
        val trimmedPath = path.trim('/')
        val expectedApp = trimmedPath.substringBeforeLast('/', trimmedPath)
        val expectedStreamName = if (trimmedPath.contains('/')) trimmedPath.substringAfterLast('/') else ""

        launch {
            while (isActive) {
                val clientSocket = try {
                    boundSocket.socket.accept()
                } catch (_: Throwable) {
                    if (!isActive) break
                    delay(50.milliseconds)
                    continue
                }

                val serverConnection = RtmpServerConnection(
                    parentJob = scopeJob,
                    tcpStreamSocket = TcpStreamSocket(scope.coroutineContext, selectorManager, clientSocket),
                    expectedApp = expectedApp,
                    expectedStreamName = expectedStreamName,
                    videoParams = videoParams,
                    audioParams = audioParams,
                    metadata = metadata,
                    onRequestKeyFrame = onRequestKeyFrame,
                    onClosed = { synchronized(rtmpServerConnections) { rtmpServerConnections.remove(it) } }
                )
                synchronized(rtmpServerConnections) { rtmpServerConnections.add(serverConnection) }
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
        val snapshot = synchronized(rtmpServerConnections) { rtmpServerConnections.toList().also { rtmpServerConnections.clear() } }
        snapshot.forEach { runCatching { it.stop() } }

        val serverSocketsSnapshot = synchronized(serverSocketsLock) { serverSockets.toList().also { serverSockets.clear() } }
        serverSocketsSnapshot.forEach { runCatching { it.socket.close() } }
        runCatching { selectorManager?.close() }
        selectorManager = null
        runCatching { serverJob?.cancelAndJoin() }
        serverJob = null
        runCatching { scopeJob.cancel() }
        onEvent(RtmpStreamingService.InternalEvent.RtmpServer.OnStop(generation))
    }

    private suspend fun disconnectAllClientsSuspend() {
        val snapshot = synchronized(rtmpServerConnections) { rtmpServerConnections.toList().also { rtmpServerConnections.clear() } }
        snapshot.forEach { runCatching { it.stop() } }
    }

    internal fun onVideoFrame(frame: MediaFrame.VideoFrame) {
        val snapshot = synchronized(rtmpServerConnections) { rtmpServerConnections.toList() }
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
        val snapshot = synchronized(rtmpServerConnections) { rtmpServerConnections.toList() }
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

    private fun classifyBindError(error: Throwable): RtmpBindError {
        val causes = buildList {
            var current: Throwable? = error
            while (current != null) {
                add(current)
                current = current.cause
            }
        }

        if (causes.any { it is SecurityException }) return RtmpBindError.PermissionDenied

        causes
            .filterIsInstance<ErrnoException>()
            .firstNotNullOfOrNull { mapErrnoToBindError(it.errno) }
            ?.let { return it }

        causes.filterIsInstance<BindException>().firstOrNull()?.let { return classifyBindErrorFromMessage(it.message) }
        causes.filterIsInstance<SocketException>().firstOrNull()?.let { return classifyBindErrorFromMessage(it.message) }

        val details = causes.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) } ?: error::class.simpleName
        return RtmpBindError.Unknown(details)
    }

    private fun mapErrnoToBindError(errno: Int): RtmpBindError? = when (errno) {
        OsConstants.EADDRINUSE -> RtmpBindError.PortInUse
        OsConstants.EADDRNOTAVAIL -> RtmpBindError.AddressNotAvailable
        OsConstants.EACCES, OsConstants.EPERM -> RtmpBindError.PermissionDenied

        else -> null
    }

    private fun classifyBindErrorFromMessage(message: String?): RtmpBindError {
        val normalized = message?.lowercase().orEmpty()
        return when {
            normalized.contains("address already in use")
                    || normalized.contains("eaddrinuse") -> RtmpBindError.PortInUse

            normalized.contains("cannot assign requested address")
                    || normalized.contains("eaddrnotavail") -> RtmpBindError.AddressNotAvailable

            normalized.contains("permission denied")
                    || normalized.contains("eacces")
                    || normalized.contains("eperm")
                    || normalized.contains("access denied") -> RtmpBindError.PermissionDenied

            else -> RtmpBindError.Unknown(message ?: "Bind failed")
        }
    }
}
