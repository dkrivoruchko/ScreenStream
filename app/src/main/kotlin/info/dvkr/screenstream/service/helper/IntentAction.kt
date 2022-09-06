package info.dvkr.screenstream.service.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.ui.activity.AppActivity
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

    internal fun toAppActivityIntent(context: Context): Intent =
        AppActivity.getAppActivityIntent(context).putExtra(EXTRA_PARCELABLE, this@IntentAction)

    internal fun sendToAppService(context: Context) =
        if (ForegroundService.isRunning)
            ForegroundService.startService(context, this.toAppServiceIntent(context))
        else
            ForegroundService.startForeground(context, this.toAppServiceIntent(context))

    @Parcelize object GetServiceState : IntentAction()
    @Parcelize object StartStream : IntentAction()
    @Parcelize object StopStream : IntentAction()
    @Parcelize object Exit : IntentAction()
    @Parcelize data class CastIntent(val intent: Intent) : IntentAction()
    @Parcelize object CastPermissionsDenied : IntentAction()
    @Parcelize object StartOnBoot : IntentAction()
    @Parcelize object RecoverError : IntentAction()

    override fun toString(): String = javaClass.simpleName
}