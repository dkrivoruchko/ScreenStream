package info.dvkr.screenstream.rtsp.internal.rtsp.server

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.CommandsManager
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspClient
import info.dvkr.screenstream.rtsp.internal.rtsp.sockets.TcpStreamSocket
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal class RtspServer(
    private val appVersion: String,
    private val onEvent: (RtspStreamingService.InternalEvent) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selectorManager: SelectorManager? = null
    private val serverSockets: MutableList<Pair<ServerSocket, String>> = mutableListOf()
    private var serverJob: Job? = null
    private val rtspServerConnections = mutableListOf<RtspServerConnection>()
    private val videoParams = AtomicReference<RtspClient.VideoParams?>()
    private val audioParams = AtomicReference<RtspClient.AudioParams?>()
    private var requiredProtocol: info.dvkr.screenstream.rtsp.internal.Protocol = info.dvkr.screenstream.rtsp.internal.Protocol.TCP
    private var serverPath: String = "/screen"
    private var serverPort: Int = 8554

    // Debug counters
    private var debugVideoFrames: Long = 0
    private var debugAudioFrames: Long = 0

    fun setVideoData(videoParams: RtspClient.VideoParams) {
        this.videoParams.set(videoParams)
    }

    fun setAudioData(audioParams: RtspClient.AudioParams) {
        this.audioParams.set(audioParams)
    }

    fun start(addresses: List<RtspNetInterface>, port: Int, path: String, protocol: info.dvkr.screenstream.rtsp.internal.Protocol) {
        XLog.d(getLog("start", "addresses=${addresses.size}, port=$port, path=$path, protocol=$protocol"))
        if (serverJob?.isActive == true) stop()

        requiredProtocol = protocol
        serverPort = port
        serverPath = "/" + path.trimStart('/')

        serverJob = scope.launch {
            val sm = SelectorManager(Dispatchers.IO)
            selectorManager = sm
            try {
                serverSockets.clear()

                addresses.forEach { netIf ->
                    val host = netIf.address.hostAddress!!.substringBefore('%')
                    runCatching {
                        val ss = aSocket(sm).tcp().bind(host, serverPort)
                        serverSockets.add(ss to host)
                        XLog.i(getLog("start.bind", "$host:$serverPort"))
                    }.onFailure {
                        XLog.w(getLog("start.bind", "Failed $host:$serverPort -> ${it.message}"), it)
                    }
                }

                if (serverSockets.isEmpty()) {
                    XLog.w(getLog("start", "No sockets bound"))
                    return@launch
                }

                onEvent(RtspStreamingService.InternalEvent.RtspServerOnStart)

                serverSockets.forEach { (ss, boundHost) ->
                    launch {
                        while (isActive) {
                            val clientSocket = runCatching { ss.accept() }.getOrElse { break }
                            XLog.i(getLog("accept", "Client connected: ${clientSocket.remoteAddress} on $boundHost:$serverPort"))
                            val tcpStreamSocket = TcpStreamSocket(scope.coroutineContext, sm, clientSocket)
                            val commandsManager = CommandsManager(
                                appVersion, CommandsManager.Mode.SERVER, boundHost, serverPort, serverPath, null, null
                            )
                            val conn = RtspServerConnection(
                                tcpStreamSocket, commandsManager, videoParams, audioParams, ::onClientEvent, requiredProtocol
                            )
                            synchronized(rtspServerConnections) { rtspServerConnections.add(conn) }
                            conn.start()
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.w(getLog("start", "Failed: ${t.message}"), t)
            }
        }
    }

    internal fun stop() {
        XLog.d(getLog("stop"))
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) { stopSuspend() }
        }
        XLog.d(getLog("stop", "Done"))
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
        onEvent(RtspStreamingService.InternalEvent.RtspServerOnStop)
    }

    internal fun onVideoFrame(frame: MediaFrame.VideoFrame) {
        val buffer = frame.data
        val size = frame.info.size
        val bytes = ByteArrayPool.get(size)
        buffer.limit(frame.info.offset + size)
        buffer.position(frame.info.offset)
        buffer.get(bytes, 0, size)
        frame.release()

        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList() }
        if (snapshot.isEmpty()) {
            ByteArrayPool.recycle(bytes); return
        }
        val shared = SharedBuffer(bytes).also { it.addRef(1) } // base ref while distributing
        var accepted = 0
        val blob = VideoBlob(shared, size, frame.info.timestamp, frame.info.isKeyFrame)
        snapshot.forEach { conn ->
            // Reserve a ref before offering to avoid race where writer releases before we account it
            shared.addRef(1)
            val ok = conn.enqueueVideo(blob)
            if (ok) {
                accepted++
            } else {
                // Compensate reservation on failure
                shared.release()
            }
        }
        // Drop the base ref; remaining refs equal to number of enqueued consumers
        shared.release()

        debugVideoFrames++
        if (debugVideoFrames <= 8L || accepted == 0) {
            XLog.d(getLog("onVideoFrame", "size=$size, key=${frame.info.isKeyFrame}, accepted=$accepted/${snapshot.size}"))
        }
    }

    internal fun onAudioFrame(frame: MediaFrame.AudioFrame) {
        val buffer = frame.data
        val size = frame.info.size
        val bytes = ByteArrayPool.get(size)
        buffer.limit(frame.info.offset + size)
        buffer.position(frame.info.offset)
        buffer.get(bytes, 0, size)
        frame.release()

        val snapshot = synchronized(rtspServerConnections) { rtspServerConnections.toList() }
        if (snapshot.isEmpty()) {
            ByteArrayPool.recycle(bytes); return
        }
        val shared = SharedBuffer(bytes).also { it.addRef(1) }
        var accepted = 0
        val blob = AudioBlob(shared, size, frame.info.timestamp)
        snapshot.forEach { conn ->
            shared.addRef(1)
            val ok = conn.enqueueAudio(blob)
            if (ok) accepted++ else shared.release()
        }
        shared.release()

        debugAudioFrames++
        if (debugAudioFrames <= 8L || accepted == 0) {
            XLog.d(getLog("onAudioFrame", "size=$size, accepted=$accepted/${snapshot.size}"))
        }
    }

    private fun onClientEvent(event: RtspStreamingService.InternalEvent) {
        if (event is RtspStreamingService.InternalEvent.RtspServerOnClientDisconnected) {
            synchronized(rtspServerConnections) {
                rtspServerConnections.remove(event.rtspServerConnection)
            }
        }
        onEvent(event)
    }
}
