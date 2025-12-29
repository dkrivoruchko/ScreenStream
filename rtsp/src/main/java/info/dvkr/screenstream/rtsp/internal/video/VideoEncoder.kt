package info.dvkr.screenstream.rtsp.internal.video

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.Av1Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H264Packet
import info.dvkr.screenstream.rtsp.internal.rtsp.packets.H265Packet
import info.dvkr.screenstream.rtsp.internal.stripAnnexBStartCode
import java.nio.ByteBuffer

internal class VideoEncoder(
    private val codecInfo: VideoCodecInfo,
    private val onVideoInfo: (sps: ByteArray, pps: ByteArray?, vps: ByteArray?) -> Unit,
    private val onVideoFrame: (MediaFrame.VideoFrame) -> Unit,
    private val onFps: (Int) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private enum class State { IDLE, PREPARED, RUNNING, STOPPED }

    private val fpsCalculator = FpsCalculator { framesPerSecond ->
        onFps.invoke(framesPerSecond)
    }

    private val encoderLock = Any()
    private var currentState = State.IDLE
        set(value) {
            field = value
            XLog.v(getLog("currentState", "State changed to: $value"))
        }

    private var videoEncoder: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglRenderer: EglRenderer? = null
    private var isCodecConfigSent: Boolean = false
    private var lastBitrate: Int? = null
    private val adjustedBufferInfo = MediaCodec.BufferInfo()

    internal var width: Int = 0
        private set

    internal var height: Int = 0
        private set

    internal val inputSurfaceTexture: SurfaceTexture?
        get() = eglRenderer?.inputSurfaceTexture

    internal fun prepare(width: Int, height: Int, fps: Int, bitRate: Int) {
        runCatching {
            synchronized(encoderLock) {
                require(width % 2 == 0 && height % 2 == 0) { "Width and height must be even. Received: $width x $height" }
                require(fps > 0) { "FPS must be > 0. Received: $fps" }
                check(currentState == State.IDLE)

                this.width = width
                this.height = height

                // H.265, H.264, AV1
                val format = MediaFormat.createVideoFormat(codecInfo.codec.mimeType, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
                    setInteger(MediaFormat.KEY_PRIORITY, 1)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }

                    if (codecInfo.isCBRModeSupported) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    } else {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }

                val encoder = MediaCodec.createByCodecName(codecInfo.name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                eglRenderer = EglRenderer(width, height, encoder.createInputSurface(), onError).apply {
                    setFps(fps)
                }

                handlerThread = HandlerThread("VideoEncoderHandler", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
                handler = Handler(handlerThread!!.looper)
                encoder.setCallback(createCodecCallback(), handler)

                videoEncoder = encoder
                lastBitrate = bitRate
                currentState = State.PREPARED
            }
        }.onFailure { cause ->
            cleanupAfterPrepareFailure()
            onError(cause)
        }
    }

    private fun cleanupAfterPrepareFailure(): Unit = synchronized(encoderLock) {
        stopInternal(force = true, logTag = "prepareCleanup")
    }

    internal fun start(): Unit = synchronized(encoderLock) {
        XLog.v(getLog("start"))
        if (currentState != State.PREPARED) {
            XLog.w(getLog("start", "Cannot start unless state is PREPARED. Current: $currentState"))
            return
        }
        isCodecConfigSent = false
        eglRenderer?.startAsync()
        videoEncoder?.start()
        currentState = State.RUNNING

        XLog.v(getLog("start", "Done"))
    }

    internal fun stop(): Unit = synchronized(encoderLock) {
        XLog.v(getLog("stop"))
        if (stopInternal(force = false, logTag = "stopInternal").not()) return
        XLog.v(getLog("stop", "Done"))
    }

    private fun stopInternal(force: Boolean, logTag: String): Boolean {
        if (!force && (currentState == State.IDLE || currentState == State.STOPPED)) {
            XLog.w(getLog("stop", "Cannot stop unless state is RUNNING. Current: $currentState"))
            return false
        }

        currentState = State.STOPPED
        isCodecConfigSent = false
        lastBitrate = null

        eglRenderer?.stop()
        eglRenderer = null

        videoEncoder?.apply {
            runCatching {
                stop()
                release()
            }.onFailure {
                XLog.w(this@VideoEncoder.getLog(logTag, "mediaCodec.stop() exception: ${it.message}"), it)
            }
        }
        videoEncoder = null

        handler?.removeCallbacksAndMessages(null)
        handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                quit()
                XLog.w(this@VideoEncoder.getLog(logTag, "handlerThread.join() took too long, forcing a shutdown"))
            }
        }
        handlerThread = null
        handler = null

        currentState = State.IDLE
        return true
    }

    internal fun setBitrate(newBitrate: Int): Unit = synchronized(encoderLock) {
        if (currentState != State.RUNNING) {
            XLog.w(getLog("setBitrate", "Ignoring setBitrate($newBitrate); not in RUNNING state."))
            return
        }
        if (newBitrate <= 0) {
            XLog.w(getLog("setBitrate", "Ignoring non-positive bitrate: $newBitrate"))
            return
        }
        val bitrateRange = codecInfo.capabilities.videoCapabilities?.bitrateRange
        val bitrate = bitrateRange?.let { newBitrate.coerceIn(it.lower, it.upper) } ?: newBitrate
        if (bitrate != newBitrate) {
            XLog.w(getLog("setBitrate", "Clamped bitrate $newBitrate -> $bitrate"))
        }
        if (lastBitrate == bitrate) return
        runCatching {
            videoEncoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
        }.onSuccess {
            lastBitrate = bitrate
        }.onFailure {
            XLog.w(getLog("setBitrate", "Error while updating bitrate; codec in invalid state."), it)
        }
    }

    internal fun requestKeyFrame(): Unit = synchronized(encoderLock) {
        if (currentState != State.RUNNING) {
            XLog.w(getLog("requestKeyFrame", "Ignoring; encoder not RUNNING (state=$currentState)"))
            return
        }
        runCatching {
            val bundle = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            videoEncoder?.setParameters(bundle)
            XLog.v(getLog("requestKeyFrame", "Sync frame requested"))
        }.onFailure {
            XLog.w(getLog("requestKeyFrame", "Failed: ${it.message}"), it)
        }
    }

    private fun createCodecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // No-op: We use a Surface input (EGL). No direct input buffers needed.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val videoFrame = synchronized(encoderLock) {
                    if (currentState != State.RUNNING || info.size == 0) {
                        releaseOutputBufferSafely(codec, index)
                        return
                    }

                    val outputBuffer = codec.getOutputBuffer(index) ?: run {
                        releaseOutputBufferSafely(codec, index)
                        return
                    }

                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val adjustedInfo = adjustedBufferInfo.apply {
                        val forcedPtsUs = MasterClock.relativeTimeUs()
                        set(info.offset, info.size, forcedPtsUs, info.flags)
                    }

                    val flags = adjustedInfo.flags
                    if (flags.hasFlag(MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        if (!isCodecConfigSent) {
                            outputBuffer.duplicate().extractCodecConfig(adjustedInfo)?.let { (sps, pps, vps) ->
                                onVideoInfo(sps.stripAnnexBStartCode(), pps?.stripAnnexBStartCode(), vps?.stripAnnexBStartCode())
                                isCodecConfigSent = true
                            }
                        }
                        releaseOutputBufferSafely(codec, index)
                        return
                    }

                    if (flags.hasFlag(MediaCodec.BUFFER_FLAG_END_OF_STREAM)) {
                        releaseOutputBufferSafely(codec, index)
                        return
                    }

                    if (!isCodecConfigSent && flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME)) {
                        outputBuffer.duplicate().extractCodecConfig(adjustedInfo)?.let { (sps, pps, vps) ->
                            onVideoInfo(sps.stripAnnexBStartCode(), pps?.stripAnnexBStartCode(), vps?.stripAnnexBStartCode())
                            isCodecConfigSent = true
                        }
                    }

                    fpsCalculator.recordFrame()

                    MediaFrame.VideoFrame(
                        data = outputBuffer,
                        info = MediaFrame.Info(
                            offset = adjustedInfo.offset,
                            size = adjustedInfo.size,
                            timestamp = adjustedInfo.presentationTimeUs,
                            isKeyFrame = flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        ),
                        releaseCallback = { releaseOutputBufferSafely(codec, index) }
                    )
                }
                onVideoFrame(videoFrame)
            }.onFailure { cause ->
                XLog.w(this@VideoEncoder.getLog("CodecCallback.onOutputBufferAvailable", "onFailure: ${cause.message}"), cause)
                releaseOutputBufferSafely(codec, index)
                onError(cause)
            }
        }

        override fun onError(codec: MediaCodec, cause: MediaCodec.CodecException) {
            XLog.w(this@VideoEncoder.getLog("CodecCallback.onError", "onFailure: ${cause.message}"), cause)
            onError(cause)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat): Unit = synchronized(encoderLock) {
            if (isCodecConfigSent) return
            format.extractCodecConfigFromFormat()?.let { (sps, pps, vps) ->
                onVideoInfo(sps.stripAnnexBStartCode(), pps?.stripAnnexBStartCode(), vps?.stripAnnexBStartCode())
                isCodecConfigSent = true
            }
        }
    }

    private fun Int.hasFlag(flag: Int) = (this and flag) != 0

    private fun releaseOutputBufferSafely(codec: MediaCodec, index: Int) {
        runCatching { codec.releaseOutputBuffer(index, false) }
    }

    private fun ByteBuffer.extractCodecConfig(bufferInfo: MediaCodec.BufferInfo): Triple<ByteArray, ByteArray?, ByteArray?>? =
        when (codecInfo.codec) {
            is Codec.Video.H264 -> H264Packet.extractSpsPps(this)?.let { (sps, pps) ->
                Triple(sps, pps, null)
            }

            is Codec.Video.H265 -> H265Packet.extractSpsPpsVps(this)?.let { (sps, pps, vps) ->
                Triple(sps, pps, vps)
            }

            is Codec.Video.AV1 -> Av1Packet.extractObuSeq(this, bufferInfo)?.let { seqHeader ->
                Triple(seqHeader, null, null)
            }
        }

    private fun MediaFormat.extractCodecConfigFromFormat(): Triple<ByteArray, ByteArray?, ByteArray?>? =
        when (codecInfo.codec) {
            is Codec.Video.H264 -> {
                val spsBuf = getByteBuffer("csd-0") ?: return null
                val ppsBuf = getByteBuffer("csd-1")
                val sps = spsBuf.toRawNalOrAnnexBFirstNal()
                val pps = ppsBuf?.toRawNalOrAnnexBFirstNal()
                sps?.let { Triple(it, pps, null) }
            }

            is Codec.Video.H265 -> {
                val csd0 = getByteBuffer("csd-0") ?: return null
                // Try as Annex-B first
                H265Packet.extractSpsPpsVps(csd0)?.let { (sps, pps, vps) -> Triple(sps, pps, vps) } ?: run {
                    // If not Annex-B, try convert HVCC to Annex-B and re-parse
                    val converted = convertLengthPrefixedToAnnexB(csd0)
                    converted?.let { H265Packet.extractSpsPpsVps(it) }?.let { (sps, pps, vps) -> Triple(sps, pps, vps) }
                }
            }

            is Codec.Video.AV1 -> getByteBuffer("csd-0")?.takeIf { it.remaining() >= 4 }?.let { av1Csd ->
                Triple(av1Csd.toByteArray(), null, null)
            }
        }

    private fun ByteBuffer.toRawNalOrAnnexBFirstNal(): ByteArray? {
        val dup = duplicate()
        if (dup.remaining() < 1) return null
        // Annex-B: return bytes between first and (optional) next start code
        if (dup.remaining() >= 4 && dup.get(0) == 0.toByte() && dup.get(1) == 0.toByte() &&
            ((dup.get(2) == 1.toByte()) || (dup.get(2) == 0.toByte() && dup.get(3) == 1.toByte()))
        ) {
            // Skip first start code
            val startSize = if (dup.get(2) == 1.toByte()) 3 else 4
            dup.position(startSize)
            val tail = dup.slice()
            // Find next start code to limit NAL payload
            val arr = ByteArray(tail.remaining())
            tail.get(arr)
            var end = arr.size
            for (i in 0 until arr.size - 3) {
                if (arr[i] == 0.toByte() && arr[i + 1] == 0.toByte() &&
                    (arr[i + 2] == 1.toByte() || (i + 3 < arr.size && arr[i + 2] == 0.toByte() && arr[i + 3] == 1.toByte()))
                ) {
                    end = i; break
                }
            }
            return arr.copyOfRange(0, end)
        }
        // AVCC: read length field (4 or 2 bytes) and return that many bytes
        if (dup.remaining() >= 2) {
            val lengthFieldBytes = if (dup.remaining() >= 4) 4 else 2
            var len = 0
            repeat(lengthFieldBytes) { i -> len = (len shl 8) or (dup.get(i).toInt() and 0xFF) }
            if (len > 0 && len <= dup.remaining() - lengthFieldBytes) {
                val out = ByteArray(len)
                dup.position(lengthFieldBytes)
                dup.get(out)
                return out
            }
        }
        return null
    }

    private fun convertLengthPrefixedToAnnexB(src: ByteBuffer): ByteBuffer? {
        val dup = src.duplicate()
        if (dup.remaining() < 4) return null
        fun tryConvert(n: Int): ByteBuffer? {
            val t = dup.duplicate()
            var remain = t.remaining()
            var cnt = 0
            while (remain >= n) {
                var len = 0
                repeat(n) { len = (len shl 8) or (t.get().toInt() and 0xFF) }
                if (len <= 0 || len > t.remaining()) return null
                t.position(t.position() + len)
                remain = t.remaining(); cnt++
            }
            if (remain != 0 || cnt == 0) return null
            val inSize = dup.remaining()
            val outSize = inSize - cnt * n + cnt * 4
            val out = ByteArray(outSize)
            val s2 = dup.duplicate()
            var dst = 0
            while (s2.remaining() >= n) {
                var len = 0
                repeat(n) { len = (len shl 8) or (s2.get().toInt() and 0xFF) }
                if (len <= 0 || len > s2.remaining()) return null
                out[dst++] = 0; out[dst++] = 0; out[dst++] = 0; out[dst++] = 1
                s2.get(out, dst, len); dst += len
            }
            return ByteBuffer.wrap(out, 0, dst)
        }
        return tryConvert(4) ?: tryConvert(2)
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicateBuffer = duplicate()
        val bytes = ByteArray(duplicateBuffer.remaining())
        duplicateBuffer.get(bytes)
        return bytes
    }
}
