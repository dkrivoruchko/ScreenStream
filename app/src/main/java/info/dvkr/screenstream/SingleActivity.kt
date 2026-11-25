package info.dvkr.screenstream

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import info.dvkr.screenstream.ads.AdMob
import info.dvkr.screenstream.review.attachAppReview
import info.dvkr.screenstream.settings.AppSettings
import info.dvkr.screenstream.ui.StreamScreen
import info.dvkr.screenstream.ui.theme.ScreenStreamTheme
import info.dvkr.screenstream.update.AppUpdate
import info.dvkr.screenstream.update.AppUpdateHost
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.compose.RuntimePermissionHost
import io.screenstream.core.compose.ScreenCaptureConsentHost
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.cancellation.CancellationException

public class SingleActivity : AppCompatActivity() {

    internal companion object {
        internal fun getIntent(context: Context): Intent = Intent(context, SingleActivity::class.java)
    }

    private val log: Logger by inject { parametersOf(this) }
    private val streamingModulesManager: StreamingModuleManager by inject(mode = LazyThreadSafetyMode.NONE)
    private val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
    private val appUpdate = AppUpdate(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val moduleState = streamingModulesManager.state.value
        if (savedInstanceState == null || moduleState == StreamingModuleManager.State.NoModule || moduleState == StreamingModuleManager.State.Exiting) {
            streamingModulesManager.selectModule()
        }

        if (streamingModulesManager.state.value == StreamingModuleManager.State.Exiting) {
            finishAndRemoveTask()
            return
        }

        var waitingForSettings = true
        splashScreen.setKeepOnScreenCondition { waitingForSettings }

        lifecycleScope.launch {
            try {
                appSettings.initialize()
                applyNightMode(appSettings.data.value.nightMode)
            } finally {
                waitingForSettings = false
            }

            val adMob = get<AdMob>()
            if (savedInstanceState == null && isTaskRoot) adMob.beginAppLaunch()
            val initialRationaleHistory = appSettings.data.value.permissionsWithObservedRationale
            setContent {
                ScreenStreamTheme(appSettings = appSettings) {
                    RuntimePermissionHost(
                        initialRationaleHistory = initialRationaleHistory,
                        onRationaleObserved = appSettings::setPermissionRationaleObserved,
                    ) {
                        StreamScreen(streamingModulesManager)
                    }

                    AppUpdateHost(appUpdate)
                    ScreenCaptureConsentHost()
                }
            }

            // Let the main thread install the window decor before UMP reads it on a worker thread.
            adMob.attach(this@SingleActivity)
            attachAppReview(this@SingleActivity)

            appSettings.data.map { it.nightMode }
                .distinctUntilChanged()
                .onEach { applyNightMode(it) }
                .launchIn(lifecycleScope)
        }
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
            log.e("applyNightMode", "Failed to apply night mode=$mode", error)
        }
    }
}
