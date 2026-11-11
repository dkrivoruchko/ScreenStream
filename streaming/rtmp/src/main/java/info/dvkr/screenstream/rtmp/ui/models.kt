package info.dvkr.screenstream.rtmp.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtmp.internal.VideoCodecInfo
import info.dvkr.screenstream.rtmp.internal.rtmp.server.ClientStats
import info.dvkr.screenstream.rtmp.settings.RtmpSettings
import io.screenstream.core.module.StreamingModule
import kotlin.uuid.Uuid

@Immutable
internal enum class RtmpClientStatus { IDLE, STARTING, ACTIVE, ERROR }

@Immutable
internal sealed interface RtmpBindError {
    data object PortInUse : RtmpBindError
    data object AddressNotAvailable : RtmpBindError
    data object PermissionDenied : RtmpBindError
    data class Unknown(val technicalDetails: String?) : RtmpBindError
}

@Immutable
internal data class RtmpBinding(val label: String, val fullAddress: String, val bindError: RtmpBindError? = null)

@Immutable
internal data class RtmpState(
    val mode: RtmpSettings.Values.Mode = RtmpSettings.Default.MODE,
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val startAttemptId: String? = null,
    val isStreaming: Boolean = false,
    val activeH264ProfileIdc: Int? = null,
    val captureAttempt: StreamingModule.CaptureAttemptId? = null,
    val consentRetryToken: Uuid? = null,
    val errorToken: Uuid? = null,
    val selectedVideoEncoder: VideoCodecInfo? = null,
    val selectedAudioEncoder: AudioCodecInfo? = null,
    val serverBindings: List<RtmpBinding> = emptyList(),
    val serverClientStats: List<ClientStats> = emptyList(),
    val clientStatus: RtmpClientStatus = RtmpClientStatus.IDLE,
    val error: RtmpError? = null
) {
    override fun toString(): String =
        "RtmpState(mode=$mode busy=$isBusy wait=$waitingCastPermission start=$startAttemptId str=$isStreaming srvClients=${serverClientStats.size} client=$clientStatus err=$error)"
}

@Immutable
internal sealed class RtmpError(@field:StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal class NotificationPermissionRequired : RtmpError(R.string.rtmp_error_notification_permission_required)
    internal class LocalNetworkPermissionRequired : RtmpError(R.string.rtmp_error_local_network_permission_required)
    internal class ScreenCaptureStartBlocked(override val cause: Throwable?) : RtmpError(R.string.rtmp_error_screen_capture_start_blocked)
    internal class ProjectionAcquireRejected(override val cause: Throwable?) : RtmpError(R.string.rtmp_error_projection_acquire_rejected)
    internal class AudioPermissionRequired : RtmpError(R.string.rtmp_error_audio_permission_required)
    internal class AudioStartBlocked(override val cause: Throwable?) : RtmpError(R.string.rtmp_error_audio_start_blocked)
    internal open class UnknownError(override val cause: Throwable?) : RtmpError(R.string.rtmp_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause.toString()}]"
    }

    internal class VideoCodecError(override val cause: Throwable?) : UnknownError(cause)
    internal class VideoRendererError(override val cause: Throwable?) : UnknownError(cause)
    internal class VideoReconfigureError(override val cause: Throwable?) : UnknownError(cause)
    internal class AudioCodecError(override val cause: Throwable?) : UnknownError(cause)

    @Immutable
    internal sealed class ClientError(@StringRes id: Int) : RtmpError(id) {
        internal class Failed(override val message: String?) : ClientError(R.string.rtmp_connection_error)
        internal class AccessDenied : ClientError(R.string.rtmp_connection_access_denied)
        internal class NoCredentialsError : ClientError(R.string.rtmp_connection_no_credentials)
        internal class AuthError : ClientError(R.string.rtmp_connection_invalid_credentials)
    }

    @Immutable
    internal sealed class ServerError(@StringRes id: Int) : RtmpError(id) {
        internal class AddressNotFoundException : RtmpError(R.string.rtmp_error_ip_address_not_found)
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}

internal fun RtmpError.isStartupPolicyError(): Boolean =
    this is RtmpError.ScreenCaptureStartBlocked ||
            this is RtmpError.ProjectionAcquireRejected ||
            this is RtmpError.AudioPermissionRequired ||
            this is RtmpError.AudioStartBlocked
