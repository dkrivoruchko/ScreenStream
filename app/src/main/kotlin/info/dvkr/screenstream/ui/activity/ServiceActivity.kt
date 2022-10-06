package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.service.ForegroundService
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

abstract class ServiceActivity(@LayoutRes contentLayoutId: Int) : AppUpdateActivity(contentLayoutId) {

    private var isBound: Boolean = false
    private var serviceMessageFlowJob: Job? = null

    private val _serviceMessageFlow = MutableSharedFlow<ServiceMessage>()
    internal val serviceMessageFlow: SharedFlow<ServiceMessage> = _serviceMessageFlow.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        appSettings.nightModeFlow
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { AppCompatDelegate.setDefaultNightMode(it) }
            .launchIn(lifecycleScope)

        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ForegroundService.serviceMessageFlow.collect { serviceMessage ->
                    _serviceMessageFlow.emit(serviceMessage)
                    onServiceMessage(serviceMessage)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        IntentAction.GetServiceState.sendToAppService(this)
    }

    @CallSuper
    open fun onServiceMessage(serviceMessage: ServiceMessage) {
        XLog.v(getLog("onServiceMessage", "$serviceMessage"))

        if (serviceMessage is ServiceMessage.FinishActivity) {
            finishAndRemoveTask()
        }
    }
}