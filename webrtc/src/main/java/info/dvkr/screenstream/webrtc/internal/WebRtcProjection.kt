package info.dvkr.screenstream.webrtc.internal

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.WrappedVideoDecoderFactory
import org.webrtc.audio.JavaAudioDeviceModule

internal class WebRtcProjection(private val serviceContext: Context) {

    private companion object {
        @JvmStatic
        private val audioMediaConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
    }

    private enum class AudioCodec { OPUS }
    private enum class VideoCodec(val priority: Int) { VP8(1), VP9(2), H264(3), H265(4); }

    private val windowContext by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val defaultDisplay = serviceContext.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            serviceContext.createDisplayContext(defaultDisplay).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
        } else {
            serviceContext
        }
    }

    private val audioDeviceModule = JavaAudioDeviceModule.builder(serviceContext).createAudioDeviceModule() // TODO Leaks Context
    private val rootEglBase: EglBase = EglBase.create()

    internal val peerConnectionFactory: PeerConnectionFactory
    internal val videoCodecs: List<RtpCapabilities.CodecCapability>
    internal val audioCodecs: List<RtpCapabilities.CodecCapability>

    private val lock = Any()

    private var screenCapturer: ScreenCapturerAndroid? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    internal var localMediaSteam: LocalMediaSteam? = null
    internal var isStopped: Boolean = true
    internal var isRunning: Boolean = false

    init {
        XLog.d(getLog("init"))

        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(serviceContext)
            .setInjectableLogger({ p0, _, _ -> XLog.e(this@WebRtcProjection.getLog("WebRTCLogger", p0)) }, Logging.Severity.LS_NONE)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(initializationOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoDecoderFactory(WrappedVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, false))
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val hardwareSupportedCodecs = HardwareVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            .supportedCodecs.map { it.name }

        videoCodecs = peerConnectionFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            .codecs.filter { it.isSupportedVideo() }.sortedByDescending { it.priority(it.name in hardwareSupportedCodecs) }

        audioCodecs = peerConnectionFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            .codecs.filter { it.isSupportedAudio() }
    }

    private fun RtpCapabilities.CodecCapability.isSupportedVideo(): Boolean = VideoCodec.entries.any { it.name == name.uppercase() }
    private fun RtpCapabilities.CodecCapability.isSupportedAudio(): Boolean = AudioCodec.entries.any { it.name == name.uppercase() }
    private fun RtpCapabilities.CodecCapability.priority(isHardwareSupported: Boolean): Int =
        VideoCodec.entries.first { it.name == name.uppercase() }.let { if (isHardwareSupported) it.priority + 10 else it.priority }

    internal fun setMicrophoneMute(mute: Boolean) {
        XLog.d(this@WebRtcProjection.getLog("setMicrophoneMute", "$mute"))
        audioDeviceModule.setMicrophoneMute(mute)
    }

    internal fun start(streamId: StreamId, intent: Intent, mediaProjectionCallbackOnStop: () -> Unit) {
        synchronized(lock) {
            XLog.d(getLog("start"))

            screenCapturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
                override fun onStop() {
                    XLog.i(this@WebRtcProjection.getLog("MediaProjection.Callback", "onStop"))
                    synchronized(lock) { isStopped = true }
                    mediaProjectionCallbackOnStop.invoke()
                }
            })
            surfaceTextureHelper = SurfaceTextureHelper.create("ScreenStreamSurfaceTexture", rootEglBase.eglBaseContext)
            videoSource = peerConnectionFactory.createVideoSource(screenCapturer!!.isScreencast)
            screenCapturer!!.initialize(surfaceTextureHelper, serviceContext, videoSource!!.capturerObserver)

            val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(windowContext).bounds
            screenCapturer!!.startCapture(screeSize.width(), screeSize.height(), 30)

            audioSource = peerConnectionFactory.createAudioSource(audioMediaConstraints)

            val mediaStreamId = MediaStreamId.create(streamId)
            localMediaSteam = LocalMediaSteam(
                mediaStreamId,
                peerConnectionFactory.createVideoTrack("VideoTrack@$mediaStreamId", videoSource),
                peerConnectionFactory.createAudioTrack("AudioTrack@$mediaStreamId", audioSource)
            )

            isStopped = false
            isRunning = true
            XLog.d(getLog("start", "MediaStreamId: $mediaStreamId"))
        }
    }

    internal fun changeCaptureFormat() {
        synchronized(lock) {
            if (isStopped || isRunning.not()) {
                XLog.i(this@WebRtcProjection.getLog("changeCaptureFormat", "Ignoring: isStopped=$isStopped, isRunning=$isRunning"))
                return
            }
            XLog.d(this@WebRtcProjection.getLog("changeCaptureFormat"))
            screenCapturer?.apply {
                val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(windowContext).bounds
                changeCaptureFormat(screeSize.width(), screeSize.height(), 30)
            }
        }
    }

    internal fun stop() {
        synchronized(lock) {
            XLog.d(getLog("stop"))

            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localMediaSteam?.videoTrack?.dispose()
            localMediaSteam?.audioTrack?.dispose()
            localMediaSteam = null

            audioSource?.dispose()
            audioSource = null

            videoSource?.dispose()
            videoSource = null

            isStopped = true
            isRunning = false
        }
    }

    internal fun destroy() {
        XLog.d(getLog("destroy"))
        stop()
        rootEglBase.release()
        audioDeviceModule.release()
    }
}