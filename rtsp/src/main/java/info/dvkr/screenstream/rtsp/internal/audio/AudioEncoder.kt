package info.dvkr.screenstream.rtsp.internal.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.Codec
import info.dvkr.screenstream.rtsp.internal.MasterClock
import info.dvkr.screenstream.rtsp.internal.MediaFrame
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ArrayBlockingQueue

internal class AudioEncoder(
    private val codecInfo: AudioCodecInfo,
    private val onAudioInfo: (AudioSource.Params) -> Unit,
    private val onAudioFrame: (MediaFrame.AudioFrame) -> Unit,
    private val onAudioCaptureError: (Throwable) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private enum class State { IDLE, PREPARED, RUNNING, STOPPED }

    private val encoderLock = Any()
    private var currentState = State.IDLE
        set(value) {
            field = value
            XLog.v(getLog("currentState", "State changed to: $value"))
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
        runCatching {
            synchronized(encoderLock) {
                check(currentState == State.IDLE)

                val onAudioSourceFrame: (AudioSource.Frame) -> Unit = { audioFrame ->
                    synchronized(encoderLock) {
                        if (currentState != State.RUNNING) return@synchronized
                        if (!frameQueue.offer(audioFrame)) {
                            XLog.w(getLog("start", "Audio frame queue is full. Dropping frame."))
                        }
                    }
                }

                this.audioSource = when {
                    enableMic && enableDeviceAudio ->
                        MixAudioSource(audioParams, audioSource, mediaProjection, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    enableMic ->
                        MicrophoneSource(audioParams, audioSource, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    enableDeviceAudio ->
                        InternalAudioSource(audioParams, mediaProjection, dispatcher, onAudioSourceFrame, onAudioCaptureError)

                    else -> null
                }

                if (this.audioSource != null) {
                    try {
                        this.audioSource!!.checkIfConfigurationSupported()
                    } catch (cause: Throwable) {
                        cleanupAfterPrepareFailure()
                        onAudioCaptureError(cause)
                        return
                    }
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
            cleanupAfterPrepareFailure()
            onError(cause)
        }
    }

    private fun cleanupAfterPrepareFailure(): Unit = synchronized(encoderLock) {
        runCatching { audioSource?.stop() }
        audioSource = null

        g711Codec?.stopEncoding()
        g711Codec = null

        audioEncoder?.apply {
            runCatching {
                stop()
                release()
            }.onFailure {
                XLog.w(this@AudioEncoder.getLog("cleanupAfterPrepareFailure", "mediaCodec.stop() exception: ${it.message}"), it)
            }
        }
        audioEncoder = null

        handler?.removeCallbacksAndMessages(null)
        handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                quit()
                XLog.w(this@AudioEncoder.getLog("cleanupAfterPrepareFailure", "handlerThread.join() took too long, forcing a shutdown"))
            }
        }
        handlerThread = null
        handler = null

        frameQueue.clear()
        currentState = State.IDLE
    }

    internal fun start(): Unit = synchronized(encoderLock) {
        if (audioSource == null) {
            XLog.i(getLog("start", "No audioSource. Ignoring"))
            return
        }
        XLog.v(getLog("start"))

        if (currentState != State.PREPARED) {
            XLog.w(getLog("start", "Cannot start unless state is PREPARED. Current: $currentState"))
            return
        }

        g711Codec?.startEncoding(handler!!)
        audioEncoder?.start()
        audioSource!!.start()

        currentState = State.RUNNING

        XLog.v(getLog("start", "Done"))
    }

    internal fun stop(): Unit = synchronized(encoderLock) {
        if (audioSource == null) {
            XLog.i(getLog("stop", "No audioSource. Ignoring"))
            return
        }
        XLog.v(getLog("stop"))

        if (currentState == State.IDLE || currentState == State.STOPPED) {
            XLog.w(getLog("stop", "Cannot stop unless state is RUNNING. Current: $currentState"))
            return
        }
        currentState = State.STOPPED

        audioSource!!.stop()
        audioSource = null

        // G711
        g711Codec?.stopEncoding()
        g711Codec = null

        audioEncoder?.apply {
            runCatching {
                stop()
                release()
            }.onFailure {
                XLog.w(this@AudioEncoder.getLog("stopInternal", "mediaCodec.stop() exception: ${it.message}"), it)
            }
        }
        audioEncoder = null

        handler?.removeCallbacksAndMessages(null)
        handlerThread?.apply {
            quitSafely()
            runCatching { join(250) }.onFailure {
                XLog.w(this@AudioEncoder.getLog("stopInternal", "handlerThread.join() interrupted"), it)
            }
            if (isAlive) {
                XLog.w(this@AudioEncoder.getLog("stopInternal", "handlerThread.join() took too long, forcing a shutdown"))
                quit()
                runCatching { join(250) }.onFailure {
                    XLog.w(this@AudioEncoder.getLog("stopInternal", "handlerThread forced join interrupted"), it)
                }
                if (isAlive) {
                    XLog.w(
                        this@AudioEncoder.getLog("stopInternal", "handlerThread did not stop after forced shutdown"),
                        IllegalStateException("AudioEncoder handlerThread still alive: name=$name, state=$state")
                    )
                }
            }
        }
        handlerThread = null
        handler = null

        frameQueue.clear()

        currentState = State.IDLE

        XLog.v(getLog("stop", "Done"))
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        XLog.v(getLog("setMute", "micMute: $micMute, deviceMute: $deviceMute"))
        when (audioSource) {
            is MicrophoneSource -> (audioSource as MicrophoneSource).setMute(micMute)
            is InternalAudioSource -> (audioSource as InternalAudioSource).setMute(deviceMute)
            is MixAudioSource -> (audioSource as MixAudioSource).setMute(micMute, deviceMute)
        }
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        XLog.v(getLog("setVolume", "micVolume: $micVolume, deviceVolume: $deviceVolume"))
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
                XLog.w(this@AudioEncoder.getLog("CodecCallback.onInputBufferAvailable", "onFailure: ${cause.message}"), cause)
                onError(cause)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            runCatching {
                val audioFrame = synchronized(encoderLock) {
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
                XLog.w(this@AudioEncoder.getLog("CodecCallback.onOutputBufferAvailable", "onFailure: ${cause.message}"), cause)
                runCatching { codec.releaseOutputBuffer(index, false) }
                onError(cause)
            }
        }

        override fun onError(codec: MediaCodec, cause: MediaCodec.CodecException) {
            XLog.w(this@AudioEncoder.getLog("CodecCallback.onError", "onFailure: ${cause.message}"), cause)
            onError(cause)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            XLog.i(this@AudioEncoder.getLog("CodecCallback.onOutputFormatChanged", "[Not handled] codec: $codec, format: $format"))
        }
    }
}
