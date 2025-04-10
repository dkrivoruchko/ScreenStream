package info.dvkr.screenstream.rtsp.internal.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.rtsp.internal.MasterClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AudioCapture(
    private val audioParams: AudioSource.Params,
    private val dispatcher: CoroutineDispatcher,
    private val onAudioFrame: (AudioSource.Frame) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var scope: CoroutineScope? = null

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var _isRunning: Boolean = false
    private var _isMuted: Boolean = false

    @get:Synchronized
    @set:Synchronized
    var micVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 2f)
        }

    @Synchronized
    fun isRunning(): Boolean = _isRunning

    @Synchronized
    fun isMuted(): Boolean = _isMuted

    @Synchronized
    fun mute() {
        _isMuted = true
    }

    @Synchronized
    fun unMute() {
        _isMuted = false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun checkIfConfigurationSupported(audioSource: Int = MediaRecorder.AudioSource.DEFAULT) {
        val channelConfig = if (audioParams.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBufferSize = AudioRecord.getMinBufferSize(audioParams.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalArgumentException("Invalid audio parameters (sample rate or channel not supported).")
        }

        var testRecord: AudioRecord? = null
        try {
            testRecord = AudioRecord(audioSource, audioParams.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, minBufferSize)
            if (testRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalArgumentException("Failed to initialize AudioRecord with given parameters.")
            }
        } catch (e: Throwable) {
            onError(e)
        } finally {
            testRecord?.release()
        }
    }

    @Synchronized
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(audioSource: Int) {
        try {
            check(!_isRunning) { "Audio capture is already running." }

            val bufferSizeInBytes = audioParams.calculateBufferSizeInBytes()

            val record = AudioRecord(
                audioSource,
                audioParams.sampleRate,
                if (audioParams.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeInBytes
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalArgumentException("Failed to initialize AudioRecord with given parameters.")
            }

            audioRecord = record
            initializeAudioEffects(record.audioSessionId, audioParams)

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalArgumentException("Failed to start recording AudioRecord with given parameters.")
            }
            _isRunning = true

            scope = CoroutineScope(Job() + dispatcher).apply {
                launch { readAudioLoop(record, bufferSizeInBytes) }
            }
        } catch (cause: Exception) {
            XLog.w(getLog("start", "Failed to start audio capture: ${cause.message}"), cause)
            onError(cause)
        }
    }

    @Synchronized
    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(config: AudioPlaybackCaptureConfiguration) {
        try {
            check(!_isRunning) { "Audio capture is already running." }

            val bufferSizeInBytes = audioParams.calculateBufferSizeInBytes()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(audioParams.sampleRate)
                        .setChannelMask(if (audioParams.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeInBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalArgumentException("Failed to initialize AudioRecord for internal capture.")
            }

            audioRecord = record
            initializeAudioEffects(record.audioSessionId, audioParams)

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalArgumentException("Failed to start recording AudioRecord with given parameters.")
            }
            _isRunning = true

            scope = CoroutineScope(Job() + dispatcher).apply {
                launch { readAudioLoop(record, bufferSizeInBytes) }
            }
        } catch (cause: Exception) {
            XLog.w(getLog("start", "Failed to start audio capture: ${cause.message}"), cause)
            onError(cause)
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning) return
        _isRunning = false

        scope?.cancel()
        scope = null

        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null

        releaseAudioEffects()
    }

    private suspend fun readAudioLoop(record: AudioRecord, bufferSizeInBytes: Int) = runCatching {
        val pcmBuffer = ByteArray(bufferSizeInBytes)
        val byteBuffer = ByteBuffer.wrap(pcmBuffer).order(ByteOrder.LITTLE_ENDIAN)
        val shortBuffer = byteBuffer.asShortBuffer()

        while (currentCoroutineContext().isActive) {
            if (!isRunning()) break
            val size = record.read(pcmBuffer, 0, pcmBuffer.size)
            when {
                size > 0 -> {
                    if (size % 2 != 0) {
                        XLog.w(getLog("readAudioLoop", "Read size is not even ($size), skipping frame."))
                        continue
                    }

                    when {
                        isMuted() -> pcmBuffer.fill(0, 0, size)
                        micVolume != 1.0f -> {
                            byteBuffer.clear()
                            shortBuffer.clear()
                            shortBuffer.limit(size / 2)
                            for (i in 0 until (size / 2)) {
                                val sample = shortBuffer.get(i)
                                val scaled = (sample * micVolume).toInt()
                                when {
                                    scaled > Short.MAX_VALUE -> shortBuffer.put(i, Short.MAX_VALUE)
                                    scaled < Short.MIN_VALUE -> shortBuffer.put(i, Short.MIN_VALUE)
                                    else -> shortBuffer.put(i, scaled.toShort())
                                }
                            }
                        }
                    }

                    val frameBuffer = ByteArray(size)
                    System.arraycopy(pcmBuffer, 0, frameBuffer, 0, size)
                    onAudioFrame(AudioSource.Frame(frameBuffer, size, MasterClock.relativeTimeUs()))
                }

                size < 0 -> {
                    XLog.w(getLog("readAudioLoop", "Read size is negative, skipping frame."))
                }
            }
        }
    }.onFailure { cause ->
        if (cause is CancellationException) throw cause
        XLog.w(getLog("readAudioLoop", "Failed to read audio: ${cause.message}"), cause)
        onError(cause)
    }

    @Throws(Exception::class)
    private fun initializeAudioEffects(audioSessionId: Int, audioParams: AudioSource.Params) {
        try {
            enableEchoCanceler(audioSessionId, audioParams.echoCanceler)
            enableNoiseSuppressor(audioSessionId, audioParams.noiseSuppressor)
            enableAutoGainControl(audioSessionId, audioParams.autoGainControl)
        } catch (e: Throwable) {
            throw IllegalArgumentException("Failed to initialize audio effects.", e)
        }
    }

    private fun enableAutoGainControl(audioSession: Int, enable: Boolean) {
        releaseAutoGainControl()
        if (enable && AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(audioSession)
            automaticGainControl?.enabled = true
        }
    }

    private fun enableEchoCanceler(audioSession: Int, enable: Boolean) {
        releaseEchoCanceler()
        if (enable && AcousticEchoCanceler.isAvailable()) {
            acousticEchoCanceler = AcousticEchoCanceler.create(audioSession)
            acousticEchoCanceler?.enabled = true
        }
    }

    private fun enableNoiseSuppressor(audioSession: Int, enable: Boolean) {
        releaseNoiseSuppressor()
        if (enable && NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSession)
            noiseSuppressor?.enabled = true
        }
    }

    private fun releaseAutoGainControl() {
        automaticGainControl?.enabled = false
        automaticGainControl?.release()
        automaticGainControl = null
    }

    private fun releaseEchoCanceler() {
        acousticEchoCanceler?.enabled = false
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
    }

    private fun releaseNoiseSuppressor() {
        noiseSuppressor?.enabled = false
        noiseSuppressor?.release()
        noiseSuppressor = null
    }

    private fun releaseAudioEffects() {
        releaseAutoGainControl()
        releaseEchoCanceler()
        releaseNoiseSuppressor()
    }
}