package info.dvkr.screenstream.rtsp.internal.audio

import android.Manifest
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher

@RequiresApi(Build.VERSION_CODES.Q)
internal class InternalAudioSource(
    audioParams: AudioSource.Params,
    private val mediaProjection: MediaProjection,
    dispatcher: CoroutineDispatcher,
    onAudioFrame: (AudioSource.Frame) -> Unit,
    onError: (Throwable) -> Unit
) : AudioSource {

    private val audioCapture = AudioCapture(audioParams, dispatcher, onAudioFrame, onError)

    override val isRunning: Boolean
        get() = audioCapture.isRunning()

    val isMuted: Boolean
        get() = audioCapture.isMuted()

    var volume: Float
        get() = audioCapture.micVolume
        set(value) {
            audioCapture.micVolume = value
        }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun checkIfConfigurationSupported() = audioCapture.checkIfConfigurationSupported()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        audioCapture.start(config)
    }

    override fun stop() = audioCapture.stop()

    fun mute() = audioCapture.mute()

    fun unMute() = audioCapture.unMute()
}