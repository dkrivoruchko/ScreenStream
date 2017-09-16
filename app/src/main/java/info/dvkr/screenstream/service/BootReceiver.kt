package info.dvkr.screenstream.service


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.crashlytics.android.Crashlytics
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.ScreenStreamApp
import info.dvkr.screenstream.model.Settings
import javax.inject.Inject

class BootReceiver : BroadcastReceiver() {
    @Inject internal lateinit var settings: Settings

    override fun onReceive(context: Context, intent: Intent) {
        if (BuildConfig.DEBUG_MODE) Log.w("BootReceiver", "Thread [${Thread.currentThread().name}] onReceive")
        Crashlytics.log(1, "BootReceiver", "onReceive")
        (context.applicationContext as ScreenStreamApp).appComponent().plusActivityComponent().inject(this)

        if (!settings.startOnBoot) System.exit(0)

        if ("android.intent.action.BOOT_COMPLETED" == intent.action ||
                "android.intent.action.QUICKBOOT_POWERON" == intent.action) {
            context.startService(ForegroundService.getIntent(context, ForegroundService.ACTION_INIT))
            context.startService(ForegroundService.getIntent(context, ForegroundService.ACTION_START_ON_BOOT))
        }
    }
}
