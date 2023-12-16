package info.dvkr.screenstream.common

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import com.elvishew.xlog.XLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.koin.core.annotation.Single

@Single
public class StreamingModulesManager(modules: List<StreamingModule>, private val appStateFlowProvider: AppStateFlowProvider) {
    @JvmField
    public val modules: List<StreamingModule> = modules.sortedByDescending { it.priority }

    public fun hasModule(id: StreamingModule.Id): Boolean = modules.any { it.id == id }

    public fun getDefaultModuleId(): StreamingModule.Id = modules.firstOrNull()?.id ?: StreamingModule.Id.UNDEFINED

    private val _activeModuleStateFlow = MutableStateFlow<StreamingModule?>(null)
    public val activeModuleStateFlow: StateFlow<StreamingModule?>
        get() = _activeModuleStateFlow.asStateFlow()

    @MainThread
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    public fun isActivate(id: StreamingModule.Id): Boolean {
        XLog.d(getLog("isActivate", id.toString()))

        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        require(id.isDefined()) { "Wrong Streaming module id: $id" }
        require(hasModule(id)) { "No streaming module found: $id" }

        return _activeModuleStateFlow.value?.id == id
    }

    @MainThread
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    public suspend fun activate(id: StreamingModule.Id, context: Context) {
        XLog.d(getLog("activate", id.toString()))

        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }
        require(id.isDefined()) { "Wrong Streaming module id: $id" }
        require(hasModule(id)) { "No streaming module found: $id" }

        if (isActivate(id)) {
            XLog.i(getLog("activate", "Streaming module already active: $id. Ignoring"))
            return
        }

        deactivate(_activeModuleStateFlow.value?.id ?: StreamingModule.Id.UNDEFINED)

        appStateFlowProvider.mutableAppStateFlow.value = StreamingModule.AppState()

        modules.first { it.id == id }.let { module ->
            module.createStreamingService(context)
            _activeModuleStateFlow.value = module
        }
    }

    @MainThread
    @Throws(IllegalStateException::class)
    public suspend fun deactivate(id: StreamingModule.Id) {
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        modules.firstOrNull { it.id == id }?.let { module ->
            XLog.d(getLog("deactivate", module.id.toString()))
            module.destroyStreamingService()
            if (isActivate(module.id)) _activeModuleStateFlow.value = null
        } ?: XLog.d(getLog("deactivate", "Module $id not found. Ignoring"))
    }

    @MainThread
    @Throws(IllegalStateException::class)
    public fun sendEvent(event: StreamingModule.AppEvent) {
        XLog.d(getLog("sendEvent", "Event $event"))
        check(Looper.getMainLooper().isCurrentThread) { "Only main thread allowed" }

        if (event is StreamingModule.AppEvent.Exit) {
            activeModuleStateFlow.value?.let { module -> runBlocking { module.destroyStreamingService() } }
            event.callback()
        } else {
            _activeModuleStateFlow.value?.sendEvent(event) ?: run {
                val exception = IllegalStateException("StreamingModulesManager.sendEvent $event. No active module.")
                XLog.e(getLog("sendEvent", "Event $event. No active module."), exception)
            }
        }
    }
}