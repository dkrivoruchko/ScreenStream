package info.dvkr.screenstream

import android.annotation.SuppressLint
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import info.dvkr.screenstream.ui.activity.PermissionActivity

class StartStreamActivity : PermissionActivity() {

    var executed = false

    @SuppressLint("RestrictedApi")
    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                XLog.d(this@StartStreamActivity.getLog("onServiceMessage", "Message: $serviceMessage"))
                if (serviceMessage.isStreaming || serviceMessage.isBusy || executed) {
                    return
                }

                executed = true
                settings.useWiFiOnly = false
                settings.enableLocalHost = true
                IntentAction.StartStream.sendToAppService(this@StartStreamActivity)
            }
        }
    }
}
