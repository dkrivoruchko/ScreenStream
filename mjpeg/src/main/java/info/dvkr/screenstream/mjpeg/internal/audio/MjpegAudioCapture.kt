package info.dvkr.screenstream.mjpeg.internal.audio

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

internal class MjpegAudioCapture(
    private val audioParams: MjpegAudioSource.Params,
    private val dispatcher: CoroutineDispatcher,
    private val onAudioFrame: (MjpegAudioSource.Frame) -> Unit,
    private val onCaptureError: (Throwable) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    private var scope: CoroutineScope? = null

    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var running: Boolean = false
    private var muted: Boolean = false
    @Volatile private var stopping: Boolean = false

    @get:Synchronized
    @set:Synchronized
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 2f)
        }

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun isMuted(): Boolean = muted

    @Synchronized
    fun setMuted(value: Boolean) {
        muted = value
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun checkIfConfigurationSupported(audioSource: Int = MediaRecorder.AudioSource.DEFAULT) {
        val channelConfig = if (audioParams.isStereo) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBufferSize = AudioRecord.getMinBufferSize(audioParams.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            throw IllegalArgumentException("Invalid audio parameters.")
        }

        var testRecord: AudioRecord? = null
        try {
            testRecord = AudioRecord(audioSource, audioParams.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT, minBufferSize)
            if (testRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalArgumentException("Failed to initialize AudioRecord.")
            }
        } finally {
            testRecord?.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun checkIfConfigurationSupported(config: AudioPlaybackCaptureConfiguration) {
        val bufferSizeInBytes = audioParams.calculateBufferSizeInBytes()
        var testRecord: AudioRecord? = null
        try {
            testRecord = AudioRecord.Builder()
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
            if (testRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalArgumentException("Failed to initialize playback AudioRecord.")
            }
        } finally {
            testRecord?.release()
        }
    }

    @Synchronized
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(audioSource: Int) {
        var record: AudioRecord? = null
        try {
            check(!running) { "Audio capture is already running." }
            stopping = false

            val (preparedRecord, bufferSizeInBytes) = createAndStart(audioSource)
            record = preparedRecord
            audioRecord = preparedRecord
            running = true

            scope = CoroutineScope(Job() + dispatcher).apply {
                launch { readAudioLoop(record, bufferSizeInBytes) { createAndStart(audioSource) } }
            }
        } catch (cause: Exception) {
            cleanupFailedStart(record)
            XLog.w(getLog("start", "Failed to start audio capture: ${cause.message}"), cause)
            onCaptureError(cause)
        }
    }

    @Synchronized
    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(config: AudioPlaybackCaptureConfiguration) {
        var record: AudioRecord? = null
        try {
            check(!running) { "Audio capture is already running." }
            stopping = false

            val (preparedRecord, bufferSizeInBytes) = createAndStart(config)
            record = preparedRecord
            audioRecord = preparedRecord
            running = true

            scope = CoroutineScope(Job() + dispatcher).apply {
                launch { readAudioLoop(record, bufferSizeInBytes) { createAndStart(config) } }
            }
        } catch (cause: Exception) {
            cleanupFailedStart(record)
            XLog.w(getLog("start", "Failed to start playback audio capture: ${cause.message}"), cause)
            onCaptureError(cause)
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        stopping = true
        running = false

        scope?.cancel()
        scope = null

        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null

        releaseAudioEffects()
    }

    private fun cleanupFailedStart(record: AudioRecord?) {
        scope?.cancel()
        scope = null
        running = false

        runCatching { record?.stop() }
        record?.release()
        audioRecord = null
        releaseAudioEffects()
    }

    private suspend fun readAudioLoop(
        record: AudioRecord,
        bufferSizeInBytes: Int,
        recreateRecord: () -> Pair<AudioRecord, Int>
    ) = runCatching {
        var currentRecord = record
        var currentBufferSize = bufferSizeInBytes
        var pcmByteBuffer = ByteBuffer.allocateDirect(currentBufferSize).order(ByteOrder.nativeOrder())
        var shortBuffer = pcmByteBuffer.asShortBuffer()
        var retryAttempted = false

        while (currentCoroutineContext().isActive) {
            if (!isRunning()) break
            pcmByteBuffer.clear()
            val size = currentRecord.read(pcmByteBuffer, currentBufferSize, AudioRecord.READ_BLOCKING)
            when {
                size > 0 -> {
                    if (size % 2 != 0) continue

                    if (isMuted()) {
                        onAudioFrame(MjpegAudioSource.Frame(ByteArray(size), size, MjpegMasterClock.relativeTimeUs()))
                        continue
                    }

                    if (volume != 1.0f) {
                        val sampleCount = size / 2
                        shortBuffer.clear()
                        shortBuffer.limit(sampleCount)
                        for (i in 0 until sampleCount) {
                            val sample = shortBuffer.get(i)
                            val scaled = (sample * volume).toInt()
                            shortBuffer.put(
                                i,
                                when {
                                    scaled > Short.MAX_VALUE -> Short.MAX_VALUE
                                    scaled < Short.MIN_VALUE -> Short.MIN_VALUE
                                    else -> scaled.toShort()
                                }
                            )
                        }
                    }

                    val frameBuffer = ByteArray(size)
                    pcmByteBuffer.position(0)
                    pcmByteBuffer.get(frameBuffer, 0, size)
                    onAudioFrame(MjpegAudioSource.Frame(frameBuffer, size, MjpegMasterClock.relativeTimeUs()))
                }

                size < 0 -> {
                    if (!isRunning() || stopping || currentRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                    if (size == AudioRecord.ERROR_BAD_VALUE && !retryAttempted) {
                        retryAttempted = true
                        runCatching { currentRecord.stop() }
                        currentRecord.release()
                        releaseAudioEffects()

                        val (newRecord, newBufferSize) = recreateRecord()
                        currentRecord = newRecord
                        currentBufferSize = newBufferSize
                        pcmByteBuffer = ByteBuffer.allocateDirect(currentBufferSize).order(ByteOrder.nativeOrder())
                        shortBuffer = pcmByteBuffer.asShortBuffer()
                        audioRecord = newRecord
                        continue
                    }
                    onCaptureError(IllegalStateException("AudioRecord read failed (code=$size)."))
                    break
                }
            }
        }
    }.onFailure { cause ->
        if (cause is CancellationException) throw cause
        XLog.w(getLog("readAudioLoop", "Failed to read audio: ${cause.message}"), cause)
        onCaptureError(cause)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAndStart(audioSource: Int): Pair<AudioRecord, Int> {
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
            throw IllegalArgumentException("Failed to initialize AudioRecord.")
        }
        initializeAudioEffects(record.audioSessionId, audioParams)
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalArgumentException("Failed to start AudioRecord.")
        }
        return record to bufferSizeInBytes
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAndStart(config: AudioPlaybackCaptureConfiguration): Pair<AudioRecord, Int> {
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
            throw IllegalArgumentException("Failed to initialize playback AudioRecord.")
        }
        initializeAudioEffects(record.audioSessionId, audioParams)
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IllegalArgumentException("Failed to start playback AudioRecord.")
        }
        return record to bufferSizeInBytes
    }

    @Throws(Exception::class)
    private fun initializeAudioEffects(audioSessionId: Int, audioParams: MjpegAudioSource.Params) {
        enableEchoCanceler(audioSessionId, audioParams.echoCanceler)
        enableNoiseSuppressor(audioSessionId, audioParams.noiseSuppressor)
        enableAutoGainControl(audioSessionId, audioParams.autoGainControl)
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
