package info.dvkr.screenstream.rtsp.internal.audio

import android.Manifest
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.MasterClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RequiresApi(Build.VERSION_CODES.Q)
internal class MixAudioSource(
    audioParams: AudioSource.Params,
    audioSource: Int,
    mediaProjection: MediaProjection,
    private val dispatcher: CoroutineDispatcher,
    private val onAudioFrame: (AudioSource.Frame) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val micMixFactor: Float = 1.6f,
    private val intMixFactor: Float = 0.4f,
) : AudioSource {

    private val micFrames = Channel<AudioSource.Frame>(Channel.UNLIMITED)
    private val microphone = MicrophoneSource(audioParams, audioSource, dispatcher, onAudioFrame = { micFrames.trySend(it) }, onError)

    private val intFrames = Channel<AudioSource.Frame>(Channel.UNLIMITED)
    private val internal = InternalAudioSource(audioParams, mediaProjection, dispatcher, onAudioFrame = { intFrames.trySend(it) }, onError)

    private val inputSize = audioParams.calculateBufferSizeInBytes()

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var scope: CoroutineScope? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun checkIfConfigurationSupported() {
        microphone.checkIfConfigurationSupported()
        internal.checkIfConfigurationSupported()
    }

    @Synchronized
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        if (isRunning) return
        isRunning = true

        microphone.start()
        internal.start()

        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scope = it }

        flow {
            val micBuffer = ByteArray(inputSize)
            val intBuffer = ByteArray(inputSize)
            val mixedBuffer = ByteArray(inputSize)

            while (currentCoroutineContext().isActive && isRunning) {
                val micFrame = withTimeoutOrNull(60) { micFrames.receive() }
                val intFrame = withTimeoutOrNull(60) { intFrames.receive() }

                micBuffer.fill(0)
                intBuffer.fill(0)
                micFrame?.buffer?.copyInto(micBuffer, endIndex = minOf(micFrame.size, inputSize))
                intFrame?.buffer?.copyInto(intBuffer, endIndex = minOf(intFrame.size, inputSize))

                val timeStamp = intFrame?.timestampUs ?: micFrame?.timestampUs ?: MasterClock.relativeTimeUs()

                val micShortBuffer = ByteBuffer.wrap(micBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val intShortBuffer = ByteBuffer.wrap(intBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val mixedShortBuffer = ByteBuffer.wrap(mixedBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                for (i in 0 until inputSize / 2) {
                    val micSample = micShortBuffer.get(i).toInt()
                    val intSample = intShortBuffer.get(i).toInt()

                    val mixedFloat = when {
                        micFrame != null && intFrame != null -> micSample * micMixFactor + intSample * intMixFactor
                        micFrame != null -> micSample * micMixFactor
                        intFrame != null -> intSample * intMixFactor
                        else -> 0f
                    }

                    val clamped = when {
                        mixedFloat > Short.MAX_VALUE -> Short.MAX_VALUE
                        mixedFloat < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> mixedFloat.toInt().toShort()
                    }

                    mixedShortBuffer.put(i, clamped)
                }
                emit(AudioSource.Frame(mixedBuffer.clone(), mixedBuffer.size, timeStamp))
            }
        }
            .onEach { frame -> onAudioFrame(frame) }
            .catch { XLog.w(getLog("start.catch", it.message), it) }
            .launchIn(scope)
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false

        microphone.stop()
        internal.stop()

        scope?.cancel()
        scope = null

        while (true) {
            if (micFrames.tryReceive().isFailure) break
        }
        while (true) {
            if (intFrames.tryReceive().isFailure) break
        }
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        microphone.setMute(micMute)
        internal.setMute(deviceMute)
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        microphone.volume = micVolume
        internal.volume = deviceVolume
    }
}