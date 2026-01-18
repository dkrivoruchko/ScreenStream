package info.dvkr.screenstream

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

public abstract class AppUpdateActivity : AppCompatActivity() {

    private companion object {
        private const val APP_UPDATE_REQUEST_TIMEOUT = 8 * 60 * 60 * 1000L  // 8 hours. Don't need exact time frame
        private const val LAST_UPDATE_REQUEST_TIME = "LAST_UPDATE_REQUEST_TIME"
    }

    private val adMob: AdMob by inject(mode = LazyThreadSafetyMode.NONE)

    private val _updateFlow: MutableStateFlow<((Boolean) -> Unit)?> = MutableStateFlow(null)
    protected val updateFlow: StateFlow<((Boolean) -> Unit)?> = _updateFlow.asStateFlow()

    private val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) { getSharedPreferences("play_update.xml", MODE_PRIVATE) }

    private var lastUpdateRequestMillis: Long
        get() = sharedPreferences.getLong(LAST_UPDATE_REQUEST_TIME, 0)
        set(value) = sharedPreferences.edit { putLong(LAST_UPDATE_REQUEST_TIME, value) }

    private val updateResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK)
            XLog.w(this@AppUpdateActivity.getLog("AppUpdateResult.updateResultLauncher", "Failed: ${result.resultCode}"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        XLog.d(getLog("onCreate"))

        adMob.init(this)

        AppUpdateManagerFactory.create(this).requestUpdateFlow().onEach { appUpdateResult ->
            when (appUpdateResult) {
                AppUpdateResult.NotAvailable -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.NotAvailable"))
                }

                is AppUpdateResult.Available -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available"))
                    if (appUpdateResult.updateInfo.isFlexibleUpdateAllowed) {
                        XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "FlexibleUpdateAllowed"))
                        val lastRequestMillisPassed = System.currentTimeMillis() - lastUpdateRequestMillis
                        if (lastRequestMillisPassed >= APP_UPDATE_REQUEST_TIMEOUT) {
                            XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "startFlexibleUpdate"))
                            lastUpdateRequestMillis = System.currentTimeMillis()
                            appUpdateResult.startFlexibleUpdate(updateResultLauncher)
                        }
                    }
                }

                is AppUpdateResult.InProgress -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.InProgress", appUpdateResult.installState.toString()))
                }

                is AppUpdateResult.Downloaded -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Downloaded"))
                    _updateFlow.value = { isPositive ->
                        _updateFlow.value = null
                        if (isPositive) {
                            lifecycleScope.launch { withContext(NonCancellable) { appUpdateResult.completeUpdate() } }
                        }
                    }
                }
            }
        }
            .catch { cause -> XLog.i(this@AppUpdateActivity.getLog("AppUpdateManager.requestUpdateFlow.catch: $cause")) }
            .launchIn(lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        XLog.d(getLog("onStart"))
    }

    override fun onResume() {
        super.onResume()
        XLog.d(getLog("onResume"))
    }

    override fun onPause() {
        XLog.d(getLog("onPause"))
        super.onPause()
    }

    override fun onStop() {
        XLog.d(getLog("onStop"))
        super.onStop()
    }

    override fun onDestroy() {
        XLog.d(getLog("onDestroy"))
        super.onDestroy()
    }
}
