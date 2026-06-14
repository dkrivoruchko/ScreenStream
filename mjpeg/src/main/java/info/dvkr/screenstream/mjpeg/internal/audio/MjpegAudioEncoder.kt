package info.dvkr.screenstream.mjpeg.internal.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ArrayBlockingQueue

internal class MjpegAudioEncoder(
    private val codecInfo: MjpegAudioEncoderInfo,
    private val onAudioPacket: (EncodedAudioPacket) -> Unit,
    private val onAudioCaptureError: (Throwable) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private enum class State { IDLE, PREPARED, RUNNING, STOPPED }

    private data class StopSnapshot(
        val audioSource: MjpegAudioSource?,
        val audioEncoder: MediaCodec?,
        val handler: Handler?,
        val handlerThread: HandlerThread?
    )

    private val encoderLock = Any()
    private var currentState = State.IDLE
        set(value) {
            field = value
            XLog.v(getLog("currentState", "State changed to: $value"))
        }

    private var audioSource: MjpegAudioSource? = null
    private var audioEncoder: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val frameQueue = ArrayBlockingQueue<MjpegAudioSource.Frame>(16)
    private val durationSamplesQueue = ArrayBlockingQueue<Int>(16)
    private var channelCount: Int = 2

    internal val isCapturing: Boolean
        get() = synchronized(encoderLock) { audioSource?.isRunning == true }

    internal fun prepare(
        enableMic: Boolean,
        enableDeviceAudio: Boolean,
        dispatcher: CoroutineDispatcher,
        audioParams: MjpegAudioSource.Params,
        mediaProjection: MediaProjection?,
    ) {
        var audioSourceConfigurationError: Throwable? = null
        runCatching {
            synchronized(encoderLock) {
                check(currentState == State.IDLE)
                channelCount = if (audioParams.isStereo) 2 else 1

                val onAudioSourceFrame: (MjpegAudioSource.Frame) -> Unit = { audioFrame ->
                    synchronized(encoderLock) {
                        if (currentState != State.RUNNING) return@synchronized
                        if (!frameQueue.offer(audioFrame)) {
                            XLog.w(getLog("start", "Audio frame queue is full. Dropping frame."))
                        }
                    }
                }

                audioSource = when {
                    enableMic && enableDeviceAudio -> {
                        val projection = requireNotNull(mediaProjection) { "MediaProjection is required for internal audio capture" }
                        MjpegMixAudioSource(audioParams, MediaRecorder.AudioSource.DEFAULT, projection, dispatcher, onAudioSourceFrame, onAudioCaptureError)
                    }

                    enableMic ->
                        MjpegMicrophoneSource(audioParams, MediaRecorder.AudioSource.DEFAULT, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    enableDeviceAudio -> {
                        val projection = requireNotNull(mediaProjection) { "MediaProjection is required for internal audio capture" }
                        MjpegInternalAudioSource(audioParams, projection, dispatcher, onAudioSourceFrame, onAudioCaptureError)
                    }

                    else -> null
                }

                audioSource?.let {
                    audioSourceConfigurationError = runCatching { it.checkIfConfigurationSupported() }.exceptionOrNull()
                    if (audioSourceConfigurationError != null) return@synchronized
                }

                if (audioSource == null) return

                val format = MediaFormat.createAudioFormat(OPUS_MIME_TYPE, audioParams.sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, audioParams.bitrate)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, audioParams.sampleRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioParams.calculateBufferSizeInBytes() + 64)
                    setInteger(MediaFormat.KEY_PRIORITY, 1)
                    if (codecInfo.isCBRModeSupported) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    } else {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }

                val encoder = MediaCodec.createByCodecName(codecInfo.name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                handlerThread = HandlerThread("MjpegAudioEncoderHandler", Process.THREAD_PRIORITY_AUDIO).apply { start() }
                handler = Handler(handlerThread!!.looper)
                encoder.setCallback(createCodecCallback(), handler)

                audioEncoder = encoder
                currentState = State.PREPARED
            }
        }.onFailure { cause ->
            stopInternal(force = true, logTag = "cleanupAfterPrepareFailure")
            onError(cause)
        }

        val configurationError = audioSourceConfigurationError ?: return
        stopInternal(force = true, logTag = "cleanupAfterPrepareFailure")
        onAudioCaptureError(configurationError)
    }

    internal fun start(): Unit = synchronized(encoderLock) {
        if (audioSource == null) {
            XLog.i(getLog("start", "No audioSource. Ignoring"))
            return
        }
        if (currentState != State.PREPARED) {
            XLog.w(getLog("start", "Cannot start unless state is PREPARED. Current: $currentState"))
            return
        }

        audioEncoder?.start()
        audioSource!!.start()
        currentState = State.RUNNING
    }

    internal fun stop() {
        if (stopInternal(force = false, logTag = "stopInternal").not()) return
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        when (val source = audioSource) {
            is MjpegMicrophoneSource -> source.setMute(micMute)
            is MjpegInternalAudioSource -> source.setMute(deviceMute)
            is MjpegMixAudioSource -> source.setMute(micMute, deviceMute)
        }
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        when (val source = audioSource) {
            is MjpegMicrophoneSource -> source.volume = micVolume
            is MjpegInternalAudioSource -> source.volume = deviceVolume
            is MjpegMixAudioSource -> source.setVolume(micVolume, deviceVolume)
        }
    }

    private fun stopInternal(force: Boolean, logTag: String): Boolean {
        val snapshot = synchronized(encoderLock) {
            if (audioSource == null && !force) return@synchronized null
            if (!force && (currentState == State.IDLE || currentState == State.STOPPED)) return@synchronized null

            currentState = State.STOPPED

            StopSnapshot(audioSource, audioEncoder, handler, handlerThread).also {
                audioSource = null
                audioEncoder = null
                handler = null
                handlerThread = null
            }
        } ?: return false

        runCatching { snapshot.audioSource?.stop() }

        snapshot.audioEncoder?.runCatching {
            stop()
            release()
        }?.onFailure {
            XLog.w(getLog(logTag, "mediaCodec.stop() exception: ${it.message}"), it)
        }

        snapshot.handler?.removeCallbacksAndMessages(null)
        snapshot.handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                XLog.w(getLog(logTag, "handlerThread.join() interrupted"), it)
            }
            if (isAlive) {
                quit()
                runCatching { join(250) }
            }
        }

        frameQueue.clear()
        durationSamplesQueue.clear()

        synchronized(encoderLock) {
            currentState = State.IDLE
        }
        return true
    }

    private fun isCallbackCodecActive(codec: MediaCodec): Boolean = synchronized(encoderLock) {
        codec === audioEncoder && currentState == State.RUNNING
    }

    private fun createCodecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            runCatching {
                synchronized(encoderLock) {
                    if (codec !== audioEncoder) return
                    if (currentState != State.RUNNING) {
                        runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
                        return
                    }

                    val frame = frameQueue.poll() ?: run {
                        runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
                        return
                    }

                    val inputBuffer = codec.getInputBuffer(index) ?: run {
                        runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
                        return
                    }

                    inputBuffer.clear()
                    if (inputBuffer.remaining() < frame.size) {
                        runCatching { codec.queueInputBuffer(index, 0, 0, 0, 0) }
                        throw IllegalArgumentException("Frame too large for input buffer")
                    }

                    inputBuffer.put(frame.buffer, 0, frame.size)
                    val samplesPerChannel = (frame.size / 2 / channelCount).coerceAtLeast(1)
                    durationSamplesQueue.offer(samplesPerChannel)
                    codec.queueInputBuffer(index, 0, frame.size, frame.timestampUs, 0)
                }
            }.onFailure { cause ->
                if (!isCallbackCodecActive(codec)) return@onFailure
                XLog.w(getLog("CodecCallback.onInputBufferAvailable", "onFailure: ${cause.message}"), cause)
                onError(cause)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val packet = synchronized(encoderLock) {
                    if (codec !== audioEncoder || currentState != State.RUNNING || info.size == 0 ||
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    ) {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        return
                    }

                    val outputBuffer = codec.getOutputBuffer(index) ?: run {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                        return
                    }

                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    outputBuffer.get(data)
                    val durationSamples = durationSamplesQueue.poll() ?: 960

                    codec.releaseOutputBuffer(index, false)
                    EncodedAudioPacket(data, durationSamples)
                }

                onAudioPacket(packet)
            }.onFailure { cause ->
                runCatching { codec.releaseOutputBuffer(index, false) }
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

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!isCallbackCodecActive(codec)) return
            XLog.i(getLog("CodecCallback.onOutputFormatChanged", "codec: $codec, format: $format"))
        }
    }
}
