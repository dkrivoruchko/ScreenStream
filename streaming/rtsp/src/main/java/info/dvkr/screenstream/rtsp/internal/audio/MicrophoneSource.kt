package info.dvkr.screenstream.rtsp.internal.audio

import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineDispatcher

internal class MicrophoneSource(
    audioParams: AudioSource.Params,
    private val audioSource: Int,
    dispatcher: CoroutineDispatcher,
    onAudioFrame: (AudioSource.Frame) -> Unit,
    onCaptureError: (Throwable) -> Unit
) : AudioSource {

    private val audioCapture = AudioCapture(audioParams, dispatcher, onAudioFrame, onCaptureError)

    override val isRunning: Boolean
        get() = audioCapture.isRunning()

    val isMuted: Boolean
        get() = audioCapture.isMuted()

    var volume: Float
        get() = audioCapture.micVolume
        set(value) {
            audioCapture.micVolume = value
        }

    @Throws
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun checkIfConfigurationSupported() = audioCapture.checkIfConfigurationSupported(audioSource)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = audioCapture.start(audioSource)

    override fun stop() = audioCapture.stop()

    internal fun setMute(mute: Boolean) {
        if (mute) audioCapture.mute() else audioCapture.unMute()
    }
}
