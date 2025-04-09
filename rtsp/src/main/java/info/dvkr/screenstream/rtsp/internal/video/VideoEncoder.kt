package info.dvkr.screenstream.rtsp.internal.video

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

    internal var width: Int = 0
        private set

    internal var height: Int = 0
        private set

    internal val inputSurfaceTexture
        get() = eglRenderer?.inputSurfaceTexture ?: throw IllegalStateException("EglRenderer not initialized")

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
//                        setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    }

                    if (codecInfo.isCBRModeSupported) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    } else {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }

                    if (codecInfo.codec is Codec.Video.H264) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
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
                currentState = State.PREPARED
            }
        }.onFailure { cause ->
            XLog.w(getLog("prepare", "Failed to prepare video encoder: $cause"), cause)
            onError(cause)
        }
    }

    internal fun start() = synchronized(encoderLock) {
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

    internal fun stop() = synchronized(encoderLock) {
        XLog.v(getLog("stop"))
        if (currentState == State.IDLE || currentState == State.STOPPED) {
            XLog.w(getLog("stop", "Cannot stop unless state is RUNNING. Current: $currentState"))
            return
        }

        currentState = State.STOPPED
        isCodecConfigSent = false

        eglRenderer?.stop()
        eglRenderer = null

        videoEncoder?.apply {
            runCatching {
                stop()
                release()
            }.onFailure {
                XLog.w(this@VideoEncoder.getLog("stopInternal", "mediaCodec.stop() exception: ${it.message}"), it)
            }
        }
        videoEncoder = null

        handler?.removeCallbacksAndMessages(null)
        handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                quit()
                XLog.w(this@VideoEncoder.getLog("stopInternal", "handlerThread.join() took too long, forcing a shutdown"))
            }
        }
        handlerThread = null
        handler = null

        currentState = State.IDLE

        XLog.v(getLog("stop", "Done"))
    }

    internal fun setBitrate(newBitrate: Int): Unit = synchronized(encoderLock) {
        if (currentState != State.RUNNING) {
            XLog.w(getLog("setBitrate", "Ignoring setBitrate($newBitrate); not in RUNNING state."))
            return
        }
        runCatching {
            videoEncoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate) })
        }.onFailure {
            XLog.w(getLog("setBitrate", "Error while updating bitrate; codec in invalid state."), it)
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
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        return
                    }

                    val outputBuffer = codec.getOutputBuffer(index) ?: run {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        return
                    }

                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)

                    val adjustedInfo = MediaCodec.BufferInfo().apply {
                        val forcedPtsUs = MasterClock.relativeTimeUs()
                        set(info.offset, info.size, forcedPtsUs, info.flags)
                    }

                    if (!isCodecConfigSent &&
                        (adjustedInfo.flags.hasFlag(MediaCodec.BUFFER_FLAG_CODEC_CONFIG) ||
                                adjustedInfo.flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    ) {
                        outputBuffer.duplicate().extractCodecConfig()?.let { (sps, pps, vps) ->
                            onVideoInfo(sps, pps, vps)
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
                            isKeyFrame = adjustedInfo.flags.hasFlag(MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        ),
                        releaseCallback = { runCatching { codec.releaseOutputBuffer(index, false) } }
                    )
                }
                onVideoFrame(videoFrame)
            }.onFailure { cause ->
                XLog.w(this@VideoEncoder.getLog("CodecCallback.onOutputBufferAvailable", "onFailure: ${cause.message}"), cause)
                runCatching { codec.releaseOutputBuffer(index, false) }
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
                onVideoInfo(sps, pps, vps)
                isCodecConfigSent = true
            }
        }
    }

    private fun Int.hasFlag(flag: Int) = (this and flag) != 0

    private fun ByteBuffer.extractCodecConfig(): Triple<ByteArray, ByteArray?, ByteArray?>? =
        when (codecInfo.codec) {
            is Codec.Video.H264 -> H264Packet.extractSpsPps(this)?.let { (sps, pps) ->
                Triple(sps, pps, null)
            }

            is Codec.Video.H265 -> H265Packet.extractVpsSpsPps(this)?.let { (sps, pps, vps) ->
                Triple(sps, pps, vps)
            }

            is Codec.Video.AV1 -> Av1Packet.extractObuSeq(this, MediaCodec.BufferInfo())?.let { seqHeader ->
                Triple(seqHeader, null, null)
            }
        }

    private fun MediaFormat.extractCodecConfigFromFormat(): Triple<ByteArray, ByteArray?, ByteArray?>? =
        when (codecInfo.codec) {
            is Codec.Video.H264 -> getByteBuffer("csd-0")?.let { sps ->
                Triple(sps.toByteArray(), getByteBuffer("csd-1")?.toByteArray(), null)
            }

            is Codec.Video.H265 -> getByteBuffer("csd-0")?.let { csd0 ->
                H265Packet.extractVpsSpsPps(csd0)?.let { (sps, pps, vps) ->
                    Triple(sps, pps, vps)
                }
            }

            is Codec.Video.AV1 -> getByteBuffer("csd-0")?.takeIf { it.remaining() >= 4 }?.let { av1Csd ->
                Triple(av1Csd.toByteArray(), null, null)
            }
        }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val duplicateBuffer = duplicate()
        val bytes = ByteArray(duplicateBuffer.remaining())
        duplicateBuffer.get(bytes)
        return bytes
    }
}