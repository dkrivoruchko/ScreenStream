package info.dvkr.screenstream.mjpeg.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings

@Immutable
internal data class MjpegState(
    val isBusy: Boolean = true,
    val serverNetInterfaces: List<ServerNetInterface> = emptyList(),
    val waitingCastPermission: Boolean = false,
    val isStreaming: Boolean = false,
    val pin: Pin = Pin(MjpegSettings.Default.ENABLE_PIN, MjpegSettings.Default.PIN, MjpegSettings.Default.HIDE_PIN_ON_START),
    val clients: List<Client> = emptyList(),
    val traffic: List<TrafficPoint> = emptyList(),
    val error: MjpegError? = null
) {
    @Immutable
    internal data class ServerNetInterface(val label: String, val fullAddress: String)

    @Immutable
    internal data class Pin(val enablePin: Boolean, val pin: String, val hidePinOnStream: Boolean)

    @Immutable
    internal data class Client(val id: String, val address: String, val state: State) {
        internal enum class State { CONNECTED, SLOW_CONNECTION, DISCONNECTED, BLOCKED }
    }

    @Immutable
    internal data class TrafficPoint(val time: Long, val MBytes: Float)

    override fun toString(): String =
        "MjpegState(isBusy=$isBusy, wCP=$waitingCastPermission, isStreaming=$isStreaming, netInterfaces=$serverNetInterfaces, clients=${clients.size}, error=$error)"
}

@Immutable
internal sealed class MjpegError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal data object AddressNotFoundException : MjpegError(R.string.mjpeg_error_ip_address_not_found)
    internal data object AddressInUseException : MjpegError(R.string.mjpeg_error_port_in_use)
    internal data object CastSecurityException : MjpegError(R.string.mjpeg_error_invalid_media_projection)
    internal data object HttpServerException : MjpegError(R.string.mjpeg_error_unspecified)
    internal data class BitmapCaptureException(override val cause: Throwable?) : MjpegError(R.string.mjpeg_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal data object NotificationPermissionRequired : MjpegError(R.string.mjpeg_error_notification_permission_required)
    internal data class UnknownError(override val cause: Throwable?) : MjpegError(R.string.mjpeg_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }

    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}