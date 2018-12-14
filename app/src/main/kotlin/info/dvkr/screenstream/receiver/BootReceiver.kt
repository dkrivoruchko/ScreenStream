package info.dvkr.screenstream.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.AppService
import org.koin.standalone.KoinComponent
import org.koin.standalone.inject
import timber.log.Timber

class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val settingsReadOnly: SettingsReadOnly by inject()

    override fun onReceive(context: Context, intent: Intent) {
        Timber.tag(getTag("onReceive")).d("Invoked")

        if (settingsReadOnly.startOnBoot.not()) System.exit(0)

        if (
            intent.action == "android.intent.action.BOOT_COMPLETED" ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            AppService.startForegroundService(context, AppService.IntentAction.StartOnBoot)
        }
    }
}