package info.dvkr.screenstream.rtsp.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtsp.internal.rtsp.server.ClientStats
import info.dvkr.screenstream.rtsp.settings.RtspSettings

@Immutable
internal data class RtspState(
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val selectedVideoEncoder: VideoCodecInfo? = null,
    val selectedAudioEncoder: AudioCodecInfo? = null,
    val modeState: RtspModeState = RtspModeState(),
    val serverClientStats: List<ClientStats> = emptyList(),
    val error: RtspError? = null
) {
    override fun toString(): String =
        "RtspState(isBusy=$isBusy, wCP=$waitingCastPermission, isStreaming=$isStreaming, transport=$modeState, clients=${serverClientStats.size}, error=$error)"
}

@Immutable
internal data class RtspBinding(val label: String, val fullAddress: String)

@Immutable
internal data class RtspModeState(
    val mode: RtspSettings.Values.Mode = RtspSettings.Default.MODE,
    val status: Status = Status.Idle
) {
    @Immutable
    internal sealed interface Status {
        data object Idle : Status
        data class Error(val error: RtspError) : Status

        @Immutable
        sealed interface Client : Status {
            data object Starting : Status
            data object Active : Status
        }

        @Immutable
        sealed interface Server : Status {
            data class Starting(val bindings: List<RtspBinding> = emptyList()) : Status
            data class Active(val bindings: List<RtspBinding> = emptyList()) : Status
        }
    }

    override fun toString(): String = "RtspModeState(mode=$mode, status=$status)"
}

@Immutable
internal sealed class RtspError(@param:StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal class NotificationPermissionRequired : RtspError(R.string.rtsp_error_notification_permission_required)
    internal class UnknownError(override val cause: Throwable?) : RtspError(R.string.rtsp_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause.toString()}]"
    }

    @Immutable
    internal sealed class ClientError(@StringRes id: Int) : RtspError(id) {
        internal class Failed(override val message: String?) : ClientError(R.string.rtsp_connection_error)
        internal class AccessDenied() : ClientError(R.string.rtsp_connection_access_denied)
        internal class NoCredentialsError : ClientError(R.string.rtsp_connection_no_credentials)
        internal class AuthError : ClientError(R.string.rtsp_connection_invalid_credentials)
    }

    @Immutable
    internal sealed class ServerError(@StringRes id: Int) : RtspError(id) {
        internal class AddressNotFoundException : RtspError(R.string.rtsp_error_ip_address_not_found)
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}
