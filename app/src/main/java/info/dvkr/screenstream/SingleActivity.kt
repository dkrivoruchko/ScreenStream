package info.dvkr.screenstream

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
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
import org.koin.android.ext.android.inject
import kotlin.coroutines.cancellation.CancellationException

public class SingleActivity : AppUpdateActivity() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, SingleActivity::class.java)
    }

    private val streamingModulesManager: StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        XLog.d(this@SingleActivity.getLog("onCreate", "Bug workaround: ${window.decorView}"))
        super.onCreate(savedInstanceState)

        setContent {
            ScreenStreamTheme {
                ScreenStreamContent(updateFlow = updateFlow)
            }
        }

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

    private suspend fun startModuleWithCheck(moduleId: StreamingModule.Id, attempt: Int = 0) {
        val importance = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
        XLog.i(this@SingleActivity.getLog("startModuleWithCheck", "$moduleId [${lifecycle.currentState}] Importance: $importance, Attempt $attempt"))

        if (attempt >= 5 || importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
            streamingModulesManager.startModule(moduleId, this)
        } else {
            delay(75)
            startModuleWithCheck(moduleId, attempt + 1)
        }
    }
}