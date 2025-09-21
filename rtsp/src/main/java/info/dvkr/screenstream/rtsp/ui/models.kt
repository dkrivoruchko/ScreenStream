package info.dvkr.screenstream.rtsp.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtsp.settings.RtspSettings

@Immutable
internal data class RtspState(
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val selectedVideoEncoder: VideoCodecInfo? = null,
    val selectedAudioEncoder: AudioCodecInfo? = null,
    val transport: RtspTransportState = RtspTransportState(),
    val error: RtspError? = null
) {
    override fun toString(): String =
        "RtspState(isBusy=$isBusy, wCP=$waitingCastPermission, isStreaming=$isStreaming, transport=$transport, error=$error)"
}

@Immutable
internal data class RtspBinding(val label: String, val fullAddress: String)

@Immutable
internal data class RtspTransportState(
    val mode: RtspSettings.Values.Mode = RtspSettings.Default.MODE,
    val protocol: Protocol = Protocol.TCP,
    val status: Status = Status.Idle
) {
    @Immutable
    internal sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data class Ready(val bindings: List<RtspBinding> = emptyList()) : Status
        data class Active(val clients: Int = 0, val bindings: List<RtspBinding> = emptyList()) : Status
        data class ClientError(val error: ConnectionError) : Status
        data class GenericError(val error: RtspError) : Status
    }

    override fun toString(): String = "RtspTransportState(mode=$mode, protocol=$protocol, status=$status)"
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
        override fun toString(context: Context): String = context.getString(id) + " [${cause.toString()}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}
