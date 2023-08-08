package info.dvkr.screenstream.receiver


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val appSettings: AppSettings by inject()

    override fun onReceive(context: Context, intent: Intent) {
        XLog.d(getLog("onReceive", "Invoked: (SDK: ${Build.VERSION.SDK_INT}) Intent Action: ${intent.action}"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
                Runtime.getRuntime().exit(0)
                return
            }
        }

        if (runBlocking { appSettings.startOnBootFlow.first().not() }) {
            Runtime.getRuntime().exit(0)
            return
        }

        IntentAction.StartOnBoot.sendToAppService(context)
    }
}