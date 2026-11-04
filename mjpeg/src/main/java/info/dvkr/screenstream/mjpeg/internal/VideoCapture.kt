package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.roundToInt

/** Common surface for the MJPEG (bitmap) and hardware-video capture paths. */
internal interface ScreenCapture {
    fun start(isStartupStillValid: () -> Boolean): Boolean
    fun destroy()
    fun resize()
    fun resize(width: Int, height: Int)
}

/** A single encoded access unit. [data] for key frames always starts with the codec's in-band config (SPS/PPS/VPS or AV1 sequence header). */
internal class VideoPacket(val seq: Long, val isKey: Boolean, val ptsUs: Long, val data: ByteArray)

/** WebCodecs-compatible description of the current stream. */
internal data class VideoConfig(val codec: String, val width: Int, val height: Int)

/** Shared between the capture (producer) and HTTP server (consumers). */
internal class VideoStreamHub {
    private val _packets = MutableSharedFlow<VideoPacket>(extraBufferCapacity = 90, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val packets: SharedFlow<VideoPacket> = _packets.asSharedFlow()

    private val _config = MutableStateFlow<VideoConfig?>(null)
    internal val config: StateFlow<VideoConfig?> = _config.asStateFlow()

    @Volatile internal var keyFrameRequester: (() -> Unit)? = null

    internal fun emit(packet: VideoPacket) { _packets.tryEmit(packet) }
    internal fun setConfig(config: VideoConfig?) { _config.value = config }
    internal fun requestKeyFrame() { keyFrameRequester?.invoke() }
}

/**
 * Captures the screen straight into a hardware video encoder (no CPU pixel copies) and publishes Annex-B / OBU packets
 * that browsers decode with WebCodecs. Crop, rotation, flip and grayscale are MJPEG-only features.
 */
internal class VideoCapture(
    private val serviceContext: Context,
    private val mjpegSettings: MjpegSettings,
    private val mediaProjection: MediaProjection,
    private val hub: VideoStreamHub,
    private val onError: (MjpegError) -> Unit
) : ScreenCapture {

    internal companion object {
        internal fun mimeFor(videoCodec: Int): String? = when (videoCodec) {
            MjpegSettings.Values.VIDEO_CODEC_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            MjpegSettings.Values.VIDEO_CODEC_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            MjpegSettings.Values.VIDEO_CODEC_AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> null
        }

        internal fun findEncoder(mime: String): MediaCodecInfo? {
            val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
            fun MediaCodecInfo.isSoftware(): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isSoftwareOnly
                else name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }
            return encoders.firstOrNull { it.isSoftware().not() } ?: encoders.firstOrNull()
        }

        private val supportCache = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        internal fun isSupported(videoCodec: Int): Boolean =
            supportCache.getOrPut(videoCodec) { mimeFor(videoCodec)?.let { findEncoder(it) } != null }
    }

    private enum class State { INIT, STARTED, DESTROYED, ERROR }

    private var state = State.INIT
    private val thread = HandlerThread("VideoCapture", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var codecConfig: ByteArray? = null
    private var seq = 0L
    private var generation = 0

    @Synchronized
    override fun start(isStartupStillValid: () -> Boolean): Boolean {
        XLog.d(getLog("start"))
        check(state == State.INIT) { "VideoCapture in state $state" }
        try {
            val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
            val surface = createEncoder(bounds.width(), bounds.height())
            if (!isStartupStillValid()) {
                state = State.ERROR; release(); return false
            }
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "VideoCaptureVirtualDisplay", width, height, serviceContext.resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler
            )
            if (virtualDisplay == null || !isStartupStillValid()) {
                state = State.ERROR; release(); return false
            }
            hub.keyFrameRequester = ::requestKeyFrame
            state = State.STARTED
        } catch (ex: SecurityException) {
            XLog.w(getLog("start", ex.toString()), ex)
            state = State.ERROR
            onError(MjpegError.CastSecurityException())
            release()
        } catch (ex: Exception) {
            XLog.e(getLog("start"), ex)
            state = State.ERROR
            onError(MjpegError.BitmapCaptureException(ex))
            release()
        }
        return state == State.STARTED
    }

    @Synchronized
    override fun destroy() {
        XLog.d(getLog("destroy"))
        if (state == State.DESTROYED) return
        state = State.DESTROYED
        release()
        thread.quitSafely()
    }

    @Synchronized
    override fun resize() {
        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
        resize(bounds.width(), bounds.height())
    }

