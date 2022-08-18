package info.dvkr.screenstream.ui.activity

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

abstract class ServiceActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    private var isBound: Boolean = false
    private var serviceMessageFlowJob: Job? = null

    private val _serviceMessageFlow = MutableStateFlow<ServiceMessage?>(null)
    internal val serviceMessageFlow: StateFlow<ServiceMessage?> = _serviceMessageFlow.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            XLog.d(this@ServiceActivity.getLog("onServiceConnected"))

            try {
                val foregroundServiceBinder = binder as ForegroundService.ForegroundServiceBinder

                serviceMessageFlowJob = lifecycleScope.launch {
                    foregroundServiceBinder.serviceMessageFlow
                        .filterNotNull()
                        .onEach { serviceMessage -> onServiceMessage(serviceMessage) }
                        .catch { cause ->
                            XLog.e(this@ServiceActivity.getLog("onServiceConnected.serviceMessageFlow: $cause"))
                            XLog.e(this@ServiceActivity.getLog("onServiceConnected.serviceMessageFlow"), cause)
                        }
                        .collect()
                }
            } catch (cause: RemoteException) {
                XLog.e(this@ServiceActivity.getLog("onServiceConnected", "Failed to bind"), cause)
                return
            }

            isBound = true
            IntentAction.GetServiceState.sendToAppService(this@ServiceActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            XLog.w(this@ServiceActivity.getLog("onServiceDisconnected"))
            serviceMessageFlowJob?.cancel()
            serviceMessageFlowJob = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        settings.nightModeFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { AppCompatDelegate.setDefaultNightMode(it) }
            .launchIn(lifecycleScope)

        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        bindService(ForegroundService.getForegroundServiceIntent(this), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        IntentAction.GetServiceState.sendToAppService(this)
    }

    override fun onStop() {
        if (isBound) {
            serviceMessageFlowJob?.cancel()
            serviceMessageFlowJob = null
            unbindService(serviceConnection)
            isBound = false
        }

        super.onStop()
    }

    @CallSuper
    open fun onServiceMessage(serviceMessage: ServiceMessage) {
        XLog.v(getLog("onServiceMessage", "$serviceMessage"))

        _serviceMessageFlow.tryEmit(serviceMessage)

        if (serviceMessage is ServiceMessage.FinishActivity) {
            finishAndRemoveTask()
        }
    }
}