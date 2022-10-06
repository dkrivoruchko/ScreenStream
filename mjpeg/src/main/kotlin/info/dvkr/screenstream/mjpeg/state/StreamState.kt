package info.dvkr.screenstream.mjpeg.state

import android.media.projection.MediaProjection
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.mjpeg.MjpegPublicState
import info.dvkr.screenstream.mjpeg.NetInterface
import info.dvkr.screenstream.mjpeg.image.BitmapCapture


data class StreamState(
    val state: State = State.CREATED,
    val mediaProjection: MediaProjection? = null,
    val bitmapCapture: BitmapCapture? = null,
    val netInterfaces: List<NetInterface> = emptyList(),
    val httpServerAddressAttempt: Int = 0,
    val appError: AppError? = null
) {

    enum class State { CREATED, ADDRESS_DISCOVERED, SERVER_STARTED, PERMISSION_PENDING, STREAMING, RESTART_PENDING, ERROR, DESTROYED }

    internal fun isPublicStatePublishRequired(previousStreamState: StreamState): Boolean =
        (state != State.DESTROYED && state != previousStreamState.state) ||
                netInterfaces != previousStreamState.netInterfaces ||
                appError != previousStreamState.appError

    internal fun toPublicState() = MjpegPublicState(
        isStreaming(), (canStartStream() || isStreaming()).not(), isWaitingForPermission(), netInterfaces, appError
    )

    internal fun isStreaming(): Boolean = state == State.STREAMING

    private fun canStartStream(): Boolean = state == State.SERVER_STARTED

    private fun isWaitingForPermission(): Boolean = state == State.PERMISSION_PENDING
}