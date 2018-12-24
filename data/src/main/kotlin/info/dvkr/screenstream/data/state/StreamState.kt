package info.dvkr.screenstream.data.state

import android.media.projection.MediaProjection
import info.dvkr.screenstream.data.image.BitmapCapture
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.NetInterface
import java.net.InetSocketAddress


data class StreamState(
    val state: State = State.CREATED,
    val mediaProjection: MediaProjection? = null,
    val bitmapCapture: BitmapCapture? = null,
    val netInterfaces: List<NetInterface> = emptyList(),
    val httpServerAddress: InetSocketAddress? = null,
    val httpServerAddressAttempt: Int = 0,
    val appError: AppError? = null
) {

    override fun toString(): String = "StreamState: $state"

    enum class State {
        CREATED, SERVER_ADDRESS_DISCOVERED, ERROR, SERVER_STARTED,
        PERMISSION_REQUESTED, STREAMING, DESTROYED, RESTART_PENDING
    }

    internal fun requireState(vararg requireStates: State): StreamState {
        state in requireStates ||
                throw IllegalStateException("Service in state [$state] expected ${requireStates.contentToString()}")
        return this
    }

    internal fun isPublicStatePublishRequired(previousStreamState: StreamState): Boolean =
        (this.state != State.DESTROYED && this.state != previousStreamState.state) ||
                this.netInterfaces != previousStreamState.netInterfaces ||
                this.appError != previousStreamState.appError

    internal fun toPublicState() = AppStateMachine.Effect.PublicState(
        this.isStreaming(), (this.canStartStream() || this.isStreaming()).not(), this.netInterfaces, this.appError
    )

    internal fun isStreaming(): Boolean = this.state == State.STREAMING

    internal fun stopProjectionIfStreaming(projectionCallback: MediaProjection.Callback): StreamState {
        if (isStreaming()) {
            bitmapCapture?.stop()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }

        return copy(mediaProjection = null, bitmapCapture = null)
    }


    private fun canStartStream(): Boolean = this.state == State.SERVER_STARTED
}