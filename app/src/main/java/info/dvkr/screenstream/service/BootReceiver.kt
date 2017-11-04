package info.dvkr.screenstream.service


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.domain.settings.Settings
import javax.inject.Inject

class BootReceiver : BroadcastReceiver() {
    @Inject internal lateinit var settings: Settings

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG_MODE) Log.w("BootReceiver", "Thread [${Thread.currentThread().name}] onReceive")
        Crashlytics.log(1, "BootReceiver", "onReceive")
        (context.applicationContext as ScreenStreamApp).appComponent().serviceComponent().inject(this)

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
