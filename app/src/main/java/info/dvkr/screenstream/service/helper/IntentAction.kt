package info.dvkr.screenstream.service.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.service.ForegroundService
import kotlinx.parcelize.Parcelize

internal sealed class IntentAction : Parcelable {
    internal companion object {
        private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"

        internal fun fromIntent(intent: Intent?): IntentAction? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PARCELABLE, IntentAction::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PARCELABLE)
        }
    }

    internal fun toAppServiceIntent(context: Context): Intent =
        ForegroundService.getForegroundServiceIntent(context).putExtra(EXTRA_PARCELABLE, this@IntentAction)

    internal fun sendToAppService(context: Context) =
        ForegroundService.startService(context, this.toAppServiceIntent(context))

    @Parcelize data object GetServiceState : IntentAction()
    @Parcelize data object StartStream : IntentAction()
    @Parcelize data object StopStream : IntentAction()
    @Parcelize data object GetNewStreamId : IntentAction()
    @Parcelize data object CreateNewStreamPassword : IntentAction()
    @Parcelize data object Exit : IntentAction()
    @Parcelize data class CastIntent(val intent: Intent) : IntentAction()
    @Parcelize data object CastPermissionsDenied : IntentAction()
    @Parcelize data object StartOnBoot : IntentAction()
    @Parcelize data object RecoverError : IntentAction()

    @Parcelize data object ApplicationOnStart : IntentAction()
    @Parcelize data object ApplicationOnStop : IntentAction()
    @Parcelize data object ApplicationModeChanged : IntentAction()
}