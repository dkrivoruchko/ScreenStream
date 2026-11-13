package info.dvkr.screenstream.rtmp.internal.rtmp.client

import androidx.annotation.AnyThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtmp.internal.AudioParams
import info.dvkr.screenstream.rtmp.internal.Codec
import info.dvkr.screenstream.rtmp.internal.MediaFrame
import info.dvkr.screenstream.rtmp.internal.RtmpStreamingService
import info.dvkr.screenstream.rtmp.internal.VideoParams
import info.dvkr.screenstream.rtmp.internal.rtmp.BitrateCalculator
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpConnection
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpConst
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpCommand
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpUrl
import info.dvkr.screenstream.rtmp.internal.rtmp.amf.Amf0Writer
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.FlvPacketizer
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.RtmpMediaPacket
import info.dvkr.screenstream.rtmp.internal.rtmp.flv.StreamMetadata
import info.dvkr.screenstream.rtmp.internal.rtmp.sockets.TcpStreamSocket
import info.dvkr.screenstream.rtmp.ui.RtmpError
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Duration.Companion.milliseconds

/**
 * RTMP/RTMPS publisher: connects to an external media server (MediaMTX, nginx-rtmp, YouTube, Twitch, ...) and pushes the stream.
 * Mirrors the public surface of the former RtmpClient so that the streaming service can treat both the same way.
 */
