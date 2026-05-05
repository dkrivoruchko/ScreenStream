package info.dvkr.screenstream

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.ui.ScreenStreamContent
import info.dvkr.screenstream.ui.theme.ScreenStreamTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.coroutines.cancellation.CancellationException

public class SingleActivity : AppUpdateActivity() {

    internal companion object {
        private const val MODULE_START_MAX_ATTEMPTS = 5

        internal fun getIntent(context: Context): Intent = Intent(context, SingleActivity::class.java)
    }

    private val streamingModulesManager: StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
    private var deferredModuleId: StreamingModule.Id? = null
    private var moduleStartInProgress: StreamingModule.Id? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        XLog.d(this@SingleActivity.getLog("onCreate", "Bug workaround: ${window.decorView}"))
        super.onCreate(savedInstanceState)

        setContent {
            ScreenStreamTheme {
                ScreenStreamContent(updateFlow = updateFlow)
            }
        }
        AppReview.startTracking(activity = this, streamingModulesManager = streamingModulesManager)

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START && event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val moduleId = deferredModuleId ?: return@LifecycleEventObserver
            when {
                appSettings.data.value.streamingModule != moduleId -> deferredModuleId = null
                streamingModulesManager.isActive(moduleId) -> deferredModuleId = null
                moduleStartInProgress != null -> Unit
                else -> {
                    XLog.i(this@SingleActivity.getLog("deferredModuleStart", "retry module=$moduleId event=$event"))
                    lifecycleScope.launch { startModuleWithCheck(moduleId) }
                }
            }
        })

        appSettings.data.map { it.nightMode }
            .distinctUntilChanged()
            .onEach { if (AppCompatDelegate.getDefaultNightMode() != it) AppCompatDelegate.setDefaultNightMode(it) }
            .launchIn(lifecycleScope)

        streamingModulesManager.selectedModuleIdFlow
            .onStart { XLog.d(this@SingleActivity.getLog("selectedModuleIdFlow.onStart")) }
            .onEach { moduleId ->
                if (streamingModulesManager.isActive(moduleId)) return@onEach
                XLog.i(this@SingleActivity.getLog("selectedModuleIdFlow.onEach:", "$moduleId"))
                startModuleWithCheck(moduleId)
            }
            .catch {
                if (it is IllegalStateException) XLog.i(this@SingleActivity.getLog("selectedModuleIdFlow.catch: ${it.message}"), it)
                else throw it
            }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) XLog.d(this@SingleActivity.getLog("selectedModuleIdFlow.onCompletion"))
                else XLog.e(this@SingleActivity.getLog("selectedModuleIdFlow.onCompletion: ${cause.message}"), cause)
            }
            .flowWithLifecycle(lifecycle, minActiveState = Lifecycle.State.RESUMED)
            .launchIn(lifecycleScope)
    }

    private suspend fun startModuleWithCheck(moduleId: StreamingModule.Id) {
        if (moduleStartInProgress != null) {
            if (appSettings.data.value.streamingModule == moduleId) deferredModuleId = moduleId
            return
        }
        moduleStartInProgress = moduleId
        try {
            for (attempt in 0..MODULE_START_MAX_ATTEMPTS) {
                if (appSettings.data.value.streamingModule != moduleId) {
                    if (deferredModuleId == moduleId) deferredModuleId = null
                    return
                }

                val lifecycleState = lifecycle.currentState
                val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
                val canStartService = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) &&
                        importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING &&
                        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

                val message = "module=$moduleId state=$lifecycleState importance=$importance attempt=$attempt"
                if (canStartService) {
                    try {
                        streamingModulesManager.startModule(moduleId, this)
                        if (deferredModuleId == moduleId) deferredModuleId = null
                    } catch (cause: StreamingModule.StartBlockedException) {
                        deferredModuleId = moduleId
                        XLog.i(this@SingleActivity.getLog("startModuleWithCheck", "blocked $message"), cause)
                    }
                    return
                }

                if (attempt >= MODULE_START_MAX_ATTEMPTS) {
                    deferredModuleId = moduleId
                    XLog.i(this@SingleActivity.getLog("startModuleWithCheck", "deferred $message"))
                    return
                }

                delay(75L)
            }
        } finally {
            if (moduleStartInProgress == moduleId) moduleStartInProgress = null
            deferredModuleId
                ?.takeIf { it != moduleId && it == appSettings.data.value.streamingModule && streamingModulesManager.isActive(it).not() }
                ?.let { lifecycleScope.launch { startModuleWithCheck(it) } }
        }
    }
}
