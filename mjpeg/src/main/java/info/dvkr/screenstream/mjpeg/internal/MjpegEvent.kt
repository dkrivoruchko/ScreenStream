package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.mjpeg.MjpegModuleService
import kotlinx.parcelize.Parcelize

internal open class MjpegEvent(val priority: Int) {

    internal object Priority {
        internal const val NONE: Int = -1
        internal const val RESTART_IGNORE: Int = 10
        internal const val RECOVER_IGNORE: Int = 20
        internal const val START_PROJECTION: Int = 21
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

        @Parcelize internal data class StartService(val token: String) : Intentable(Priority.NONE)
        @Parcelize internal data class StartProjection(val startAttemptId: String, val intent: Intent) : Intentable(Priority.START_PROJECTION)
        @Parcelize internal data class StopStream(val reason: String) : Intentable(Priority.RESTART_IGNORE)
        @Parcelize internal data object RecoverError : Intentable(Priority.RECOVER_IGNORE)

        internal fun toIntent(context: Context): Intent = MjpegModuleService.getIntent(context).putExtra(EXTRA_PARCELABLE, this)
    }

    internal data class CastPermissionsDenied(val startAttemptId: String) : MjpegEvent(Priority.RECOVER_IGNORE)
    internal data class StartProjection(
        val startAttemptId: String, val intent: Intent, val foregroundStartProcessed: Boolean = false, val foregroundStartError: Throwable? = null
    ) : MjpegEvent(Priority.START_PROJECTION)
    internal data object CreateNewPin : MjpegEvent(Priority.DESTROY_IGNORE)
}
