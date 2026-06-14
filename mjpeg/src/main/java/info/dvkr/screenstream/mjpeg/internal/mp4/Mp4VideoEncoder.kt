package info.dvkr.screenstream.mjpeg.internal.mp4

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
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegMasterClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal class Mp4VideoEncoder(
    private val codecInfo: Mp4VideoEncoderInfo,
    private val onVideoConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onVideoPacket: (Mp4VideoPacket) -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private enum class State { IDLE, PREPARED, RUNNING, STOPPED }

    private data class StopSnapshot(
        val eglRenderer: Mp4EglRenderer?,
        val mediaCodec: MediaCodec?,
        val handler: Handler?,
        val handlerThread: HandlerThread?
    )

    private val encoderLock = Any()
    private var currentState = State.IDLE
    private var mediaCodec: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var eglRenderer: Mp4EglRenderer? = null
    private var isCodecConfigSent: Boolean = false
    private var lastBitrate: Int? = null
    private var width: Int = 0
    private var height: Int = 0
    private var fps: Int = 30
    private var generation: Long = 0L
    private val adjustedBufferInfo = MediaCodec.BufferInfo()

    internal val inputSurfaceTexture: SurfaceTexture?
        get() = eglRenderer?.inputSurfaceTexture

    internal fun prepare(width: Int, height: Int, fps: Int, bitRate: Int, generation: Long) {
        runCatching {
            synchronized(encoderLock) {
                require(width % 2 == 0 && height % 2 == 0) { "Width and height must be even. Received: $width x $height" }
                require(fps > 0) { "FPS must be > 0. Received: $fps" }
                check(currentState == State.IDLE)

                this.width = width
                this.height = height
                this.fps = fps
                this.generation = generation

                val format = MediaFormat.createVideoFormat(H264_MIME_TYPE, width, height).apply {
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

                eglRenderer = Mp4EglRenderer(width, height, encoder.createInputSurface(), onError).apply { setFps(fps) }

                handlerThread = HandlerThread("Mp4VideoEncoderHandler", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
                handler = Handler(handlerThread!!.looper)
                encoder.setCallback(createCodecCallback(), handler)

                mediaCodec = encoder
                lastBitrate = bitRate
                currentState = State.PREPARED
            }
        }.onFailure { cause ->
            stopInternal(force = true, logTag = "prepareCleanup")
            onError(cause)
        }
    }

    internal fun start(): Unit = synchronized(encoderLock) {
        if (currentState != State.PREPARED) return
        isCodecConfigSent = false
        eglRenderer?.startAsync()
        mediaCodec?.start()
        currentState = State.RUNNING
    }

    internal fun stop() {
        stopInternal(force = false, logTag = "stopInternal")
    }

    internal fun setBitrate(newBitrate: Int): Unit = synchronized(encoderLock) {
        if (currentState != State.RUNNING || newBitrate <= 0) return
        val bitrateRange = codecInfo.capabilities.videoCapabilities?.bitrateRange
        val bitrate = bitrateRange?.let { newBitrate.coerceIn(it.lower, it.upper) } ?: newBitrate
        if (lastBitrate == bitrate) return
        runCatching {
            mediaCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
        }.onSuccess {
            lastBitrate = bitrate
        }.onFailure {
            XLog.w(getLog("setBitrate", "Error while updating bitrate."), it)
        }
    }

    private fun stopInternal(force: Boolean, logTag: String): Boolean {
        val snapshot = synchronized(encoderLock) {
            if (!force && (currentState == State.IDLE || currentState == State.STOPPED)) return@synchronized null

            currentState = State.STOPPED
            isCodecConfigSent = false
            lastBitrate = null

            StopSnapshot(eglRenderer, mediaCodec, handler, handlerThread).also {
                eglRenderer = null
                mediaCodec = null
                handler = null
                handlerThread = null
            }
        } ?: return false

        snapshot.eglRenderer?.stop()

        snapshot.mediaCodec?.runCatching {
            stop()
            release()
        }?.onFailure {
            XLog.w(getLog(logTag, "mediaCodec.stop() exception: ${it.message}"), it)
        }

        snapshot.handler?.removeCallbacksAndMessages(null)
        snapshot.handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure { XLog.w(getLog(logTag, "handlerThread.join() interrupted"), it) }
            if (isAlive) {
                quit()
                runCatching { join(250) }
            }
        }

        synchronized(encoderLock) {
            currentState = State.IDLE
        }
        return true
    }

    private fun isCallbackCodecActive(codec: MediaCodec): Boolean = synchronized(encoderLock) {
        codec === mediaCodec && currentState == State.RUNNING
    }

    private fun createCodecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val packet = synchronized(encoderLock) {
                    val activeCodec = mediaCodec
                    if (codec !== activeCodec || currentState != State.RUNNING || info.size == 0) {
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
                        val forcedPtsUs = MjpegMasterClock.relativeTimeUs()
                        set(info.offset, info.size, forcedPtsUs, info.flags)
                    }

                    val flags = adjustedInfo.flags
                    val isKeyFrame = flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    if (flags.hasFlag(MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        if (!isCodecConfigSent) {
                            outputBuffer.duplicate().extractSpsPps()?.let { (sps, pps) ->
                                onVideoConfig(sps.stripAnnexBStartCode(), pps.stripAnnexBStartCode())
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

                    if (!isCodecConfigSent && isKeyFrame) {
                        outputBuffer.duplicate().extractSpsPps()?.let { (sps, pps) ->
                            onVideoConfig(sps.stripAnnexBStartCode(), pps.stripAnnexBStartCode())
                            isCodecConfigSent = true
                        }
                    }

                    val data = outputBuffer.duplicate().toLengthPrefixedNalBytes(adjustedInfo.offset, adjustedInfo.size)
                    releaseOutputBufferSafely(codec, index)
                    Mp4VideoPacket(
                        generation = generation,
                        data = data,
                        timestampUs = adjustedInfo.presentationTimeUs,
                        durationUs = 1_000_000L / fps.coerceAtLeast(1),
                        isKeyFrame = isKeyFrame
                    )
                }
                onVideoPacket(packet)
            }.onFailure { cause ->
                releaseOutputBufferSafely(codec, index)
                if (!isCallbackCodecActive(codec)) return@onFailure
                XLog.w(getLog("CodecCallback.onOutputBufferAvailable", "onFailure: ${cause.message}"), cause)
                onError(cause)
            }
        }

        override fun onError(codec: MediaCodec, cause: MediaCodec.CodecException) {
            if (!isCallbackCodecActive(codec)) return
            XLog.w(getLog("CodecCallback.onError", "onFailure: ${cause.message}"), cause)
            onError(cause)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat): Unit = synchronized(encoderLock) {
            if (isCodecConfigSent) return
            val spsBuffer = format.getByteBuffer("csd-0")
            val ppsBuffer = format.getByteBuffer("csd-1")
            val sps = spsBuffer?.toRawNalOrAnnexBFirstNal()
            val pps = ppsBuffer?.toRawNalOrAnnexBFirstNal()
            if (sps != null && pps != null) {
                onVideoConfig(sps.stripAnnexBStartCode(), pps.stripAnnexBStartCode())
                isCodecConfigSent = true
            }
        }
    }

    private fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0

    private fun releaseOutputBufferSafely(codec: MediaCodec, index: Int) {
        runCatching { codec.releaseOutputBuffer(index, false) }
    }

    private fun ByteBuffer.extractSpsPps(): Pair<ByteArray, ByteArray>? {
        val bytes = ByteArray(remaining()).also {
            mark()
            get(it)
            reset()
        }
        val nals = bytes.splitAnnexBNals().ifEmpty { bytes.splitLengthPrefixedNals() }
        val sps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 } ?: return null
        val pps = nals.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 } ?: return null
        return sps to pps
    }

    private fun ByteBuffer.toRawNalOrAnnexBFirstNal(): ByteArray? {
        val bytes = ByteArray(remaining()).also {
            mark()
            get(it)
            reset()
        }
        return bytes.splitAnnexBNals().firstOrNull()
            ?: bytes.splitLengthPrefixedNals().firstOrNull()
            ?: bytes.takeIf { it.isNotEmpty() }
    }

    private fun ByteBuffer.toLengthPrefixedNalBytes(offset: Int, size: Int): ByteArray {
        val duplicate = duplicate().apply {
            position(offset)
            limit(offset + size)
        }
        val bytes = ByteArray(size).also { duplicate.get(it) }
        val annexBNals = bytes.splitAnnexBNals()
        val nals = annexBNals.ifEmpty { bytes.splitLengthPrefixedNals() }.ifEmpty { listOf(bytes) }
        return ByteArrayOutputStream().also { output ->
            nals.filter { it.isNotEmpty() }.forEach { nal ->
                output.write(byteArrayOf((nal.size ushr 24).toByte(), (nal.size ushr 16).toByte(), (nal.size ushr 8).toByte(), nal.size.toByte()))
                output.write(nal)
            }
        }.toByteArray()
    }

    private fun ByteArray.splitAnnexBNals(): List<ByteArray> {
        fun startCodeLength(index: Int): Int = when {
            index + 3 < size && this[index] == 0.toByte() && this[index + 1] == 0.toByte() &&
                    this[index + 2] == 0.toByte() && this[index + 3] == 1.toByte() -> 4
            index + 2 < size && this[index] == 0.toByte() && this[index + 1] == 0.toByte() && this[index + 2] == 1.toByte() -> 3
            else -> 0
        }

        val result = mutableListOf<ByteArray>()
        var i = 0
        while (i < size) {
            val startCode = startCodeLength(i)
            if (startCode == 0) {
                i++
                continue
            }
            val start = i + startCode
            var end = start
            while (end < size && startCodeLength(end) == 0) end++
            if (end > start) result.add(copyOfRange(start, end))
            i = end
        }
        return result
    }

    private fun ByteArray.splitLengthPrefixedNals(): List<ByteArray> {
        fun parse(lengthBytes: Int): List<ByteArray>? {
            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (offset + lengthBytes <= size) {
                var length = 0
                repeat(lengthBytes) { index -> length = (length shl 8) or (this[offset + index].toInt() and 0xFF) }
                offset += lengthBytes
                if (length <= 0 || offset + length > size) return null
                result.add(copyOfRange(offset, offset + length))
                offset += length
            }
            return if (offset == size && result.isNotEmpty()) result else null
        }
        return parse(4) ?: parse(2) ?: emptyList()
    }

    private fun ByteArray.stripAnnexBStartCode(): ByteArray {
        val startCodeLength = when {
            size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 0.toByte() && this[3] == 1.toByte() -> 4
            size >= 3 && this[0] == 0.toByte() && this[1] == 0.toByte() && this[2] == 1.toByte() -> 3
            else -> 0
        }
        return if (startCodeLength > 0) copyOfRange(startCodeLength, size) else this
    }
}
