package info.dvkr.screenstream.rtmp.internal.rtmp.server

import info.dvkr.screenstream.rtmp.internal.AudioParams
import info.dvkr.screenstream.rtmp.internal.VideoParams
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpCommand
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpConnection
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpConst
import info.dvkr.screenstream.rtmp.internal.rtmp.amf.Amf0Writer
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.FlvPacketizer
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.StreamMetadata
import info.dvkr.screenstream.rtmp.internal.rtmp.sockets.TcpStreamSocket
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One RTMP viewer (VLC, ffplay, mpv, OBS media source, ...) connected to the on-device RTMP server.
 * Flow: handshake → connect → createStream → play → sequence headers → live audio/video messages.
 * Publishing to the device is not supported.
 */
internal class RtmpServerConnection(
    parentJob: Job,
    private val tcpStreamSocket: TcpStreamSocket,
    private val expectedApp: String,
    private val expectedStreamName: String,
    private val videoParams: AtomicReference<VideoParams?>,
    private val audioParams: AtomicReference<AudioParams?>,
    private val metadata: AtomicReference<StreamMetadata>,
    private val onRequestKeyFrame: () -> Unit,
    private val onClosed: (RtmpServerConnection) -> Unit,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob(parentJob) + Dispatchers.IO)
    private val rtmp = RtmpConnection(tcpStreamSocket)
    private val sessionId: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile
    internal var isStreaming: Boolean = false

    @Volatile
    private var closed = false

    @Volatile
    private var videoParamsChanged = false

    private var clientJob: Job? = null
    private var videoWriterJob: Job? = null
    private var audioWriterJob: Job? = null
    private var packetizer: FlvPacketizer? = null
    private var playStreamId = 1

    private val videoQueue = Channel<VideoBlob>(capacity = 32) { it.buf.releaseOne() }
    private val audioQueue = Channel<AudioBlob>(capacity = 64) { it.buf.releaseOne() }
    private val videoQueueSize = AtomicInteger(0)
    private val audioQueueSize = AtomicInteger(0)

    private val statsReporter = ClientStatsReporter(
        sessionId = sessionId,
        remoteHost = tcpStreamSocket.remoteHost,
        queueCapVideo = 32,
        queueCapAudio = 64
    )
    internal val stats: StateFlow<ClientStats> = statsReporter.stats

    private fun updateQueueStats() = statsReporter.setQueueSizes(videoQueueSize.get(), audioQueueSize.get())

    internal fun enqueueVideo(blob: VideoBlob): Boolean {
        if (!isStreaming) return false
        if (!videoQueue.trySend(blob).isSuccess) {
            if (!blob.isKeyFrame) {
                statsReporter.onVideoDrop()
                return false
            }
            // Slow viewer: drop the backlog and resume from this key frame
            while (true) {
                val drained = videoQueue.tryReceive().getOrNull() ?: break
                drained.buf.releaseOne()
                videoQueueSize.decrementAndGet()
            }
            if (!videoQueue.trySend(blob).isSuccess) {
                statsReporter.onVideoDrop()
                return false
            }
        }
        videoQueueSize.incrementAndGet()
        updateQueueStats()
        statsReporter.onVideoEnqueue()
        return true
    }

    internal fun enqueueAudio(blob: AudioBlob): Boolean {
        if (!isStreaming || packetizer?.hasAudio != true) return false
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

    /** Called by the server when SPS/PPS/VPS changed (e.g. resolution change): resend configuration and wait for a key frame. */
    internal fun onVideoParamsChanged() {
        videoParamsChanged = true
    }

    internal fun start() {
        clientJob = scope.launch {
            try {
                withTimeoutHandshake()
                commandLoop()
            } catch (_: Throwable) {
            } finally {
                stop()
            }
        }
    }

    private suspend fun withTimeoutHandshake() {
        kotlinx.coroutines.withTimeout(10_000) { rtmp.serverHandshake() }
    }

    // ------------------------------------------------------------------------------------------ commands

    private suspend fun commandLoop() {
        while (scope.isActive && tcpStreamSocket.isConnected()) {
            val message = rtmp.readMessage()
            val command = RtmpCommand.from(message) ?: continue
            val txn = command.transactionId

            when (command.name) {
                "connect" -> {
                    val app = ((command.args.getOrNull(0) as? Map<*, *>)?.get("app") as? String).orEmpty().trim('/').substringBefore('?')
                    if (app != expectedApp) {
                        sendStatus(0, "error", "NetConnection.Connect.Rejected", "Application not found")
                        return
                    }
                    rtmp.sendWindowAckSize(WINDOW_SIZE)
                    rtmp.sendSetPeerBandwidth(WINDOW_SIZE)
                    rtmp.sendUserControl(RtmpConst.UC_STREAM_BEGIN, 0)
                    rtmp.setOutChunkSize(RtmpConst.OUT_CHUNK_SIZE)
                    send(
                        0,
                        Amf0Writer().string("_result").number(txn)
                            .obj(linkedMapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31))
                            .obj(
                                linkedMapOf(
                                    "level" to "status", "code" to "NetConnection.Connect.Success",
                                    "description" to "Connection succeeded.", "objectEncoding" to 0
                                )
                            ).toByteArray()
                    )
                }

                "createStream" -> send(0, Amf0Writer().string("_result").number(txn).nullValue().number(playStreamId).toByteArray())

                "play" -> {
                    val requested = (command.args.getOrNull(1) as? String).orEmpty().substringBefore('?').trim('/')
                    if (expectedStreamName.isNotEmpty() && requested != expectedStreamName) {
                        sendStatus(message.streamId, "error", "NetStream.Play.StreamNotFound", "Stream not found")
                        return
                    }
                    if (!startPlaying(message.streamId)) return
                }

                "publish" -> {
                    sendStatus(message.streamId, "error", "NetStream.Publish.BadName", "Publishing to this device is not supported")
                    return
                }

                "deleteStream", "closeStream" -> return

                "releaseStream", "FCPublish", "FCUnpublish", "getStreamLength", "_checkbw", "checkBandwidth" ->
                    if (txn > 0) send(0, Amf0Writer().string("_result").number(txn).nullValue().nullValue().toByteArray())
            }
        }
    }

    private suspend fun startPlaying(streamId: Int): Boolean {
        val video = videoParams.get()
        if (video == null || !video.isOk) {
            sendStatus(streamId, "error", "NetStream.Play.StreamNotFound", "Stream is not ready")
            return false
        }
        val audio = audioParams.get()
        val flv = FlvPacketizer(video, audio, metadata.get())
        packetizer = flv
        playStreamId = streamId

        rtmp.sendUserControl(RtmpConst.UC_STREAM_BEGIN, streamId)
        sendStatus(streamId, "status", "NetStream.Play.Reset", "Playing and resetting stream.")
        sendStatus(streamId, "status", "NetStream.Play.Start", "Started playing stream.")
        rtmp.writeMessage(RtmpConst.CSID_DATA, RtmpConst.MSG_DATA_AMF0, streamId, 0, flv.metadataPayload(forPublish = false))
        rtmp.writeMessage(RtmpConst.CSID_VIDEO, RtmpConst.MSG_VIDEO, streamId, 0, flv.videoSequenceHeader())
        flv.audioSequenceHeader()?.let { rtmp.writeMessage(RtmpConst.CSID_AUDIO, RtmpConst.MSG_AUDIO, streamId, 0, it) }

        videoWriterJob = scope.launch { videoWriterLoop(flv, streamId) }
        audioWriterJob = scope.launch { audioWriterLoop(flv, streamId) }
        isStreaming = true
        onRequestKeyFrame()
        return true
    }

    private suspend fun send(streamId: Int, payload: ByteArray) =
        rtmp.writeMessage(RtmpConst.CSID_COMMAND, RtmpConst.MSG_COMMAND_AMF0, streamId, 0, payload)

    private suspend fun sendStatus(streamId: Int, level: String, code: String, description: String) = send(
        streamId,
        Amf0Writer().string("onStatus").number(0).nullValue()
            .obj(linkedMapOf("level" to level, "code" to code, "description" to description)).toByteArray()
    )

    // ------------------------------------------------------------------------------------------ writers

    private suspend fun videoWriterLoop(flv: FlvPacketizer, streamId: Int) {
        var waitingForKeyFrame = true
        try {
            for (blob in videoQueue) {
                videoQueueSize.decrementAndGet()
                try {
                    if (videoParamsChanged) {
                        videoParamsChanged = false
                        val params = videoParams.get()
                        if (params != null && params.isOk) {
                            flv.updateVideo(params)
                            rtmp.writeMessage(RtmpConst.CSID_VIDEO, RtmpConst.MSG_VIDEO, streamId, 0, flv.videoSequenceHeader())
                            waitingForKeyFrame = true
                            onRequestKeyFrame()
                        }
                    }
                    if (waitingForKeyFrame && !blob.isKeyFrame) continue

                    val packet = flv.packVideo(blob.buf.bytes, 0, blob.length, blob.timestampUs, blob.isKeyFrame) ?: continue
                    waitingForKeyFrame = false
                    rtmp.writeMessage(RtmpConst.CSID_VIDEO, packet.typeId, streamId, packet.timestampMs, packet.payload)
                    statsReporter.onVideoSent(1, packet.payload.size)
                } finally {
                    blob.buf.releaseOne()
                    updateQueueStats()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            XLog.d(getLog("videoWriterLoop", "Stopped: ${e.message}"))
            stop()
        }
    }

    private suspend fun audioWriterLoop(flv: FlvPacketizer, streamId: Int) {
        try {
            for (blob in audioQueue) {
                audioQueueSize.decrementAndGet()
                try {
                    val packet = flv.packAudio(blob.buf.bytes, 0, blob.length, blob.timestampUs) ?: continue
                    rtmp.writeMessage(RtmpConst.CSID_AUDIO, packet.typeId, streamId, packet.timestampMs, packet.payload)
                    statsReporter.onAudioSent(1, packet.payload.size)
                } finally {
                    blob.buf.releaseOne()
                    updateQueueStats()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            XLog.d(getLog("audioWriterLoop", "Stopped: ${e.message}"))
            stop()
        }
    }

    // ------------------------------------------------------------------------------------------ shutdown

    internal fun stop() {
        if (closed) return
        closed = true
        isStreaming = false
        CoroutineScope(Dispatchers.IO).launch {   // independent scope: must outlive this connection's own scope
            runCatching { videoQueue.close() }
            runCatching { audioQueue.close() }
            while (true) { (videoQueue.tryReceive().getOrNull() ?: break).buf.releaseOne() }
            while (true) { (audioQueue.tryReceive().getOrNull() ?: break).buf.releaseOne() }
            withContext(NonCancellable) { runCatching { tcpStreamSocket.close() } }
            onClosed(this@RtmpServerConnection)
            scope.cancel()
        }
    }

    private companion object {
        const val WINDOW_SIZE = 2_500_000
    }
}
