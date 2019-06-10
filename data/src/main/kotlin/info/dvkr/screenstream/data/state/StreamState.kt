package info.dvkr.screenstream.data.state

import android.media.projection.MediaProjection
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.NetInterface


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

    internal fun toPublicState() = AppStateMachine.Effect.PublicState(
        isStreaming(), (canStartStream() || isStreaming()).not(), isWaitingForPermission(), netInterfaces, appError
    )

    internal fun isStreaming(): Boolean = state == State.STREAMING

    private fun canStartStream(): Boolean = state == State.SERVER_STARTED

    private fun isWaitingForPermission(): Boolean = state == State.PERMISSION_PENDING
}