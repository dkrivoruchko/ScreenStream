package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import androidx.annotation.StringRes
import info.dvkr.screenstream.common.AppState
import info.dvkr.screenstream.mjpeg.MjpegModuleService
import info.dvkr.screenstream.mjpeg.R
import kotlinx.parcelize.Parcelize
import java.net.InetAddress

internal open class MjpegEvent(@JvmField val priority: Int) {

    internal object Priority {
        internal const val NONE: Int = -1
        internal const val RESTART_IGNORE: Int = 10
        internal const val RECOVER_IGNORE: Int = 20
        internal const val DESTROY_IGNORE: Int = 30
    }

    internal sealed class Intentable(priority: Int) : MjpegEvent(priority), Parcelable {
        internal companion object {
            private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"

            @Suppress("DEPRECATION")
            internal fun fromIntent(intent: Intent): Intentable? =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_PARCELABLE)
                else intent.getParcelableExtra(EXTRA_PARCELABLE, Intentable::class.java)
        }

        @Parcelize internal data object StartService : Intentable(Priority.NONE)
        @Parcelize internal data class StopStream(val reason: String) : Intentable(Priority.RESTART_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        internal fun toIntent(context: Context): Intent = MjpegModuleService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    internal data object CastPermissionsDenied : MjpegEvent(Priority.RECOVER_IGNORE)
    internal data class StartProjection(val intent: Intent) : MjpegEvent(Priority.RECOVER_IGNORE)
    internal data object CreateNewPin : MjpegEvent(Priority.DESTROY_IGNORE)
}

internal data class MjpegState(
    @JvmField val isBusy: Boolean = true,
    @JvmField val waitingCastPermission: Boolean = false,
    @JvmField val isStreaming: Boolean = false,
    @JvmField val netInterfaces: List<NetInterface> = emptyList(),
    @JvmField val clients: List<Client> = emptyList(),
    @JvmField val traffic: List<TrafficPoint> = emptyList(),
    @JvmField val error: MjpegError? = null
) {
    internal data class NetInterface(@JvmField val name: String, @JvmField val address: InetAddress)

    internal data class Client(@JvmField val id: String, @JvmField val clientAddress: String, @JvmField val state: State) {
        internal enum class State { CONNECTED, SLOW_CONNECTION, DISCONNECTED, BLOCKED }
    }

    internal data class TrafficPoint(@JvmField val time: Long, @JvmField val MBytes: Float)

    internal fun toAppState() = AppState(isBusy, isStreaming)

    override fun toString(): String =
        "MjpegState(isBusy=$isBusy, waitingCastPermission=$waitingCastPermission, isStreaming=$isStreaming, netInterfaces=$netInterfaces, clients=${clients.size}, error=$error)"
}

internal sealed class MjpegError(@StringRes open val id: Int, override val message: String? = null) : Throwable() {
    internal data object AddressNotFoundException : MjpegError(R.string.mjpeg_error_ip_address_not_found)
    internal data object AddressInUseException : MjpegError(R.string.mjpeg_error_port_in_use)
    internal data object CastSecurityException : MjpegError(R.string.mjpeg_error_invalid_media_projection)
    internal data object HttpServerException : MjpegError(R.string.mjpeg_error_unspecified)
    internal data class BitmapCaptureException(override val cause: Throwable?) : MjpegError(R.string.mjpeg_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }
    internal data object NotificationPermissionRequired : MjpegError(R.string.mjpeg_error_notification_permission_required) //TODO update error with settings button
    internal data class UnknownError(override val cause: Throwable?) : MjpegError(R.string.mjpeg_error_unspecified) {
        override fun toString(context: Context): String = context.getString(id) + " [${cause?.message}]"
    }
    internal open fun toString(context: Context): String = if (id != 0) context.getString(id) else message ?: toString()
}