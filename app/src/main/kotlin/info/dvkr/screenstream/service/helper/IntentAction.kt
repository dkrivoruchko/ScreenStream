package info.dvkr.screenstream.service.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import info.dvkr.screenstream.service.AppService
import kotlinx.android.parcel.Parcelize

sealed class IntentAction : Parcelable {
    internal companion object {
        private const val EXTRA_PARCELABLE = "EXTRA_PARCELABLE"
        fun fromIntent(intent: Intent?): IntentAction? = intent?.getParcelableExtra(
            EXTRA_PARCELABLE
        )
    }

    fun addToIntent(intent: Intent): Intent = intent.putExtra(EXTRA_PARCELABLE, this)

    fun toAppServiceIntent(context: Context): Intent = AppService.getAppServiceIntent(context.applicationContext, this)

    fun sendToAppService(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(this.toAppServiceIntent(context))
        else
            context.startService(this.toAppServiceIntent(context))
    }

    @Parcelize object GetServiceState : IntentAction()
    @Parcelize object StartStream : IntentAction()
    @Parcelize object StopStream : IntentAction()
    @Parcelize object Exit : IntentAction()
    @Parcelize data class CastIntent(val intent: Intent) : IntentAction()
    @Parcelize object CastPermissionsDenied : IntentAction()
    @Parcelize object StartOnBoot : IntentAction()
    @Parcelize object RecoverError : IntentAction()

    @Parcelize object StartAppActivity : IntentAction()
    @Parcelize object StartPermissionActivity : IntentAction()

    override fun toString(): String = this::class.java.simpleName
}