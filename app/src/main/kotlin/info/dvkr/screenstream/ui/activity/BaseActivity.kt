package info.dvkr.screenstream.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.*
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.service.AppService
import info.dvkr.screenstream.service.ServiceMessage
import timber.log.Timber

abstract class BaseActivity : AppCompatActivity() {

    private var serviceMessenger: Messenger? = null
    private var isBound: Boolean = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceMessenger = Messenger(service)
            isBound = true
            sendMessage(ServiceMessage.RegisterActivity(activityMessenger))
            AppService.startForegroundService(this@BaseActivity, AppService.IntentAction.GetServiceState)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            serviceMessenger = null
        }
    }

    fun sendMessage(serviceMessage: ServiceMessage) {
        Timber.tag(getTag("sendMessage")).d("ServiceMessage: $serviceMessage")
        isBound || return

        try {
            serviceMessenger?.send(Message.obtain(null, 0).apply { data = serviceMessage.toBundle() })
        } catch (ex: RemoteException) {
            Timber.tag(getTag("sendMessage")).e(ex)
        }
    }

    private class ServiceMessagesHandler : Handler() {
        private val serviceMessageLiveData = MutableLiveData<ServiceMessage>()

        fun getServiceMessageLiveData(): LiveData<ServiceMessage> = serviceMessageLiveData

        override fun handleMessage(msg: Message?) {
            serviceMessageLiveData.value = ServiceMessage.fromBundle(msg?.data)
        }
    }

    private val serviceMessagesHandler = ServiceMessagesHandler()
    private val activityMessenger = Messenger(serviceMessagesHandler)

    override fun onStart() {
        super.onStart()
        Timber.tag(getTag("onStart")).d("Invoked")

        serviceMessagesHandler.getServiceMessageLiveData().observe(this, Observer<ServiceMessage> { message ->
            message?.let { onServiceMessage(it) }
        })

        bindService(AppService.getIntent(this), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        Timber.tag(getTag("onStop")).d("Invoked")

        if (isBound) {
            sendMessage(ServiceMessage.UnRegisterActivity(activityMessenger))
            unbindService(serviceConnection)
            isBound = false
        }

        super.onStop()
    }

    @CallSuper
    open fun onServiceMessage(serviceMessage: ServiceMessage) {
        when (serviceMessage) {
            ServiceMessage.FinishActivity -> finish()
        }
    }

    fun getServiceMessageLiveData() = serviceMessagesHandler.getServiceMessageLiveData()
}