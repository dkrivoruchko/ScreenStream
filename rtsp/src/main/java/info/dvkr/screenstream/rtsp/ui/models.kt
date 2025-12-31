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
internal enum class RtspClientStatus { IDLE, STARTING, ACTIVE, ERROR }

@Immutable
internal data class RtspBinding(val label: String, val fullAddress: String)

@Immutable
internal data class RtspState(
    val mode: RtspSettings.Values.Mode = RtspSettings.Default.MODE,
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val selectedVideoEncoder: VideoCodecInfo? = null,
    val selectedAudioEncoder: AudioCodecInfo? = null,
    val serverBindings: List<RtspBinding> = emptyList(),
    val serverClientStats: List<ClientStats> = emptyList(),
    val clientStatus: RtspClientStatus = RtspClientStatus.IDLE,
    val error: RtspError? = null
) {
    override fun toString(): String =
        "RtspViewState(mode=$mode, isBusy=$isBusy, wCP=$waitingCastPermission, isStreaming=$isStreaming, serverClients=${serverClientStats.size}, clientStatus=$clientStatus, error=$error)"
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
