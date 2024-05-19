package info.dvkr.screenstream

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.ui.ScreenStreamContent
import info.dvkr.screenstream.ui.theme.ScreenStreamTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject
import org.koin.compose.KoinContext
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
            KoinContext {
                ScreenStreamTheme {
                    ScreenStreamContent(
                        updateFlow = updateFlow,
                        modifier = Modifier.safeDrawingPadding()
                    )
                }
            }
        }

        appSettings.data.map { it.nightMode }
            .distinctUntilChanged()
            .onEach { if (AppCompatDelegate.getDefaultNightMode() != it) AppCompatDelegate.setDefaultNightMode(it) }
            .launchIn(lifecycleScope)

        streamingModulesManager.selectedModuleIdFlow
            .onEach { moduleId ->
                XLog.i(this@SingleActivity.getLog("streamingModuleFlow.onEach:", "$moduleId"))
                streamingModulesManager.startModule(moduleId, this)
            }
            .catch {
                if (it is IllegalStateException) XLog.i(this@SingleActivity.getLog("streamingModuleFlow.catch: ${it.message}"), it)
                else throw it
            }
            .onCompletion { cause ->
                if (cause == null || cause is CancellationException) XLog.i(this@SingleActivity.getLog("streamingModuleFlow.onCompletion"))
                else XLog.e(this@SingleActivity.getLog("streamingModuleFlow.onCompletion: ${cause.message}"), cause)
            }
            .flowWithLifecycle(lifecycle)
            .launchIn(lifecycleScope)
    }
}