package info.dvkr.screenstream.service


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.crashlytics.android.Crashlytics
import com.ironz.binaryprefs.BinaryPreferencesBuilder
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.data.settings.SettingsImpl

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG_MODE) Log.w("BootReceiver", "Thread [${Thread.currentThread().name}] onReceive")
        Crashlytics.log(1, "BootReceiver", "onReceive")

        val preferences = BinaryPreferencesBuilder(context)
                .exceptionHandler {
                    it?.let {
                        if (info.dvkr.screenstream.data.BuildConfig.DEBUG_MODE) Log.e("BinaryPreferences", it.toString())
                        Crashlytics.logException(it)
                    }
                }
                .build()
        val settings = SettingsImpl(preferences)

        if (!settings.startOnBoot) System.exit(0)

        if ("android.intent.action.BOOT_COMPLETED" == intent.action ||
                "android.intent.action.QUICKBOOT_POWERON" == intent.action) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(ForegroundService.getIntent(context, ForegroundService.ACTION_INIT))
                context.startForegroundService(ForegroundService.getIntent(context, ForegroundService.ACTION_START_ON_BOOT))
            } else {
                context.startService(ForegroundService.getIntent(context, ForegroundService.ACTION_INIT))
                context.startService(ForegroundService.getIntent(context, ForegroundService.ACTION_START_ON_BOOT))
            }
        }
    }
}
