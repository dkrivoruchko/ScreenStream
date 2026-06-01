package info.dvkr.screenstream.mjpeg.internal.mp4

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import androidx.core.util.toClosedRange
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegAudioSource
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

internal class Mp4Capture(
    private val serviceContext: Context,
    private val settings: MjpegSettings.Data,
    private val mediaProjection: MediaProjection?,
    private val generation: Long,
    private val enableMic: Boolean,
    private val enableDeviceAudio: Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val configFlow: MutableStateFlow<Mp4StreamConfig?>,
    private val videoPacketFlow: MutableSharedFlow<Mp4VideoPacket>,
    private val audioPacketFlow: MutableSharedFlow<Mp4AudioPacket>,
    private val onError: (MjpegError) -> Unit,
    private val onAudioCaptureError: (Throwable) -> Unit
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var captureSurface: Surface? = null
    private var videoEncoder: Mp4VideoEncoder? = null
    private var audioEncoder: Mp4AudioEncoder? = null
    private var videoConfig: Mp4VideoConfig? = null
    private var audioConfig: Mp4AudioConfig? = null
    private var started = false

    private val streamAudioOnly: Boolean = settings.streamAudioOnly
    private val audioEnabled: Boolean = enableMic || enableDeviceAudio

    internal fun start(isStartupStillValid: () -> Boolean): Boolean {
        check(!started) { "MP4 capture already started" }
        started = true
        configFlow.value = null

        runCatching {
            if (!streamAudioOnly) startVideo(isStartupStillValid)
            if (audioEnabled) startAudio()
            publishConfigIfReady()
        }.onFailure { cause ->
            XLog.w(getLog("start", "Failed to start MP4 capture: ${cause.message}"), cause)
            stop()
            if (cause is MjpegError) onError(cause) else onError(MjpegError.UnknownError(cause))
        }

        return if (streamAudioOnly) audioEncoder?.isCapturing == true else virtualDisplay != null && videoEncoder != null
    }

    internal fun stop() {
        videoEncoder?.stop()
        videoEncoder = null

        virtualDisplay?.surface = null
        virtualDisplay?.release()
        virtualDisplay = null

        runCatching { captureSurface?.release() }
        captureSurface = null

        audioEncoder?.stop()
        audioEncoder = null

        videoConfig = null
        audioConfig = null
        configFlow.value = null
        started = false
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        audioEncoder?.setMute(micMute, deviceMute)
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        audioEncoder?.setVolume(micVolume, deviceVolume)
    }

    internal fun setVideoBitrate(videoBitrateBits: Int) {
        videoEncoder?.setBitrate(videoBitrateBits)
    }

    private fun startVideo(isStartupStillValid: () -> Boolean) {
        val encoderInfo = requireNotNull(
            Mp4VideoEncoderUtils.selectH264Encoder(settings.videoCodecAutoSelect, settings.videoCodec)
        ) { "No H.264 video encoder available" }
        val videoCapabilities = requireNotNull(encoderInfo.capabilities.videoCapabilities) { "Missing H.264 video capabilities" }
        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
        val (width, height) = with(Mp4VideoEncoderUtils) {
            videoCapabilities.adjustSize(
                sourceWidth = bounds.width(),
                sourceHeight = bounds.height(),
                resizeFactor = settings.resizeFactor / 100F,
                exactWidth = settings.resolutionWidth,
                exactHeight = settings.resolutionHeight,
                stretch = settings.resolutionStretch
            )
        }
        val requestedFps = when {
            settings.maxFPS > 0 -> settings.maxFPS
            settings.maxFPS < 0 -> (1F / -settings.maxFPS).roundToInt().coerceAtLeast(1)
            else -> MjpegSettings.Default.MAX_FPS
        }
        val fps = requestedFps.coerceIn(videoCapabilities.supportedFrameRates.toClosedRange())
        val bitrate = settings.videoBitrateBits.coerceIn(videoCapabilities.bitrateRange.toClosedRange())

        val encoder = Mp4VideoEncoder(
            codecInfo = encoderInfo,
            onVideoConfig = { sps, pps ->
                videoConfig = Mp4VideoConfig(width, height, sps, pps, fps)
                publishConfigIfReady()
            },
            onVideoPacket = { packet -> videoPacketFlow.tryEmit(packet) },
            onError = {
                XLog.w(getLog("VideoEncoder.onError", it.message), it)
                onError(MjpegError.UnknownError(it))
            }
        ).also { videoEncoder = it }

        encoder.prepare(width, height, fps, bitrate, generation)
        if (!isStartupStillValid()) {
            encoder.stop()
            return
        }

        val inputSurfaceTexture = encoder.inputSurfaceTexture ?: throw IllegalStateException("H.264 encoder input surface is null")
        val surface = Surface(inputSurfaceTexture)
        captureSurface = surface
        virtualDisplay = requireNotNull(mediaProjection) { "MediaProjection is required for MP4 video capture" }.createVirtualDisplay(
            "Mp4CaptureVirtualDisplay",
            width,
            height,
            serviceContext.resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        if (virtualDisplay == null || !isStartupStillValid()) {
            encoder.stop()
            runCatching { surface.release() }
            captureSurface = null
            virtualDisplay?.release()
            virtualDisplay = null
            return
        }

        encoder.start()
    }

    private fun startAudio() {
        val encoderInfo = requireNotNull(Mp4AudioEncoderUtils.selectedAacEncoder) { "No AAC audio encoder available" }
        val params = MjpegAudioSource.Params.DEFAULT_OPUS.copy(
            sampleRate = AAC_SAMPLE_RATE,
            bitrate = settings.audioBitrateBits,
            echoCanceler = settings.audioEchoCanceller,
            noiseSuppressor = settings.audioNoiseSuppressor,
            isStereo = true
        )

        val encoder = Mp4AudioEncoder(
            codecInfo = encoderInfo,
            generation = generation,
            onAudioConfig = { config ->
                audioConfig = config
                publishConfigIfReady()
            },
            onAudioPacket = { packet -> audioPacketFlow.tryEmit(packet) },
            onAudioCaptureError = onAudioCaptureError,
            onError = {
                XLog.w(getLog("AudioEncoder.onError", it.message), it)
                onError(MjpegError.UnknownError(it))
            }
        ).also { audioEncoder = it }

        encoder.prepare(
            enableMic = enableMic,
            enableDeviceAudio = enableDeviceAudio,
            dispatcher = dispatcher,
            audioParams = params,
            mediaProjection = mediaProjection
        )
        encoder.setMute(settings.muteMic, settings.muteDeviceAudio)
        encoder.setVolume(settings.volumeMic, settings.volumeDeviceAudio)
        encoder.start()
        check(encoder.isCapturing) { "AAC audio capture did not start" }
    }

    private fun publishConfigIfReady() {
        val video = videoConfig
        val audio = audioConfig
        if (!streamAudioOnly && video == null) return
        if (audioEnabled && audio == null) return
        configFlow.value = Mp4StreamConfig(
            generation = generation,
            video = if (streamAudioOnly) null else video,
            audio = if (audioEnabled) audio else null
        )
    }

}
