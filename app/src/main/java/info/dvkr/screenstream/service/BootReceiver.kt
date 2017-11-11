package info.dvkr.screenstream.service


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import info.dvkr.screenstream.data.settings.SettingsImpl
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Timber.w("[${Thread.currentThread().name} @${this.hashCode()}] BootReceiver.onReceive")

        val preferences = BinaryPreferencesBuilder(context)
                .exceptionHandler { Timber.e(it, "BinaryPreferencesBuilder") }
                .build()
        val settings = SettingsImpl(preferences)

        if (!settings.startOnBoot) System.exit(0)

        if ("android.intent.action.BOOT_COMPLETED" == intent.action ||
                "android.intent.action.QUICKBOOT_POWERON" == intent.action) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(FgService.getIntent(context, FgService.ACTION_INIT))
                context.startForegroundService(FgService.getIntent(context, FgService.ACTION_START_ON_BOOT))
            } else {
                context.startService(FgService.getIntent(context, FgService.ACTION_INIT))
                context.startService(FgService.getIntent(context, FgService.ACTION_START_ON_BOOT))
            }
        }
    }
}