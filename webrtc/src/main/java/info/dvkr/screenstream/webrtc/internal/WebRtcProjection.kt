package info.dvkr.screenstream.webrtc.internal

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.WrappedVideoDecoderFactory
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

internal class WebRtcProjection(private val serviceContext: Context) : AudioRecordDataCallback {

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

    private val mediaProjectionManager = serviceContext.applicationContext.getSystemService(MediaProjectionManager::class.java)

    private val rootEglBase: EglBase = EglBase.create()
    private val audioDeviceModule = JavaAudioDeviceModule.builder(serviceContext.applicationContext)
        .setAudioRecordDataCallback(this)
        .createAudioDeviceModule()
    internal val peerConnectionFactory: PeerConnectionFactory
    internal val videoCodecs: List<RtpCapabilities.CodecCapability>
    internal val audioCodecs: List<RtpCapabilities.CodecCapability>

    private val lock = Any()

    private var mediaProjection: MediaProjection? = null
    @Volatile private var deviceAudioMute: Boolean = true
    @Volatile private var deviceAudioRecord: AudioRecord? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    internal var localMediaSteam: LocalMediaSteam? = null
    internal var isStopped: Boolean = true
    internal var isRunning: Boolean = false

    init {
        XLog.d(getLog("init"))

        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(serviceContext.applicationContext)
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

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun setDeviceAudioMute(mute: Boolean) = synchronized(lock) {
        XLog.d(this@WebRtcProjection.getLog("setDeviceAudioMute", "$mute"))
        deviceAudioMute = mute
    }

    /**
     * Invoked after an audio sample is recorded. Can be used to manipulate
     * the ByteBuffer before it's fed into WebRTC. Currently the audio in the
     * ByteBuffer is always PCM 16bit and the buffer sample size is ~10ms.
     *
     * @param audioFormat format in android.media.AudioFormat
     */
    override fun onAudioDataRecorded(audioFormat: Int, channelCount: Int, sampleRate: Int, audioBuffer: ByteBuffer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && deviceAudioMute.not()) {
            if (deviceAudioRecord == null) {
                deviceAudioRecord = createAudioRecord(audioFormat, sampleRate, mediaProjection!!).apply { startRecording() }
            }
            deviceAudioRecord?.apply {
                val deviceAudioBuffer = ByteBuffer.allocateDirect(audioBuffer.capacity()).order(ByteOrder.nativeOrder())
                read(deviceAudioBuffer, deviceAudioBuffer.capacity(), AudioRecord.READ_BLOCKING)
                val mixed = mixBuffers(audioBuffer, deviceAudioBuffer)

                audioBuffer.clear()
                audioBuffer.put(mixed)
            }
        }
    }

    internal fun start(streamId: StreamId, intent: Intent, mediaProjectionCallbackOnStop: () -> Unit) {
        synchronized(lock) {
            XLog.d(getLog("start"))

            val mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, intent)
            val videoSource = peerConnectionFactory.createVideoSource(true)
            val audioSource = peerConnectionFactory.createAudioSource(audioMediaConstraints)

            val screenCapturer = ScreenCapturerAndroid(
                SurfaceTextureHelper.create("ScreenStreamSurfaceTexture", rootEglBase.eglBaseContext),
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        XLog.i(this@WebRtcProjection.getLog("MediaProjection.Callback", "onStop"))
                        synchronized(lock) { isStopped = true }
                        mediaProjectionCallbackOnStop.invoke()
                    }

                    // TODO https://android-developers.googleblog.com/2024/03/enhanced-screen-sharing-capabilities-in-android-14.html
                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        XLog.i(this@WebRtcProjection.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
                    }

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        XLog.i(this@WebRtcProjection.getLog("MediaProjection.Callback", "onCapturedContentResize: width: $width, height: $height"))
                        changeCaptureFormat(width, height)
                    }
                }
            )

            val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
            screenCapturer.startCapture(mediaProjection, screeSize.width(), screeSize.height(), videoSource.capturerObserver)

            val mediaStreamId = MediaStreamId.create(streamId)
            localMediaSteam = LocalMediaSteam(
                mediaStreamId,
                peerConnectionFactory.createVideoTrack("VideoTrack@$mediaStreamId", videoSource),
                peerConnectionFactory.createAudioTrack("AudioTrack@$mediaStreamId", audioSource)
            )

            this.mediaProjection = mediaProjection
            this.videoSource = videoSource
            this.audioSource = audioSource
            this.screenCapturer = screenCapturer
            isStopped = false
            isRunning = true
            XLog.d(getLog("start", "MediaStreamId: $mediaStreamId"))
        }
    }

    internal fun changeCaptureFormat() {
        val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
        changeCaptureFormat(screeSize.width(), screeSize.height())
    }

    internal fun changeCaptureFormat(width: Int, height: Int) {
        synchronized(lock) {
            if (isStopped || isRunning.not()) {
                XLog.i(this@WebRtcProjection.getLog("changeCaptureFormat", "Ignoring: isStopped=$isStopped, isRunning=$isRunning"))
                return
            }
            XLog.d(this@WebRtcProjection.getLog("changeCaptureFormat", "width:$width, height:$height"))
            screenCapturer?.apply {
                val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
                changeCaptureFormat(screeSize.width(), screeSize.height())
            }
        }
    }

    internal fun stop() {
        synchronized(lock) {
            XLog.d(getLog("stop"))

            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null

            mediaProjection = null

            localMediaSteam?.videoTrack?.dispose()
            localMediaSteam?.audioTrack?.dispose()
            localMediaSteam = null

            audioSource?.dispose()
            audioSource = null

            videoSource?.dispose()
            videoSource = null

            deviceAudioRecord?.release()
            deviceAudioRecord = null

            isStopped = true
            isRunning = false
        }
    }

    internal fun destroy() {
        XLog.d(getLog("destroy"))
        stop()
        peerConnectionFactory.dispose()
        rootEglBase.release()
        audioDeviceModule.release()
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createAudioRecord(audioFormat: Int, sampleRate: Int, mediaProjection: MediaProjection): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()


        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()
    }

    private fun mixBuffers(buffer1: ByteBuffer, buffer2: ByteBuffer): ByteArray {
        buffer1.rewind()
        val shortsArray1 = ShortArray(buffer1.capacity() / 2)
        buffer1.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()[shortsArray1]

        buffer2.rewind()
        val shortsArray2 = ShortArray(buffer2.capacity() / 2)
        buffer2.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()[shortsArray2]

        val size = max(shortsArray1.size, shortsArray2.size)
        if (size < 0) return ByteArray(0)
        val result = ByteArray(size * 2)
        for (i in 0 until size) {
            var sum: Int = when {
                i >= shortsArray1.size -> shortsArray2[i].toInt()
                i >= shortsArray2.size -> shortsArray1[i].toInt()
                else -> shortsArray1[i].toInt() + shortsArray2[i].toInt()
            }
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE.toInt()
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE.toInt()
            val byteIndex = i * 2
            result[byteIndex] = (sum and 0xFF).toByte()
            result[byteIndex + 1] = (sum shr 8 and 0xFF).toByte()
        }
        return result
    }
}