internal class RtmpClient(
    private val appVersion: String,
    private val generation: Long,
    private val rtmpUrl: RtmpUrl,
    private val onlyVideo: Boolean,
    private val onRequestKeyFrame: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onEvent: (RtmpStreamingService.InternalEvent) -> Unit
) {

    private enum class State { IDLE, CONNECTING, STREAMING }

    private sealed class QueuedItem {
        class Frame(val frame: MediaFrame, val videoGeneration: Int?) : QueuedItem()
        class NewVideoParams(val videoParams: VideoParams, val videoGeneration: Int) : QueuedItem()
    }

    private class MediaFramesBuffer(val capacity: Int = 32) {
        private var itemsChannel = newChannel()

        private fun newChannel() = Channel<QueuedItem>(capacity = capacity) { item ->
            if (item is QueuedItem.Frame) item.frame.release()
        }

        private val bufferedFrameCount = AtomicInteger(0)
        private val droppedFrames = AtomicLong(0)

        // Guarded by rtmpLock
        fun trySendFrame(frame: MediaFrame, videoGeneration: Int?): ChannelResult<Unit> =
            itemsChannel.trySend(QueuedItem.Frame(frame, videoGeneration))
                .onSuccess { bufferedFrameCount.incrementAndGet() }
                .onFailure { discardFrame(frame) }

        fun discardFrame(frame: MediaFrame) {
            XLog.w(getLog("discardFrame", "Frame discarded"))
            frame.release()
            droppedFrames.incrementAndGet()
        }

        fun trySendNewVideoParams(params: QueuedItem.NewVideoParams): ChannelResult<Unit> = itemsChannel.trySend(params)

        suspend fun receiveItemWithTimeoutOrNull(timeMillis: Long = 100): QueuedItem? {
            val item = withTimeoutOrNull(timeMillis.milliseconds) { itemsChannel.receive() } ?: return null
            if (item is QueuedItem.Frame) bufferedFrameCount.decrementAndGet()
            return item
        }

        fun hasCongestion(percentUsed: Float = 20f): Boolean =
            bufferedFrameCount.get().toFloat() >= capacity.toFloat() * (percentUsed / 100f)

        // Guarded by rtmpLock
        fun clear() {
            itemsChannel.close()
            itemsChannel = newChannel()
            bufferedFrameCount.set(0)
            droppedFrames.set(0)
        }
    }

    private val selectorManager = SelectorManager(scope.coroutineContext)
    private val mediaFramesBuffer = MediaFramesBuffer()
    private val videoParams = AtomicReference<CompletableDeferred<VideoParams>>(CompletableDeferred())
    private val audioParams = AtomicReference<AudioParams?>(null)
    private val metadata = AtomicReference(StreamMetadata())

    private val rtmpLock = Any()

    // Guarded by rtmpLock
    private var currentState = State.IDLE
        set(value) {
            field = value
            XLog.v(getLog("currentState", "State changed to: $value"))
        }

    private var connectionJob = AtomicReference<Job?>(null)
    private var currentVideoGeneration = 0
    private var acceptVideoFrames = true
    private var pendingVideoParams: QueuedItem.NewVideoParams? = null

    @Throws
    @AnyThread
    internal fun setVideoData(videoCodec: Codec.Video, sps: ByteArray, pps: ByteArray?, vps: ByteArray?) = synchronized(rtmpLock) {
        XLog.d(getLog("setVideoData", "$videoCodec"))
        val params = VideoParams(codec = videoCodec, sps = sps, pps = pps, vps = if (videoCodec == Codec.Video.H265) vps else null)
        if (currentState == State.STREAMING) {
            pendingVideoParams = QueuedItem.NewVideoParams(params, currentVideoGeneration)
            acceptVideoFrames = false
            tryEnqueuePendingVideoParams()
        } else {
            videoParams.get().complete(params)
        }
    }

    @Throws
    @AnyThread
    internal fun setAudioData(params: AudioParams?) = synchronized(rtmpLock) {
        if (params == null) {
            audioParams.set(null)
            return@synchronized
        }
        XLog.d(getLog("setAudioData", "${params.codec}"))
        if (currentState == State.STREAMING) error("Cannot change audio codec while streaming")
        if (onlyVideo) error("Cannot change audio codec in only video mode")
        audioParams.set(params)
    }

    /** Optional values for onMetaData; must be set before [connect]. */
    @AnyThread
    internal fun setMetadata(value: StreamMetadata) {
        metadata.set(value)
    }

    @AnyThread
    internal fun enqueueFrame(frame: MediaFrame) = synchronized(rtmpLock) {
        if (currentState == State.STREAMING && !tryEnqueuePendingVideoParams()) {
            mediaFramesBuffer.discardFrame(frame)
            return@synchronized
        }

        val detachedFrame = runCatching { frame.detachedCopy() }
            .onFailure { XLog.w(getLog("enqueueFrame", "Failed to detach frame. Dropping.")) }
            .getOrNull()

        frame.release()
        if (detachedFrame == null) return@synchronized

        val videoGeneration = if (detachedFrame is MediaFrame.VideoFrame) {
            if (!acceptVideoFrames) {
                detachedFrame.release()
                return@synchronized
            }
            currentVideoGeneration
        } else {
            null
        }

        if (currentState != State.STREAMING || detachedFrame is MediaFrame.AudioFrame && onlyVideo) {
            detachedFrame.release()
        } else {
            mediaFramesBuffer.trySendFrame(detachedFrame, videoGeneration)
        }
    }

    // Guarded by rtmpLock
    private fun tryEnqueuePendingVideoParams(): Boolean {
        val pending = pendingVideoParams ?: return true
        if (mediaFramesBuffer.trySendNewVideoParams(pending).isFailure) return false
        pendingVideoParams = null
        acceptVideoFrames = true
        return true
    }

    @AnyThread
    internal fun beginVideoReconfigure() {
        synchronized(rtmpLock) {
            if (currentState != State.STREAMING) return
            currentVideoGeneration++
            acceptVideoFrames = false
            pendingVideoParams = null
        }
    }

    internal fun connect() {
        scope.launch {
            var tcpSocket: TcpStreamSocket? = null
            var connection: RtmpConnection? = null
            var messageStreamId = 0
            var error: Throwable? = null
            try {
                synchronized(rtmpLock) {
                    if (currentState != State.IDLE) error("Cannot connect while streaming")
                    currentState = State.CONNECTING
                    currentVideoGeneration = 0
                    acceptVideoFrames = true
                    pendingVideoParams = null
                }

                val videoParams = withTimeoutOrNull(5_000.milliseconds) { this@RtmpClient.videoParams.get().await() }
                if (videoParams?.isOk != true) error("SPS/PPS/VPS not set or incomplete for video codec.")
                val audioParams = if (onlyVideo) null else this@RtmpClient.audioParams.get()

                val tcp = TcpStreamSocket(Dispatchers.Default, selectorManager, rtmpUrl.host, rtmpUrl.port, rtmpUrl.tlsEnabled)
                    .apply {
                        tcpSocket = this
                        withWriteLock { connect() }
                    }
                val rtmp = RtmpConnection(tcp).also { connection = it }

                val packetizer = FlvPacketizer(videoParams, audioParams, metadata.get())

                messageStreamId = withTimeout(HANDSHAKE_TIMEOUT_MS.milliseconds) { publishHandshake(rtmp, packetizer, videoParams.codec == Codec.Video.H265) }

                synchronized(rtmpLock) {
                    currentState = State.STREAMING
                    pendingVideoParams = null
                    mediaFramesBuffer.clear()
                }
                onEvent(RtmpStreamingService.InternalEvent.RtmpClient.OnConnectionSuccess(generation))
                onRequestKeyFrame()

                coroutineScope {
                    launch { sendingLoop(rtmp, messageStreamId, packetizer) }
                    launch { readingLoop(rtmp) }
                }
            } catch (e: Throwable) {
                val mappedMessage = when (e) {
                    is UnresolvedAddressException -> "Invalid address"
                    is TimeoutCancellationException, is SocketTimeoutException -> "Timeout"
                    is SSLHandshakeException, is CertificateException -> "TLS error"
                    is IOException -> e.message ?: "Connection failed"
                    else -> null
                }
                when {
                    e is CancellationException && e !is TimeoutCancellationException -> Unit

                    e is RtmpError.ClientError -> {
                        XLog.w(getLog("connect", "Connection error: ${e.message}"), e)
                        onEvent(RtmpStreamingService.InternalEvent.RtmpClient.OnError(generation, e))
                        error = e
                    }

                    mappedMessage != null -> {
                        XLog.w(getLog("connect", "Connection error: $mappedMessage"), e)
                        onEvent(RtmpStreamingService.InternalEvent.RtmpClient.OnError(generation, RtmpError.ClientError.Failed(mappedMessage)))
                        error = e
                    }

                    else -> {
                        XLog.w(getLog("connect", "Unknown error: ${e.message}"), e)
                        onEvent(RtmpStreamingService.InternalEvent.Error(RtmpError.UnknownError(e)))
                        error = e
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    val socket = tcpSocket
                    val rtmp = connection
                    if (socket != null && rtmp != null && socket.isConnected() && messageStreamId != 0) {
                        runCatching {
                            withTimeout(2_000.milliseconds) {
                                rtmp.writeMessage(
                                    RtmpConst.CSID_COMMAND, RtmpConst.MSG_COMMAND_AMF0, messageStreamId, 0,
                                    Amf0Writer().string("deleteStream").number(0).nullValue().number(messageStreamId).toByteArray()
                                )
                            }
                        }
                    }
                    socket?.close()
                }

                videoParams.getAndSet(CompletableDeferred()).cancel()
                audioParams.set(null)
                synchronized(rtmpLock) {
                    currentState = State.IDLE
                    pendingVideoParams = null
                }

                if (error == null) onEvent(RtmpStreamingService.InternalEvent.RtmpClient.OnDisconnect(generation))
            }
        }.also {
            connectionJob.set(it)
        }
    }

    internal fun destroy() = runBlocking {
        XLog.d(this@RtmpClient.getLog("destroy"))
        connectionJob.getAndSet(null)?.cancelAndJoin()
        runCatching { selectorManager.close() }
        scope.cancel()
        XLog.d(this@RtmpClient.getLog("destroy", "Done"))
    }

    // ------------------------------------------------------------------------------------------ RTMP session setup

    /** handshake → connect → createStream → publish → metadata + sequence headers. Returns the message stream id. */
    private suspend fun publishHandshake(rtmp: RtmpConnection, packetizer: FlvPacketizer, isHevc: Boolean): Int {
        rtmp.clientHandshake()
        rtmp.setOutChunkSize(RtmpConst.OUT_CHUNK_SIZE)

        // connect
        val connectObject = linkedMapOf<String, Any?>(
            "app" to rtmpUrl.app,
            "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (compatible; ScreenStream $appVersion)",
            "tcUrl" to rtmpUrl.tcUrl,
            "fpad" to false,
            "capabilities" to 15,
            "audioCodecs" to 3191,
            "videoCodecs" to 252,
            "videoFunction" to 1,
        )
        if (isHevc) connectObject["fourCcList"] = listOf("hvc1")
        sendCommand(rtmp, 0, Amf0Writer().string("connect").number(1).obj(connectObject).toByteArray())
        val connectResult = awaitResult(rtmp, 1)
        val connectInfo = connectResult.getOrNull(1) as? Map<*, *>
        val connectCode = connectInfo?.get("code") as? String
        if (connectCode != null && connectCode != "NetConnection.Connect.Success") {
            throw RtmpError.ClientError.Failed(connectCode)
        }

        // release/FCPublish are optional hints used by Flash Media Server compatible servers; replies are not awaited
        sendCommand(rtmp, 0, Amf0Writer().string("releaseStream").number(2).nullValue().string(rtmpUrl.streamName).toByteArray())
        sendCommand(rtmp, 0, Amf0Writer().string("FCPublish").number(3).nullValue().string(rtmpUrl.streamName).toByteArray())

        // createStream
        sendCommand(rtmp, 0, Amf0Writer().string("createStream").number(4).nullValue().toByteArray())
        val streamId = (awaitResult(rtmp, 4).getOrNull(1) as? Double)?.toInt() ?: throw RtmpError.ClientError.Failed("createStream failed")

        // publish
        sendCommand(
            rtmp, streamId,
            Amf0Writer().string("publish").number(0).nullValue().string(rtmpUrl.streamName).string("live").toByteArray()
        )
        awaitPublishStart(rtmp)

        // metadata + codec configuration
        rtmp.writeMessage(RtmpConst.CSID_DATA, RtmpConst.MSG_DATA_AMF0, streamId, 0, packetizer.metadataPayload(forPublish = true))
        rtmp.writeMessage(RtmpConst.CSID_VIDEO, RtmpConst.MSG_VIDEO, streamId, 0, packetizer.videoSequenceHeader())
        packetizer.audioSequenceHeader()?.let { rtmp.writeMessage(RtmpConst.CSID_AUDIO, RtmpConst.MSG_AUDIO, streamId, 0, it) }
        return streamId
    }

    private suspend fun sendCommand(rtmp: RtmpConnection, streamId: Int, payload: ByteArray) =
        rtmp.writeMessage(RtmpConst.CSID_COMMAND, RtmpConst.MSG_COMMAND_AMF0, streamId, 0, payload)

    /** Waits for `_result` of [transactionId]; returns its arguments after name and transaction id (0 = command object or null, 1 = info object or stream id). */
    private suspend fun awaitResult(rtmp: RtmpConnection, transactionId: Int): List<Any> {
        while (true) {
            val command = RtmpCommand.from(rtmp.readMessage()) ?: continue
            if (command.transactionId.toInt() != transactionId) continue
            when (command.name) {
                "_result" -> return command.args
                "_error" -> {
                    val info = command.args.getOrNull(1) as? Map<*, *>
                    throw RtmpError.ClientError.Failed((info?.get("code") as? String) ?: "Server returned error")
                }
            }
        }
    }

    private suspend fun awaitPublishStart(rtmp: RtmpConnection) {
        while (true) {
            val command = RtmpCommand.from(rtmp.readMessage()) ?: continue
            if (command.name == "_error") throw RtmpError.ClientError.Failed("Publish rejected")
            if (command.name != "onStatus") continue
            val code = command.statusCode() ?: continue
            when {
                code == "NetStream.Publish.Start" -> return
                code == "NetStream.Publish.BadName" || code == "NetStream.Publish.Rejected" || code.contains("Unauthorized") ->
                    throw RtmpError.ClientError.AccessDenied()

                code.startsWith("NetStream.Publish.") && code != "NetStream.Publish.Idle" -> throw RtmpError.ClientError.Failed(code)
            }
        }
    }

    // ------------------------------------------------------------------------------------------ streaming

    /** Drains server messages (ack, ping, onStatus); fails the session when the server stops us or closes the connection. */
    private suspend fun readingLoop(rtmp: RtmpConnection) {
        while (currentCoroutineContext().isActive) {
            val command = RtmpCommand.from(rtmp.readMessage()) ?: continue
            if (command.name != "onStatus") continue
            val code = command.statusCode() ?: continue
            if (code == "NetStream.Unpublish.Success" || code == "NetConnection.Connect.Closed" ||
                (code.startsWith("NetStream.Publish.") && code != "NetStream.Publish.Start")
            ) {
                throw RtmpError.ClientError.Failed(code)
            }
        }
    }

    private suspend fun sendingLoop(rtmp: RtmpConnection, streamId: Int, packetizer: FlvPacketizer) {
        var activeVideoGeneration = 0
        var waitingForKeyFrame = true
        val bitrateCalculator = BitrateCalculator(scope) { bitrate ->
            onEvent(RtmpStreamingService.InternalEvent.RtmpClient.OnBitrate(generation, bitrate))
        }

        try {
            bitrateCalculator.start()
            while (currentCoroutineContext().isActive) {
                val item = mediaFramesBuffer.receiveItemWithTimeoutOrNull() ?: continue
                try {
                    when (item) {
                        is QueuedItem.Frame -> {
                            val frame = item.frame
                            val packet: RtmpMediaPacket? = when (frame) {
                                is MediaFrame.VideoFrame -> {
                                    if (item.videoGeneration != activeVideoGeneration) continue
                                    if (waitingForKeyFrame && !frame.info.isKeyFrame) continue
                                    // If queue is congested, drop non-key video frames to reduce latency
                                    if (mediaFramesBuffer.hasCongestion(75f) && !frame.info.isKeyFrame) continue
                                    val (bytes, offset) = frame.arrayAndOffset()
                                    packetizer.packVideo(bytes, offset, frame.info.size, frame.info.timestamp, frame.info.isKeyFrame)
                                        ?.also { if (frame.info.isKeyFrame) waitingForKeyFrame = false }
                                }

                                is MediaFrame.AudioFrame -> {
                                    val (bytes, offset) = frame.arrayAndOffset()
                                    packetizer.packAudio(bytes, offset, frame.info.size, frame.info.timestamp)
                                }
                            }

                            if (packet != null) {
                                val csid = if (packet.typeId == RtmpConst.MSG_VIDEO) RtmpConst.CSID_VIDEO else RtmpConst.CSID_AUDIO
                                rtmp.writeMessage(csid, packet.typeId, streamId, packet.timestampMs, packet.payload)
                                bitrateCalculator.addBytes(packet.payload.size)
                            }
                        }

                        is QueuedItem.NewVideoParams -> {
                            XLog.w(getLog("sendingLoop", "Applying new video parameters"))
                            activeVideoGeneration = item.videoGeneration
                            packetizer.updateVideo(item.videoParams)
                            rtmp.writeMessage(RtmpConst.CSID_VIDEO, RtmpConst.MSG_VIDEO, streamId, 0, packetizer.videoSequenceHeader())
                            waitingForKeyFrame = true
                            onRequestKeyFrame()
                        }
                    }
                } catch (_: CancellationException) {
                } catch (error: IOException) {
                    if (!currentCoroutineContext().isActive) return
                    throw error
                } catch (error: Throwable) {
                    if (!currentCoroutineContext().isActive) return
                    XLog.w(getLog("sendingLoop", "Dropping frame: ${error.message}"), error)
                } finally {
                    if (item is QueuedItem.Frame) item.frame.release()
                }
            }
        } finally {
            withContext(NonCancellable) {
                bitrateCalculator.stop()
                synchronized(rtmpLock) { mediaFramesBuffer.clear() }
            }
        }
    }

    /** Frames reaching the sender are detached copies backed by a plain byte array. */
    private fun MediaFrame.arrayAndOffset(): Pair<ByteArray, Int> {
        val buffer = data
        return if (buffer.hasArray()) buffer.array() to (buffer.arrayOffset() + info.offset)
        else ByteArray(info.size).also { dst ->
            buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(dst)
        } to 0
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
    }
}
