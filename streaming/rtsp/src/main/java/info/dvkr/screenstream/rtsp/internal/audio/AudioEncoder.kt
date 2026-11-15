package info.dvkr.screenstream.rtsp.internal.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ArrayBlockingQueue

internal class AudioEncoder(
    private val log: Logger,
    private val codecInfo: AudioCodecInfo,
    private val onAudioInfo: (AudioSource.Params) -> Unit,
    private val onAudioFrame: (MediaFrame.AudioFrame) -> Unit,
    private val onAudioCaptureError: (Throwable) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private enum class State { IDLE, PREPARED, RUNNING, STOPPED }

    private data class StopSnapshot(
        val audioSource: AudioSource?,
        val g711Codec: G711Codec?,
        val audioEncoder: MediaCodec?,
        val handler: Handler?,
        val handlerThread: HandlerThread?
    )

    private val encoderLock = Any()
    private var currentState = State.IDLE
        set(value) {
            field = value
            log.v("AudioEncoder.currentState", "State changed to: $value")
        }

    private var audioSource: AudioSource? = null
    private var g711Codec: G711Codec? = null
    private var audioEncoder: MediaCodec? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val frameQueue = ArrayBlockingQueue<AudioSource.Frame>(16)

    internal fun prepare(
        enableMic: Boolean,
        enableDeviceAudio: Boolean,
        dispatcher: CoroutineDispatcher,
        audioParams: AudioSource.Params,
        audioSource: Int,
        mediaProjection: MediaProjection,
    ) {
        var audioSourceConfigurationError: Throwable? = null
        runCatching {
            synchronized(encoderLock) {
                check(currentState == State.IDLE)

                val onAudioSourceFrame: (AudioSource.Frame) -> Unit = { audioFrame ->
                    synchronized(encoderLock) {
                        if (currentState != State.RUNNING) return@synchronized
                        if (!frameQueue.offer(audioFrame)) {
                            log.w("AudioEncoder.start", "Audio frame queue is full. Dropping frame.")
                        }
                    }
                }

                this.audioSource = when {
                    enableMic && enableDeviceAudio ->
                        MixAudioSource(log, audioParams, audioSource, mediaProjection, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    enableMic ->
                        MicrophoneSource(log, audioParams, audioSource, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    enableDeviceAudio ->
                        InternalAudioSource(log, audioParams, mediaProjection, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    else -> null
                }

                if (this.audioSource != null) {
                    audioSourceConfigurationError = runCatching { this.audioSource!!.checkIfConfigurationSupported() }.exceptionOrNull()
                    if (audioSourceConfigurationError != null) return@synchronized
                }

                if (this.audioSource == null) return

                if (codecInfo.codec == Codec.Audio.G711) {
                    g711Codec = G711Codec(
                        getAudioFrame = { frameQueue.poll() },
                        onAudioData = this@AudioEncoder.onAudioFrame,
                        onError = onError
                    ).apply {
                        prepare(audioParams.sampleRate, audioParams.isStereo)
                    }
                    handlerThread = HandlerThread("AudioEncoderHandler", Process.THREAD_PRIORITY_AUDIO).apply { start() }
                    handler = Handler(handlerThread!!.looper)

                    currentState = State.PREPARED
                    onAudioInfo(audioParams)
                    return
                }

                // OPUS, AAC
                val channelCount = if (audioParams.isStereo) 2 else 1
                val format = MediaFormat.createAudioFormat(codecInfo.codec.mimeType, audioParams.sampleRate, channelCount).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, audioParams.bitrate)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, audioParams.sampleRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioParams.calculateBufferSizeInBytes() + 64)
                    setInteger(MediaFormat.KEY_PRIORITY, 1)

                    if (codecInfo.codec == Codec.Audio.AAC) {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }

                    if (codecInfo.isCBRModeSupported) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    } else {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }

                val encoder = MediaCodec.createByCodecName(codecInfo.name)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                handlerThread = HandlerThread("AudioEncoderHandler", Process.THREAD_PRIORITY_AUDIO).apply { start() }
                handler = Handler(handlerThread!!.looper)
                encoder.setCallback(createCodecCallback(), handler)

                audioEncoder = encoder
                currentState = State.PREPARED
                onAudioInfo(audioParams)
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
            log.i("AudioEncoder.start", "No audioSource. Ignoring")
            return
        }
        log.v("AudioEncoder.start")

        if (currentState != State.PREPARED) {
            log.w("AudioEncoder.start", "Cannot start unless state is PREPARED. Current: $currentState")
            return
        }

        g711Codec?.startEncoding(handler!!)
        audioEncoder?.start()
        audioSource!!.start()

        currentState = State.RUNNING

        log.v("AudioEncoder.start", "Done")
    }

    internal fun stop() {
        log.v("AudioEncoder.stop")
        if (stopInternal(force = false, logTag = "stopInternal").not()) return
        log.v("AudioEncoder.stop", "Done")
    }

    private fun stopInternal(force: Boolean, logTag: String): Boolean {
        val snapshot = synchronized(encoderLock) {
            if (audioSource == null && !force) {
                log.i("AudioEncoder.stop", "No audioSource. Ignoring")
                return@synchronized null
            }
            if (!force && (currentState == State.IDLE || currentState == State.STOPPED)) {
                log.w("AudioEncoder.stop", "Cannot stop unless state is RUNNING. Current: $currentState")
                return@synchronized null
            }

            currentState = State.STOPPED

            StopSnapshot(audioSource, g711Codec, audioEncoder, handler, handlerThread).also {
                audioSource = null
                g711Codec = null
                audioEncoder = null
                handler = null
                handlerThread = null
            }
        } ?: return false

        runCatching { snapshot.audioSource?.stop() }

        snapshot.g711Codec?.stopEncoding()

        snapshot.audioEncoder?.runCatching {
            stop()
            release()
        }?.onFailure {
            log.w("AudioEncoder.$logTag", "Codec stop failed", it)
        }

        snapshot.handler?.removeCallbacksAndMessages(null)

        snapshot.handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                log.w("AudioEncoder.$logTag", "handlerThread.join() interrupted", it)
            }
            if (isAlive) {
                log.w("AudioEncoder.$logTag", "handlerThread.join() took too long, forcing a shutdown")
                quit()
                runCatching { join(250) }.onFailure {
                    log.w("AudioEncoder.$logTag", "handlerThread forced join interrupted", it)
                }
                if (isAlive) {
                    log.w("AudioEncoder.$logTag", "Worker still alive after forced shutdown: name=$name, state=$state")
                }
            }
        }

        frameQueue.clear()

        synchronized(encoderLock) {
            currentState = State.IDLE
        }
        return true
    }

    private fun isCallbackCodecActive(codec: MediaCodec): Boolean = synchronized(encoderLock) {
        codec === audioEncoder && currentState == State.RUNNING
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        log.v("AudioEncoder.setMute", "micMute: $micMute, deviceMute: $deviceMute")
        when (audioSource) {
            is MicrophoneSource -> (audioSource as MicrophoneSource).setMute(micMute)
            is InternalAudioSource -> (audioSource as InternalAudioSource).setMute(deviceMute)
            is MixAudioSource -> (audioSource as MixAudioSource).setMute(micMute, deviceMute)
        }
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        log.v("AudioEncoder.setVolume", "micVolume: $micVolume, deviceVolume: $deviceVolume")
        when (audioSource) {
            is MicrophoneSource -> (audioSource as MicrophoneSource).volume = micVolume
            is InternalAudioSource -> (audioSource as InternalAudioSource).volume = deviceVolume
            is MixAudioSource -> (audioSource as MixAudioSource).setVolume(micVolume, deviceVolume)
        }
    }

    private fun createCodecCallback(): MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            runCatching {
                synchronized(encoderLock) {
                    val activeCodec = audioEncoder
                    if (codec !== activeCodec) return

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
                    codec.queueInputBuffer(index, 0, frame.size, frame.timestampUs, 0)
                }
            }.onFailure { cause ->
                if (!isCallbackCodecActive(codec)) {
                    log.v(
                        "AudioEncoder.CodecCallback.onInputBufferAvailable",
                        "Ignoring stale codec callback failure"
                    )
                    return@onFailure
                }

                log.w("AudioEncoder.CodecCallback.onInputBufferAvailable", "Codec callback failed", cause)
                if (isCallbackCodecActive(codec)) {
                    onError(cause)
                } else {
                    log.v("AudioEncoder.CodecCallback.onInputBufferAvailable", "Ignoring callback failure after stop")
                }
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val audioFrame = synchronized(encoderLock) {
                    val activeCodec = audioEncoder
                    if (codec !== activeCodec || currentState != State.RUNNING || info.size == 0) {
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

                    MediaFrame.AudioFrame(
                        data = outputBuffer,
                        info = MediaFrame.Info(
                            offset = adjustedInfo.offset,
                            size = adjustedInfo.size,
                            timestamp = adjustedInfo.presentationTimeUs,
                            isKeyFrame = false
                        ),
                        releaseCallback = { runCatching { codec.releaseOutputBuffer(index, false) } }
                    )
                }

                onAudioFrame(audioFrame)
            }.onFailure { cause ->
                if (!isCallbackCodecActive(codec)) {
                    log.v(
                        "AudioEncoder.CodecCallback.onOutputBufferAvailable",
                        "Ignoring stale codec callback failure"
                    )
                    runCatching { codec.releaseOutputBuffer(index, false) }
                    return@onFailure
                }

                log.w("AudioEncoder.CodecCallback.onOutputBufferAvailable", "Codec callback failed", cause)
                runCatching { codec.releaseOutputBuffer(index, false) }
                if (isCallbackCodecActive(codec)) {
                    onError(cause)
                } else {
                    log.v("AudioEncoder.CodecCallback.onOutputBufferAvailable", "Ignoring callback failure after stop")
                }
            }
        }

        override fun onError(codec: MediaCodec, cause: MediaCodec.CodecException) {
            if (!isCallbackCodecActive(codec)) {
                log.v("AudioEncoder.CodecCallback.onError", "Ignoring stale codec error")
                return
            }
            log.w("AudioEncoder.CodecCallback.onError", "Codec callback failed", cause)
            onError(cause)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (!isCallbackCodecActive(codec)) return
            log.i("AudioEncoder.CodecCallback.onOutputFormatChanged", "Output format changed; ignoring")
        }
    }
}
