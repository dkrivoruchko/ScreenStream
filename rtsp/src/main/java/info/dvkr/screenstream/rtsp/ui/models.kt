package info.dvkr.screenstream.rtsp.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo

@Immutable
internal data class RtspState(
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val selectedVideoEncoder: VideoCodecInfo? = null,
    val selectedAudioEncoder: AudioCodecInfo? = null,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val error: RtspError? = null
) {
    override fun toString(): String =
        "MjpegState(isBusy=$isBusy, wCP=$waitingCastPermission, isStreaming=$isStreaming, error=$error)"
}

@Immutable
internal sealed class ConnectionState {
    internal data object Disconnected : ConnectionState()
    internal data object Connecting : ConnectionState()
    internal data object Connected : ConnectionState()
    internal data class Error(val connectionError: ConnectionError) : ConnectionState()
}

@Immutable
internal sealed class ConnectionError(@StringRes open val id: Int) : Throwable() {
    internal data class Failed(override val message: String?) : ConnectionError(R.string.rtsp_connection_error)
    internal data object AccessDenied : ConnectionError(R.string.rtsp_connection_access_denied)
    internal data object NoCredentialsError : ConnectionError(R.string.rtsp_connection_no_credentials)
    internal data object AuthError : ConnectionError(R.string.rtsp_connection_invalid_credentials)
}

@Immutable
internal sealed class RtspError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal data object NotificationPermissionRequired : RtspError(R.string.rtsp_error_notification_permission_required)
    internal data class UnknownError(override val cause: Throwable?) : RtspError(R.string.rtsp_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}