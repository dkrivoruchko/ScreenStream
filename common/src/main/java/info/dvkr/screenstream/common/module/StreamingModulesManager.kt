package info.dvkr.screenstream.common.module

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.AppEvent
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single

@Single
public class StreamingModulesManager(modules: List<StreamingModule>, private val appSettings: AppSettings) {

    @JvmField
    public val modules: List<StreamingModule> = modules.sortedByDescending { it.priority }

    private fun hasModule(id: StreamingModule.Id): Boolean = modules.any { it.id == id }

    public val selectedModuleIdFlow: Flow<StreamingModule.Id> = appSettings.streamingModuleFlow
    public suspend fun selectStreamingModule(moduleId: StreamingModule.Id) {
        appSettings.setStreamingModule(moduleId)
    }

    private val _activeModuleStateFlow = MutableStateFlow<StreamingModule?>(null)
    public val activeModuleStateFlow: StateFlow<StreamingModule?>
        get() = _activeModuleStateFlow.asStateFlow()

    init {
        runBlocking {
            val currentModuleId = selectedModuleIdFlow.first()
            if (hasModule(currentModuleId).not()) {
                val defaultModuleId = modules.firstOrNull()?.id ?: throw IllegalStateException("No streaming module available")
                selectStreamingModule(defaultModuleId)
                XLog.i(getLog("init", "Set module: $defaultModuleId"))
            }
        }
    }

    public fun isActive(id: StreamingModule.Id): Boolean {
        require(hasModule(id)) { "No streaming module found: $id" }

        val isActive = _activeModuleStateFlow.value?.id == id

        XLog.d(getLog("isActive", "$id: $isActive"))

        return isActive
    }

    @MainThread
    public suspend fun startModule(id: StreamingModule.Id, context: Context) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        require(hasModule(id)) { "No streaming module found: $id" }

        XLog.d(getLog("startModule", "$id"))

        if (isActive(id)) {
            XLog.i(getLog("startModule", "Streaming module already active: $id."))
            return
        }

        stopModule()

        modules.first { it.id == id }.let { module ->
            module.startModule(context)
            _activeModuleStateFlow.value = module
        }
    }

    @MainThread
    public suspend fun stopModule(id: StreamingModule.Id? = _activeModuleStateFlow.value?.id) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (id == null) {
            XLog.d(getLog("stopModule", "No module specified. Ignoring"))
            return
        }

        modules.firstOrNull { it.id == id }?.let { module ->
            XLog.d(getLog("stopModule", "${module.id}"))
            if (isActive(module.id)) _activeModuleStateFlow.value = null
            module.stopModule()
        } ?: XLog.d(getLog("stopModule", "Module $id not found. Ignoring"))
    }

    public fun sendEvent(event: AppEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))

        _activeModuleStateFlow.value?.sendEvent(event) ?: run {
            val exception = IllegalStateException("StreamingModulesManager.sendEvent $event. No active module.")
            XLog.e(getLog("sendEvent", "Event $event. No active module."), exception)
        }
    }
}