package info.dvkr.screenstream

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import info.dvkr.screenstream.common.getLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koin.android.ext.android.inject
import com.google.android.play.core.install.model.ActivityResult as PlayActivityResult

public abstract class AppUpdateActivity : AppCompatActivity() {

    private companion object {
        private const val APP_UPDATE_REQUEST_TIMEOUT = 8 * 60 * 60 * 1000L  // 8 hours. Don't need exact time frame
        private const val LAST_UPDATE_REQUEST_TIME = "LAST_UPDATE_REQUEST_TIME"
        private const val LAST_UPDATE_RESTART_REQUEST_TIME = "LAST_UPDATE_RESTART_REQUEST_TIME"
    }

    private val adMob: AdMob by inject(mode = LazyThreadSafetyMode.NONE)
    private val appUpdateManager: AppUpdateManager by lazy(LazyThreadSafetyMode.NONE) { AppUpdateManagerFactory.create(this) }

    private val _updateFlow: MutableStateFlow<((Boolean) -> Unit)?> = MutableStateFlow(null)
    protected val updateFlow: StateFlow<((Boolean) -> Unit)?> = _updateFlow.asStateFlow()

    private val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) { getSharedPreferences("play_update.xml", MODE_PRIVATE) }

    private var lastUpdateRequestMillis: Long
        get() = sharedPreferences.getLong(LAST_UPDATE_REQUEST_TIME, 0)
        set(value) = sharedPreferences.edit { putLong(LAST_UPDATE_REQUEST_TIME, value) }

    private var lastUpdateRestartRequestMillis: Long
        get() = sharedPreferences.getLong(LAST_UPDATE_RESTART_REQUEST_TIME, 0)
        set(value) = sharedPreferences.edit { putLong(LAST_UPDATE_RESTART_REQUEST_TIME, value) }

    private val canRequestUpdateRestart: Boolean
        get() = _updateFlow.value == null && System.currentTimeMillis() - lastUpdateRestartRequestMillis >= APP_UPDATE_REQUEST_TIMEOUT

    private val updateResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        when (result.resultCode) {
            RESULT_OK -> XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.updateResultLauncher", "Accepted"))
            RESULT_CANCELED -> XLog.i(this@AppUpdateActivity.getLog("AppUpdateResult.updateResultLauncher", "Canceled"))
            else -> {
                val reason = if (result.resultCode == PlayActivityResult.RESULT_IN_APP_UPDATE_FAILED) "Failed"
                else "Unknown: ${result.resultCode}"
                XLog.w(this@AppUpdateActivity.getLog("AppUpdateResult.updateResultLauncher", reason))
                lastUpdateRequestMillis = 0
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        XLog.d(getLog("onCreate"))

        adMob.init(this)

        appUpdateManager.requestUpdateFlow().onEach { appUpdateResult ->
            when (appUpdateResult) {
                AppUpdateResult.NotAvailable -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.NotAvailable"))
                }

                is AppUpdateResult.Available -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available"))
                    val now = System.currentTimeMillis()
                    if (appUpdateResult.updateInfo.isFlexibleUpdateAllowed && now - lastUpdateRequestMillis >= APP_UPDATE_REQUEST_TIMEOUT) {
                        XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "startFlexibleUpdate"))
                        appUpdateResult.startFlexibleUpdate(updateResultLauncher).also { isUpdateFlowStarted ->
                            if (isUpdateFlowStarted) lastUpdateRequestMillis = now
                            else XLog.w(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "startFlexibleUpdate: false"))
                        }
                    }
                }

                is AppUpdateResult.InProgress -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.InProgress", appUpdateResult.installState.toString()))
                }

                is AppUpdateResult.Downloaded -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Downloaded"))
                    requestCompleteUpdate()
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

        if (canRequestUpdateRestart) {
            appUpdateManager.appUpdateInfo
                .addOnSuccessListener { appUpdateInfo ->
                    if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                        XLog.d(this@AppUpdateActivity.getLog("onResume", "DownloadedUpdate"))
                        requestCompleteUpdate()
                    }
                }
                .addOnFailureListener { cause ->
                    XLog.i(this@AppUpdateActivity.getLog("onResume", "DownloadedUpdate failed: $cause"))
                }
        }
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
        adMob.onActivityDestroyed(this)
        super.onDestroy()
    }

    private fun requestCompleteUpdate() {
        if (canRequestUpdateRestart.not()) return

        _updateFlow.value = { isPositive ->
            _updateFlow.value = null
            if (isPositive) {
                runCatching {
                    appUpdateManager.completeUpdate().addOnFailureListener { cause ->
                        XLog.w(this@AppUpdateActivity.getLog("completeUpdate", "Failed: $cause"), cause)
                    }
                }.onFailure { cause ->
                    XLog.w(this@AppUpdateActivity.getLog("completeUpdate", "Failed: $cause"), cause)
                }
            } else {
                lastUpdateRestartRequestMillis = System.currentTimeMillis()
            }
        }
    }
}
