package info.dvkr.screenstream.webrtc.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.webrtc.R

@Immutable
internal data class WebRtcState(
    val isBusy: Boolean = true,
    val signalingServerUrl: String = "",
    val streamId: String = "",
    val streamPassword: String = "",
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val networkRecovery: Boolean = false,
    val clients: List<Client> = emptyList(),
    val error: WebRtcError? = null
) {
    @Immutable
    internal data class Client(val id: String, val publicId: String, val address: String)

    override fun toString(): String =
        "WebRtcState(isBusy=$isBusy, streamId='$streamId', wCP=$waitingCastPermission, isStreaming=$isStreaming, networkRecovery=$networkRecovery, clients=${clients.size}, error=$error)"
}

@Immutable
internal sealed class WebRtcError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal data class PlayIntegrityError(
        val code: Int,
        val isAutoRetryable: Boolean,
        override val message: String?,
        @StringRes override val id: Int = R.string.webrtc_error_unspecified
    ) : WebRtcError(id)

    internal data class NetworkError(
        val code: Int,
        override val message: String?,
        override val cause: Throwable?
    ) : WebRtcError(R.string.webrtc_error_check_network) {
        internal fun isNonRetryable(): Boolean = code in 500..599
        override fun toString(context: Context): String = context.getString(id) + if (message.isNullOrBlank()) "" else ":\n$message"
    }

    internal data class SocketError(
        override val message: String?,
        override val cause: Throwable?
    ) : WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String =
            context.getString(id) + " [$message] : ${if (cause?.message != null) cause.message else ""} "
    }

    internal data object NotificationPermissionRequired : WebRtcError(R.string.webrtc_notification_permission_required)

    internal data class UnknownError(override val cause: Throwable?) : WebRtcError(R.string.webrtc_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}