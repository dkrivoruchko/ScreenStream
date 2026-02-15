package info.dvkr.screenstream.webrtc.internal

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.window.layout.WindowMetricsCalculator
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.webrtc.R
import org.webrtc.AudioSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpCapabilities
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.audio.AudioRecordDataCallback
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

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
    private enum class VideoCodec(val priority: Int) { VP8(1), VP9(2), H265(3), H264(4); }

    private val mainHandler = Handler(Looper.getMainLooper())

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
    private var deviceAudioRecoveryUsed: Boolean = false
    private var reusableDeviceAudioBuffer: ByteBuffer? = null
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
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
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
        if (!mute) deviceAudioRecoveryUsed = false
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
            val projection = synchronized(lock) {
                if (isStopped || isRunning.not()) return
                mediaProjection
            } ?: run {
                XLog.i(getLog("onAudioDataRecorded", "MediaProjection is null. Ignoring device-audio mix."))
                return
            }

            var record: AudioRecord? = synchronized(lock) { deviceAudioRecord }
            if (record == null) {
                val createdRecord = createAudioRecord(audioFormat, sampleRate, projection) ?: run {
                    recoverOrDisableDeviceAudio(null, "Cannot create AudioRecord for projection.")
                    return
                }
                runCatching { createdRecord.startRecording() }.onFailure { cause ->
                    recoverOrDisableDeviceAudio(createdRecord, "AudioRecord start failed.", cause)
                    return
                }
                synchronized(lock) {
                    if (isStopped || isRunning.not() || mediaProjection == null) {
                        runCatching { createdRecord.release() }
                    } else if (deviceAudioRecord == null) {
                        deviceAudioRecord = createdRecord
                    } else {
                        runCatching { createdRecord.release() }
                    }
                    record = deviceAudioRecord
                }
            }

            val activeRecord = record ?: return
            val deviceAudioBuffer = obtainDeviceAudioBuffer(audioBuffer.capacity())
            val readBytes = runCatching {
                activeRecord.read(deviceAudioBuffer, deviceAudioBuffer.capacity(), AudioRecord.READ_BLOCKING)
            }.onFailure { cause ->
                recoverOrDisableDeviceAudio(activeRecord, "AudioRecord.read failed.", cause)
            }.getOrNull() ?: return
            if (readBytes <= 0) {
                recoverOrDisableDeviceAudio(activeRecord, "AudioRecord.read failed: $readBytes.")
                return
            }
            mixPcm16InPlace(audioBuffer, deviceAudioBuffer, readBytes)
            audioBuffer.position(audioBuffer.limit())
            synchronized(lock) { deviceAudioRecoveryUsed = false }
        }
    }

    internal fun start(streamId: StreamId, mediaProjection: MediaProjection, onCaptureFatal: (Throwable) -> Unit): Boolean {
        synchronized(lock) {
            XLog.d(getLog("start"))

            val videoSource = peerConnectionFactory.createVideoSource(true)
            val audioSource = peerConnectionFactory.createAudioSource(audioMediaConstraints)

            val screenCapturer = ScreenCapturerAndroid(
                SurfaceTextureHelper.create("ScreenStreamSurfaceTexture", rootEglBase.eglBaseContext),
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        XLog.i(this@WebRtcProjection.getLog("MediaProjection.Callback", "onStop"))
                        synchronized(lock) { isStopped = true }
                    }

                    override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                        XLog.v(this@WebRtcProjection.getLog("MediaProjection.Callback", "onCapturedContentVisibilityChanged: $isVisible"))
                    }

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        XLog.v(this@WebRtcProjection.getLog("MediaProjection.Callback", "onCapturedContentResize: width:$width, height:$height"))
                        changeCaptureFormat(width, height)
                    }
                },
                onCaptureFatal = { cause ->
                    synchronized(lock) { isStopped = true }
                    onCaptureFatal(cause)
                }
            )

            val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
            val captureStarted = screenCapturer.startCapture(
                mediaProjection, screeSize.width(), screeSize.height(), videoSource.capturerObserver
            )
            if (!captureStarted) {
                XLog.i(getLog("start", "Capture start failed. Stopping projection."))
                screenCapturer.dispose()
                videoSource.dispose()
                audioSource.dispose()
                return false
            }

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
            deviceAudioRecoveryUsed = false
            isStopped = false
            isRunning = true
            XLog.d(getLog("start", "MediaStreamId: $mediaStreamId"))
            return true
        }
    }

    internal fun changeCaptureFormat(width: Int, height: Int) {
        synchronized(lock) {
            if (isStopped || isRunning.not()) {
                XLog.i(this@WebRtcProjection.getLog("changeCaptureFormat", "Ignoring: isStopped=$isStopped, isRunning=$isRunning"))
                return
            }
            if (width <= 0 || height <= 0) {
                XLog.e(
                    this@WebRtcProjection.getLog("changeCaptureFormat", "Invalid size: $width x $height. Ignoring."),
                    IllegalArgumentException("Invalid capture size: $width x $height")
                )
                return
            }
            XLog.d(this@WebRtcProjection.getLog("changeCaptureFormat", "width:$width, height:$height"))
            screenCapturer?.changeCaptureFormat(width, height)
        }
    }

    internal fun forceKeyFrame() {
        synchronized(lock) {
            if (isStopped || isRunning.not()) {
                XLog.i(this@WebRtcProjection.getLog("forceKeyFrame", "Ignoring: isStopped=$isStopped, isRunning=$isRunning"))
                return
            }
            XLog.d(this@WebRtcProjection.getLog("forceKeyFrame"))
            screenCapturer?.apply {
                val screeSize = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(serviceContext).bounds
                changeCaptureFormat(screeSize.width() - 1, screeSize.height() - 1)
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
            deviceAudioRecoveryUsed = false
            reusableDeviceAudioBuffer = null

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
    private fun createAudioRecord(audioFormat: Int, sampleRate: Int, mediaProjection: MediaProjection): AudioRecord? {
        val format = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        }.onFailure { e -> XLog.e(getLog("createAudioRecord", "Cannot create AudioRecord"), e) }.getOrNull() ?: return null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            XLog.w(getLog("createAudioRecord", "AudioRecord not initialized."))
            record.release()
            return null
        }

        return record
    }

    private fun notifyDeviceAudioUnavailable() = mainHandler.post {
        Toast.makeText(serviceContext, R.string.webrtc_stream_audio_capture_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun recoverOrDisableDeviceAudio(record: AudioRecord?, message: String, cause: Throwable? = null) {
        val shouldRetry = synchronized(lock) {
            if (record != null) {
                runCatching { record.release() }.onFailure {
                    XLog.w(getLog("onAudioDataRecorded", "AudioRecord.release failed"), it)
                }
                if (deviceAudioRecord === record) deviceAudioRecord = null
            }

            if (deviceAudioRecoveryUsed) false
            else {
                deviceAudioRecoveryUsed = true
                true
            }
        }
        if (shouldRetry) {
            val details = cause?.message?.let { " Cause: $it" } ?: ""
            XLog.w(getLog("onAudioDataRecorded", "$message Retrying AudioRecord recreation once.$details"))
            return
        }

        if (cause == null) XLog.w(getLog("onAudioDataRecorded", "$message Disabling device audio."), IllegalStateException(message))
        else XLog.w(getLog("onAudioDataRecorded", "$message Disabling device audio."), cause)
        setDeviceAudioMute(true)
        notifyDeviceAudioUnavailable()
    }

    private fun obtainDeviceAudioBuffer(capacity: Int): ByteBuffer = synchronized(lock) {
        val buffer = reusableDeviceAudioBuffer
        val reusable = if (buffer != null && buffer.capacity() >= capacity) {
            buffer
        } else {
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder()).also { reusableDeviceAudioBuffer = it }
        }
        reusable.clear()
        reusable.limit(capacity)
        reusable
    }

    private fun mixPcm16InPlace(micBuffer: ByteBuffer, deviceBuffer: ByteBuffer, bytesToMix: Int) {
        val bytes = min(bytesToMix, min(micBuffer.limit(), deviceBuffer.limit())) and 0xFFFE
        if (bytes <= 0) return

        micBuffer.order(ByteOrder.LITTLE_ENDIAN)
        deviceBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until bytes step 2) {
            val mixed = (micBuffer.getShort(index).toInt() + deviceBuffer.getShort(index).toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            micBuffer.putShort(index, mixed.toShort())
        }
    }
}
