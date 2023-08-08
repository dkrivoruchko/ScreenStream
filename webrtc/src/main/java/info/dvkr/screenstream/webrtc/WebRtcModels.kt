package info.dvkr.screenstream.webrtc

import android.content.Context
import androidx.annotation.StringRes
import info.dvkr.screenstream.common.AppError
import info.dvkr.screenstream.common.AppStateMachine
import info.dvkr.screenstream.common.Client
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

public class WebRtcEnvironment(
    internal val packageName: String,
    public val signalingServerUrl: String,
    internal val noncePath: String,
    internal val socketPath: String,
    internal val cloudProjectNumber: Long
) {
    internal val signalingServerHost: String = signalingServerUrl.toHttpUrl().host
    internal val signalingServerNonceUrl: HttpUrl = (signalingServerUrl + noncePath).toHttpUrl()
}

public data class WebRtcPublicState(
    val isStreaming: Boolean,
    val isBusy: Boolean,
    val permissionWaiting: Boolean,
    val streamId: String,
    val streamPassword: String,
    val appError: AppError?
) : AppStateMachine.Effect.PublicState() {
    override fun toString(): String =
        "WebRtcPublicState(isStreaming=$isStreaming, isBusy=$isBusy, permissionWaiting=$permissionWaiting, streamId='$streamId', streamPassword='*', appError=$appError)"
}

public data class WebRtcPublicClient(override val id: Long, override val clientAddress: String) : Client

public sealed class WebRtcError(@StringRes id: Int) : AppError.FixableError(id) {
    public data class PlayIntegrityUserActionError(val code: Int, override val message: String?, @StringRes override val id: Int) :
        WebRtcError(id)

    public data class PlayIntegrityUserNotifyError(val code: Int, override val message: String?) :
        WebRtcError(R.string.webrtc_error_unspecified)

    public data class NetworkError(val code: Int, override val message: String?, override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_check_network) {
        override fun toString(context: Context): String = context.getString(id) + "\n[$code] : $message"
    }

    public data class SocketError(override val message: String?, override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String =
            context.getString(id) + " [$message] : ${if (cause?.message != null) cause.message else ""} "
    }

    public data class UnknownError(override val cause: Throwable?) :
        WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }
}