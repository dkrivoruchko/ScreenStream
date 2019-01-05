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

    enum class State { CREATED, ADDRESS_DISCOVERED, SERVER_STARTED, STREAMING, RESTART_PENDING, ERROR, DESTROYED }

    internal fun isPublicStatePublishRequired(previousStreamState: StreamState): Boolean =
        (this.state != State.DESTROYED && this.state != previousStreamState.state) ||
                this.netInterfaces != previousStreamState.netInterfaces ||
                this.appError != previousStreamState.appError

    internal fun toPublicState() = AppStateMachine.Effect.PublicState(
        this.isStreaming(), (this.canStartStream() || this.isStreaming()).not(), this.netInterfaces, this.appError
    )

    internal fun isStreaming(): Boolean = this.state == State.STREAMING

    private fun canStartStream(): Boolean = this.state == State.SERVER_STARTED
}