    @Synchronized
    override fun resize(width: Int, height: Int) {
        if (state != State.STARTED) return
        val (w, h) = targetSize(width, height)
        if (w == this.width && h == this.height) return
        XLog.d(getLog("resize", "$width x $height -> $w x $h"))
        try {
            virtualDisplay?.surface = null
            stopEncoder()
            val surface = createEncoder(width, height)
            virtualDisplay?.resize(this.width, this.height, serviceContext.resources.configuration.densityDpi)
            virtualDisplay?.surface = surface
        } catch (ex: Exception) {
            XLog.e(getLog("resize"), ex)
            state = State.ERROR
            onError(MjpegError.BitmapCaptureException(ex))
            release()
        }
    }

    @Synchronized
    private fun requestKeyFrame() {
        if (state != State.STARTED) return
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun release() {
        hub.keyFrameRequester = null
        hub.setConfig(null)
        virtualDisplay?.release()
        virtualDisplay = null
        stopEncoder()
    }

    private fun stopEncoder() {
        generation++
        encoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        encoder = null
        inputSurface?.release()
        inputSurface = null
        codecConfig = null
    }

    /** Size in pixels after applying resize settings, aligned to 16 and clamped to what the encoder supports. */
    private fun targetSize(screenWidth: Int, screenHeight: Int, caps: MediaCodecInfo.VideoCapabilities? = null): Pair<Int, Int> {
        val data = mjpegSettings.data.value
        var scale = when {
            data.resolutionWidth > 0 && data.resolutionHeight > 0 ->
                minOf(data.resolutionWidth.toFloat() / screenWidth, data.resolutionHeight.toFloat() / screenHeight, 1f)
            else -> (data.resizeFactor / 100f).coerceIn(0.1f, 1f)
        }
        fun aligned(v: Float) = max(16, (v / 16f).roundToInt() * 16)
        var w = aligned(screenWidth * scale)
        var h = aligned(screenHeight * scale)
        if (caps != null) {
            while (scale > 0.1f && caps.isSizeSupported(w, h).not()) {
                scale *= 0.9f
                w = aligned(screenWidth * scale); h = aligned(screenHeight * scale)
            }
        }
        return w to h
    }

    private fun createEncoder(screenWidth: Int, screenHeight: Int): Surface {
        val data = mjpegSettings.data.value
        val mime = mimeFor(data.videoCodec) ?: throw IllegalStateException("No video codec selected")
        val info = findEncoder(mime) ?: throw IllegalStateException("No encoder for $mime")
        val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: throw IllegalStateException("No video capabilities for $mime")
        val (w, h) = targetSize(screenWidth, screenHeight, caps)
        width = w; height = h

        val fps = if (data.maxFPS > 0) data.maxFPS else 30
        val bitRate = (data.videoBitrateKbps * 1000).coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper)
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Keep emitting frames for a static screen, so late joiners and the browser's decoder don't stall
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                if (data.maxFPS > 0) setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, data.maxFPS.toFloat())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
        }

        val codec = MediaCodec.createByCodecName(info.name)
        val myGeneration = ++generation
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                synchronized(this@VideoCapture) {
                    if (myGeneration != generation) return
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset).limit(info.offset + info.size)
                            val bytes = ByteArray(info.size).also { buffer.get(it) }
                            onEncoded(mime, bytes, info.flags, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (ex: IllegalStateException) {
                        XLog.w(this@VideoCapture.getLog("onOutputBufferAvailable", ex.toString()))
                    }
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                synchronized(this@VideoCapture) {
                    if (myGeneration != generation) return
                    XLog.e(this@VideoCapture.getLog("onError"), e)
                    state = State.ERROR
                    onError(MjpegError.BitmapCaptureException(e))
                    release()
                }
            }
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        inputSurface = surface
        XLog.i(getLog("createEncoder", "${info.name} $mime ${w}x$h @ $fps fps, ${bitRate / 1000} kbps"))
        return surface
    }

    private fun onEncoded(mime: String, bytes: ByteArray, flags: Int, ptsUs: Long) {
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            val config = if (mime == MediaFormat.MIMETYPE_VIDEO_AV1) Bitstream.av1ConfigObus(bytes) else bytes
            codecConfig = config.takeIf { it.isNotEmpty() }
            val codecString = when (mime) {
                MediaFormat.MIMETYPE_VIDEO_AVC -> Bitstream.avcCodecString(config)
                MediaFormat.MIMETYPE_VIDEO_HEVC -> Bitstream.hevcCodecString(config)
                else -> Bitstream.av1CodecString(bytes)
            }
            hub.setConfig(VideoConfig(codecString, width, height))
            return
        }
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val config = codecConfig
        // Some AV1 encoders (e.g. Pixel c2.google.av1.encoder) prefix sync frames with the 4-byte av1C record,
        // which belongs in MP4 containers only; WebCodecs expects a plain OBU stream.
        val bytes = if (mime == MediaFormat.MIMETYPE_VIDEO_AV1 && bytes.size > 4 && (bytes[0].toInt() and 0xFF) == 0x81) {
            bytes.copyOfRange(4, bytes.size)
        } else bytes
        val data = if (isKey && config != null && Bitstream.startsWith(bytes, config).not()) config + bytes else bytes
        hub.emit(VideoPacket(seq++, isKey, ptsUs, data))
    }
}

