package info.dvkr.screenstream

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.ui.ScreenStreamContent
import info.dvkr.screenstream.ui.theme.ScreenStreamTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.coroutines.cancellation.CancellationException

public class SingleActivity : AppUpdateActivity() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, SingleActivity::class.java)
    }

    private val streamingModulesManager: info.dvkr.screenstream.common.module.StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val streamingViewModel: SingleActivityViewModel by viewModel()
    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        XLog.d(this@SingleActivity.getLog("onCreate", "Bug workaround: ${window.decorView}"))
        super.onCreate(savedInstanceState)
        streamingViewModel.onActivityCreated()

        var waitingForSettings = true
        splashScreen.setKeepOnScreenCondition { waitingForSettings }

        lifecycleScope.launch {
            try {
                appSettings.initialize()
                applyNightMode(appSettings.data.value.nightMode)
            } finally {
                waitingForSettings = false
            }

            setContent {
                ScreenStreamTheme {
                    ScreenStreamContent(updateFlow = updateFlow)
                }
            }

            AppReview.startTracking(activity = this@SingleActivity, streamingModulesManager = streamingModulesManager)

            appSettings.data.map { it.nightMode }
                .distinctUntilChanged()
                .onEach { applyNightMode(it) }
                .launchIn(lifecycleScope)
        }

        streamingViewModel.exitState
            .onEach { state -> if (state == SingleActivityViewModel.ExitState.Finished) finishAndRemoveTask() }
            .launchIn(lifecycleScope)
    }

    private fun applyNightMode(mode: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val systemMode = when (mode) {
                    AppCompatDelegate.MODE_NIGHT_YES -> UiModeManager.MODE_NIGHT_YES
                    AppCompatDelegate.MODE_NIGHT_NO -> UiModeManager.MODE_NIGHT_NO
                    else -> UiModeManager.MODE_NIGHT_AUTO
                }
                getSystemService(UiModeManager::class.java).setApplicationNightMode(systemMode)
            } else if (AppCompatDelegate.getDefaultNightMode() != mode) {
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        } catch (error: RuntimeException) {
            if (error is CancellationException) throw error
            XLog.e(this@SingleActivity.getLog("applyNightMode", "Failed to apply night mode=$mode"), error)
        }
    }
}
