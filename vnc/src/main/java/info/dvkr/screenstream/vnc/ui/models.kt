package info.dvkr.screenstream.vnc.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.settings.VncSettings

@Immutable
internal enum class VncReverseStatus { IDLE, CONNECTING, ACTIVE, RECONNECTING, ERROR }

@Immutable
internal data class VncState(
    val mode: VncSettings.Values.Mode = VncSettings.Default.MODE,
    val isBusy: Boolean = true,
    val waitingCastPermission: Boolean = false,
    val startAttemptId: String? = null,
    val isStreaming: Boolean = false,
    val serverNetInterfaces: List<VncNetInterface> = emptyList(),
    val clients: List<String> = emptyList(),
    val reverseStatus: VncReverseStatus = VncReverseStatus.IDLE,
    val reverseMessage: String? = null,
    val error: VncError? = null
) {
    @Immutable
    internal data class VncNetInterface(val label: String, val fullAddress: String)

    override fun toString(): String =
        "VncState(mode=$mode busy=$isBusy wait=$waitingCastPermission start=$startAttemptId str=$isStreaming " +
                "ifs=${serverNetInterfaces.size} clients=${clients.size} reverse=$reverseStatus err=$error)"
}

@Immutable
internal sealed class VncError(@field:StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal class AddressNotFoundException : VncError(R.string.vnc_error_ip_address_not_found)
    internal class AddressInUseException : VncError(R.string.vnc_error_port_in_use)
    internal class CastSecurityException : VncError(R.string.vnc_error_invalid_media_projection)
    internal class ScreenCaptureStartBlocked(override val cause: Throwable?) : VncError(R.string.vnc_error_screen_capture_start_blocked)
    internal class ProjectionAcquireRejected(override val cause: Throwable?) : VncError(R.string.vnc_error_projection_acquire_rejected)
    internal class BitmapCaptureException(override val cause: Throwable?) : VncError(R.string.vnc_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal class NotificationPermissionRequired : VncError(R.string.vnc_error_notification_permission_required)
    internal class LocalNetworkPermissionRequired : VncError(R.string.vnc_error_local_network_permission_required)
    internal class UnknownError(override val cause: Throwable?) : VncError(R.string.vnc_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}

internal fun VncError.isStartupPolicyError(): Boolean =
    this is VncError.ScreenCaptureStartBlocked || this is VncError.ProjectionAcquireRejected