/** Minimal bitstream helpers to build WebCodecs codec strings (RFC 6381 / AV1 ISOBMFF). */
internal object Bitstream {
    internal fun startsWith(data: ByteArray, prefix: ByteArray): Boolean =
        data.size >= prefix.size && prefix.indices.all { data[it] == prefix[it] }

    /** Yields (offset of NAL header, end) for each Annex-B NAL unit. */
    private fun nalUnits(data: ByteArray): List<IntRange> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3); i += 3
            } else i++
        }
        return starts.mapIndexed { idx, s ->
            var end = if (idx + 1 < starts.size) starts[idx + 1] - 3 else data.size
            while (end > s && data[end - 1].toInt() == 0) end--
            s until end
        }
    }

    private fun unescape(data: ByteArray, range: IntRange): ByteArray {
        val out = java.io.ByteArrayOutputStream(range.count())
        var zeros = 0
        for (i in range) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    internal fun avcCodecString(config: ByteArray): String {
        val sps = nalUnits(config).firstOrNull { (config[it.first].toInt() and 0x1F) == 7 && it.count() >= 4 }
            ?: return "avc1.42E01F"
        return "avc1.%02X%02X%02X".format(config[sps.first + 1], config[sps.first + 2], config[sps.first + 3])
    }

    internal fun hevcCodecString(config: ByteArray): String {
        val spsRange = nalUnits(config).firstOrNull { ((config[it.first].toInt() shr 1) and 0x3F) == 33 }
            ?: return "hev1.1.6.L153.B0"
        val sps = unescape(config, spsRange)
        if (sps.size < 15) return "hev1.1.6.L153.B0"
        // sps[0..1] NAL header, sps[2] vps_id/max_sub_layers/nesting, then profile_tier_level
        val ptl = 3
        val b = sps[ptl].toInt() and 0xFF
        val profileSpace = b shr 6
        val tier = (b shr 5) and 1
        val profileIdc = b and 0x1F
        var compat = 0L
        for (k in 0 until 4) compat = (compat shl 8) or (sps[ptl + 1 + k].toLong() and 0xFF)
        val reversed = java.lang.Long.reverse(compat) ushr 32
        val constraints = (0 until 6).map { sps[ptl + 5 + it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        val level = sps[ptl + 11].toInt() and 0xFF
        val space = when (profileSpace) { 1 -> "A"; 2 -> "B"; 3 -> "C"; else -> "" }
        val sb = StringBuilder("hev1.$space$profileIdc.${java.lang.Long.toHexString(reversed).uppercase()}.${if (tier == 1) "H" else "L"}$level")
        constraints.forEach { sb.append('.').append("%X".format(it)) }
        return sb.toString()
    }

    /** MediaCodec AV1 CSD may be an `av1C` record (marker bit set) followed by config OBUs, or raw OBUs. */
    internal fun av1ConfigObus(csd: ByteArray): ByteArray =
        if (csd.size >= 4 && (csd[0].toInt() and 0x80) != 0) csd.copyOfRange(4, csd.size) else csd

    internal fun av1CodecString(csd: ByteArray): String {
        if (csd.size >= 4 && (csd[0].toInt() and 0x80) != 0) {
            val profile = (csd[1].toInt() and 0xFF) shr 5
            val level = csd[1].toInt() and 0x1F
            val tier = if ((csd[2].toInt() and 0x80) != 0) "H" else "M"
            val highBitDepth = (csd[2].toInt() and 0x40) != 0
            val twelveBit = (csd[2].toInt() and 0x20) != 0
            val depth = if (highBitDepth) (if (twelveBit) 12 else 10) else 8
            return "av01.$profile.%02d$tier.%02d".format(level, depth)
        }
        return "av01.0.13M.08"
    }
}
