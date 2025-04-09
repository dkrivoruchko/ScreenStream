package info.dvkr.screenstream.rtsp.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.rtsp.RtspModuleService
import kotlinx.parcelize.Parcelize

internal open class RtspEvent(val priority: Int) {

    internal object Priority {
        internal const val NONE: Int = -1
        internal const val RECOVER_IGNORE: Int = 20
        internal const val DESTROY_IGNORE: Int = 30
    }

    internal sealed class Intentable(priority: Int) : RtspEvent(priority), Parcelable {
        internal companion object {
            private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"

            @Suppress("DEPRECATION")
            internal fun fromIntent(intent: Intent): Intentable? =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) intent.getParcelableExtra(EXTRA_PARCELABLE)
                else intent.getParcelableExtra(EXTRA_PARCELABLE, Intentable::class.java)
        }

        @Parcelize internal data object StartService : Intentable(Priority.NONE)
        @Parcelize internal data class StopStream(val reason: String) : Intentable(Priority.RECOVER_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        internal fun toIntent(context: Context): Intent = RtspModuleService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    internal data object CastPermissionsDenied : RtspEvent(Priority.RECOVER_IGNORE)
    internal data class StartProjection(val intent: Intent) : RtspEvent(Priority.RECOVER_IGNORE)
}