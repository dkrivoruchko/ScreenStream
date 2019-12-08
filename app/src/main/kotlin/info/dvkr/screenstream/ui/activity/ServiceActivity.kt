package info.dvkr.screenstream.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.coroutineScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.AppService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

abstract class ServiceActivity : AppUpdateActivity() {

    private val serviceMessageLiveData = MutableLiveData<ServiceMessage>()
    private var isBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            (service as AppService.AppServiceBinder).getServiceMessageFlow()
                .onEach {
                    XLog.v(getLog("onServiceMessage", "ServiceMessage: $it")) //TODO
                    serviceMessageLiveData.value = it
                }
                .launchIn(lifecycle.coroutineScope)
            isBound = true
            IntentAction.GetServiceState.sendToAppService(this@ServiceActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key == Settings.Key.NIGHT_MODE) AppCompatDelegate.setDefaultNightMode(settings.nightMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(settings.nightMode)

        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        serviceMessageLiveData.observe(this, Observer { message -> message?.let { onServiceMessage(it) } })
        bindService(AppService.getAppServiceIntent(this), serviceConnection, Context.BIND_AUTO_CREATE)

        settings.registerChangeListener(settingsListener)
    }

    override fun onResume() {
        super.onResume()
        IntentAction.GetServiceState.sendToAppService(this)
    }

    override fun onStop() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }

        settings.unregisterChangeListener(settingsListener)
        super.onStop()
    }

    @CallSuper
    open fun onServiceMessage(serviceMessage: ServiceMessage) {
        when (serviceMessage) {
            ServiceMessage.FinishActivity -> {
                finishAndRemoveTask()
                Runtime.getRuntime().exit(0)
            }
        }
    }

    fun getServiceMessageLiveData(): LiveData<ServiceMessage> = serviceMessageLiveData
}