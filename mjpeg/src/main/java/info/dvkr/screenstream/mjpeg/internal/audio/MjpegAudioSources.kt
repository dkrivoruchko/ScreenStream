package info.dvkr.screenstream.mjpeg.internal.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class MjpegMicrophoneSource(
    audioParams: MjpegAudioSource.Params,
    private val audioSource: Int,
    dispatcher: CoroutineDispatcher,
    onAudioFrame: (MjpegAudioSource.Frame) -> Unit,
    onCaptureError: (Throwable) -> Unit
) : MjpegAudioSource {

    private val audioCapture = MjpegAudioCapture(audioParams, dispatcher, onAudioFrame, onCaptureError)

    override val isRunning: Boolean
        get() = audioCapture.isRunning()

    var volume: Float
        get() = audioCapture.volume
        set(value) {
            audioCapture.volume = value
        }

    @Throws
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun checkIfConfigurationSupported() = audioCapture.checkIfConfigurationSupported(audioSource)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = audioCapture.start(audioSource)

    override fun stop() = audioCapture.stop()

    internal fun setMute(mute: Boolean) = audioCapture.setMuted(mute)
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class MjpegInternalAudioSource(
    audioParams: MjpegAudioSource.Params,
    private val mediaProjection: MediaProjection,
    dispatcher: CoroutineDispatcher,
    onAudioFrame: (MjpegAudioSource.Frame) -> Unit,
    onCaptureError: (Throwable) -> Unit
) : MjpegAudioSource {

    private val audioCapture = MjpegAudioCapture(audioParams, dispatcher, onAudioFrame, onCaptureError)

    override val isRunning: Boolean
        get() = audioCapture.isRunning()

    var volume: Float
        get() = audioCapture.volume
        set(value) {
            audioCapture.volume = value
        }

    @Throws
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun checkIfConfigurationSupported() {
        audioCapture.checkIfConfigurationSupported(createConfig())
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = audioCapture.start(createConfig())

    override fun stop() = audioCapture.stop()

    internal fun setMute(mute: Boolean) = audioCapture.setMuted(mute)

    private fun createConfig(): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class MjpegMixAudioSource(
    audioParams: MjpegAudioSource.Params,
    audioSource: Int,
    mediaProjection: MediaProjection,
    private val dispatcher: CoroutineDispatcher,
    private val onAudioFrame: (MjpegAudioSource.Frame) -> Unit,
    private val onCaptureError: (Throwable) -> Unit,
    private val micMixFactor: Float = 1.0f,
    private val intMixFactor: Float = 0.25f,
) : MjpegAudioSource {

    private val microphone = MjpegMicrophoneSource(audioParams, audioSource, dispatcher, onAudioFrame = { pushToRing(it, micRing) }, onCaptureError)
    private val internal = MjpegInternalAudioSource(audioParams, mediaProjection, dispatcher, onAudioFrame = { pushToRing(it, intRing) }, onCaptureError)

    private val channels = if (audioParams.isStereo) 2 else 1
    private val chunkMs = 20
    private val chunkSamplesPerChannel = (audioParams.sampleRate * chunkMs) / 1000
    private val chunkSamples = chunkSamplesPerChannel * channels
    private val chunkBytes = chunkSamples * 2
    private val chunkDurationUs = (chunkSamplesPerChannel * 1_000_000L) / audioParams.sampleRate

    private val micRing = ShortRing(chunkSamples * 10)
    private val intRing = ShortRing(chunkSamples * 10)
    private val limiterTarget = Short.MAX_VALUE * 0.98f

    @Volatile
    override var isRunning: Boolean = false
        private set

    private var scope: CoroutineScope? = null

    @Throws
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
        scope.launch {
            val micChunk = ShortArray(chunkSamples)
            val intChunk = ShortArray(chunkSamples)
            val outChunk = ShortArray(chunkSamples)
            val outBytes = ByteArray(chunkBytes)

            var nextPtsUs = MjpegMasterClock.relativeTimeUs()

            while (currentCoroutineContext().isActive && isRunning) {
                val micOk = micRing.read(micChunk, chunkSamples)
                val intOk = intRing.read(intChunk, chunkSamples)

                if (!micOk) micChunk.fill(0)
                if (!intOk) intChunk.fill(0)

                var peakSum = 0f
                for (i in 0 until chunkSamples) {
                    val mixedAbs = abs(micChunk[i] * micMixFactor + intChunk[i] * intMixFactor)
                    if (mixedAbs > peakSum) peakSum = mixedAbs
                }

                val scale = if (peakSum > limiterTarget) limiterTarget / peakSum else 1f

                for (i in 0 until chunkSamples) {
                    val mixedFloat = (micChunk[i] * micMixFactor + intChunk[i] * intMixFactor) * scale
                    outChunk[i] = when {
                        mixedFloat > Short.MAX_VALUE -> Short.MAX_VALUE
                        mixedFloat < Short.MIN_VALUE -> Short.MIN_VALUE
                        else -> mixedFloat.toInt().toShort()
                    }
                }

                ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outChunk)
                onAudioFrame(MjpegAudioSource.Frame(outBytes.clone(), outBytes.size, nextPtsUs))

                nextPtsUs += chunkDurationUs
                val sleepUs = nextPtsUs - MjpegMasterClock.relativeTimeUs()
                if (sleepUs > 0) delay((sleepUs / 1000L).coerceAtLeast(1L))
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (!isRunning) return
        isRunning = false

        microphone.stop()
        internal.stop()

        scope?.cancel()
        scope = null

        micRing.clear()
        intRing.clear()
    }

    internal fun setMute(micMute: Boolean, deviceMute: Boolean) {
        microphone.setMute(micMute)
        internal.setMute(deviceMute)
    }

    internal fun setVolume(micVolume: Float, deviceVolume: Float) {
        microphone.volume = micVolume
        internal.volume = deviceVolume
    }

    private fun pushToRing(frame: MjpegAudioSource.Frame, ring: ShortRing) {
        val sampleCount = frame.size / 2
        if (sampleCount <= 0) return
        val shortBuffer = ByteBuffer.wrap(frame.buffer, 0, sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val tmp = ShortArray(sampleCount)
        shortBuffer.get(tmp)
        ring.write(tmp, tmp.size)
    }

    private class ShortRing(capacitySamples: Int) {
        private val data = ShortArray(capacitySamples)
        private var head = 0
        private var size = 0

        @Synchronized
        fun write(src: ShortArray, count: Int) {
            var i = 0
            while (i < count) {
                if (size == data.size) {
                    head = (head + 1) % data.size
                    size--
                }
                val idx = (head + size) % data.size
                data[idx] = src[i]
                size++
                i++
            }
        }

        @Synchronized
        fun read(dst: ShortArray, count: Int): Boolean {
            if (size < count) return false
            var i = 0
            while (i < count) {
                dst[i] = data[head]
                head = (head + 1) % data.size
                size--
                i++
            }
            return true
        }

        @Synchronized
        fun clear() {
            head = 0
            size = 0
        }
    }
}
