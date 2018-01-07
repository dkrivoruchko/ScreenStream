package info.dvkr.screenstream.service


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import info.dvkr.screenstream.domain.settings.Settings
import info.dvkr.screenstream.domain.utils.Utils
import org.koin.standalone.KoinComponent
import org.koin.standalone.inject
import timber.log.Timber

class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val settings: Settings by inject()

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("[${Utils.getLogPrefix(this)}] BootReceiver.onReceive")
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