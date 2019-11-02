package info.dvkr.screenstream.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import info.dvkr.screenstream.service.AppService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction

abstract class ServiceActivity : AppUpdateActivity() {

    private var serviceMessenger: Messenger? = null
    private var isBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            serviceMessenger = Messenger(service)
            isBound = true
            sendMessage(ServiceMessage.RegisterActivity(activityMessenger))
            IntentAction.GetServiceState.sendToAppService(this@ServiceActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            serviceMessenger = null
        }
    }

    fun sendMessage(serviceMessage: ServiceMessage) {
        XLog.d(getLog("sendMessage", "ServiceMessage: $serviceMessage"))
        isBound || return

        try {
            serviceMessenger?.send(Message.obtain(null, 0).apply { data = serviceMessage.toBundle() })
        } catch (ex: RemoteException) {
            XLog.w(getLog("sendMessage", ex.toString()))
        }
    }

    private class ServiceMessagesHandler : Handler() {
        private val serviceMessageLiveData = MutableLiveData<ServiceMessage>()

        fun getServiceMessageLiveData(): LiveData<ServiceMessage> = serviceMessageLiveData

        override fun handleMessage(msg: Message) {
            serviceMessageLiveData.value = ServiceMessage.fromBundle(msg.data)
        }
    }

    private val serviceMessagesHandler = ServiceMessagesHandler()
    private val activityMessenger = Messenger(serviceMessagesHandler)
    private val settingsListener = object : SettingsReadOnly.OnSettingsChangeListener {
        override fun onSettingsChanged(key: String) {
            if (key == Settings.Key.NIGHT_MODE) setNightMode(settings.nightMode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setNightMode(settings.nightMode)
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        serviceMessagesHandler.getServiceMessageLiveData().observe(this, Observer<ServiceMessage> { message ->
            message?.let { onServiceMessage(it) }
        })

        bindService(AppService.getAppServiceIntent(this), serviceConnection, Context.BIND_AUTO_CREATE)

        settings.registerChangeListener(settingsListener)
    }

    override fun onResume() {
        super.onResume()
        IntentAction.GetServiceState.sendToAppService(this)
    }

    override fun onStop() {
        if (isBound) {
            sendMessage(ServiceMessage.UnRegisterActivity(activityMessenger))
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

    fun getServiceMessageLiveData() = serviceMessagesHandler.getServiceMessageLiveData()

    private fun setNightMode(@AppCompatDelegate.NightMode nightMode: Int) {
        AppCompatDelegate.setDefaultNightMode(nightMode)
//        delegate.localNightMode = nightMode
    }
}