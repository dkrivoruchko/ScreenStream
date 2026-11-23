package info.dvkr.screenstream.update

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.android.ext.android.inject
import org.koin.core.parameter.parametersOf
import com.google.android.play.core.install.model.ActivityResult as PlayActivityResult

/** ComponentActivity supplies result registration and lifecycle ownership for this Activity-local feature. */
internal class AppUpdate(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    private val log: Logger by activity.inject { parametersOf(this) }

    private companion object {
        private const val APP_UPDATE_REQUEST_TIMEOUT = 8 * 60 * 60 * 1000L  // 8 hours. Don't need exact time frame
        private const val LAST_UPDATE_REQUEST_TIME = "LAST_UPDATE_REQUEST_TIME"
        private const val LAST_UPDATE_RESTART_REQUEST_TIME = "LAST_UPDATE_RESTART_REQUEST_TIME"
    }

    private val appUpdateManager: AppUpdateManager by lazy(LazyThreadSafetyMode.NONE) { AppUpdateManagerFactory.create(activity) }

    private val mutableRestartAvailable = MutableStateFlow(false)
    val restartAvailable: StateFlow<Boolean> = mutableRestartAvailable.asStateFlow()

    private val sharedPreferences by lazy(LazyThreadSafetyMode.NONE) { activity.getSharedPreferences("play_update.xml", Context.MODE_PRIVATE) }

    private var lastUpdateRequestMillis: Long
        get() = sharedPreferences.getLong(LAST_UPDATE_REQUEST_TIME, 0)
        set(value) = sharedPreferences.edit { putLong(LAST_UPDATE_REQUEST_TIME, value) }

    private var lastUpdateRestartRequestMillis: Long
        get() = sharedPreferences.getLong(LAST_UPDATE_RESTART_REQUEST_TIME, 0)
        set(value) = sharedPreferences.edit { putLong(LAST_UPDATE_RESTART_REQUEST_TIME, value) }

    private val canRequestUpdateRestart: Boolean
        get() = !mutableRestartAvailable.value && !isCompletingUpdate &&
                System.currentTimeMillis() - lastUpdateRestartRequestMillis !in 0 until APP_UPDATE_REQUEST_TIMEOUT

    private val canShowUpdateUi: Boolean
        get() = !activity.isDestroyed && !activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private var isUpdateInfoRequestInProgress = false
    private var isUpdateFlowInProgress = false
    private var isCompletingUpdate = false
    private var skipNextUpdatePrompt = false

    private val installStateListener = InstallStateUpdatedListener { installState ->
        when (installState.installStatus()) {
            InstallStatus.DOWNLOADED -> requestCompleteUpdate()
            InstallStatus.FAILED, InstallStatus.CANCELED -> isCompletingUpdate = false
            else -> Unit
        }
    }

    private val updateResultLauncher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        isUpdateFlowInProgress = false
        when (result.resultCode) {
            Activity.RESULT_OK -> log.d("AppUpdateResult.updateResultLauncher", "Accepted")
            Activity.RESULT_CANCELED -> log.i("AppUpdateResult.updateResultLauncher", "Canceled")
            else -> {
                val reason = if (result.resultCode == PlayActivityResult.RESULT_IN_APP_UPDATE_FAILED) "Failed"
                else "Unknown: ${result.resultCode}"
                log.w("AppUpdateResult.updateResultLauncher", reason)
                lastUpdateRequestMillis = 0
                // The result is delivered before onResume; avoid immediately reopening failed consent UI.
                skipNextUpdatePrompt = true
            }
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Own registration directly: delayed lookup callbacks must never register a listener after destruction.
        appUpdateManager.registerListener(installStateListener)
    }

    override fun onResume(owner: LifecycleOwner) {
        val allowUpdatePrompt = !skipNextUpdatePrompt
        skipNextUpdatePrompt = false
        checkForUpdate(allowUpdatePrompt)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        appUpdateManager.unregisterListener(installStateListener)
    }

    private fun checkForUpdate(allowUpdatePrompt: Boolean) {
        if (!canShowUpdateUi || isUpdateInfoRequestInProgress || isCompletingUpdate) return
        isUpdateInfoRequestInProgress = true
        runCatching {
            appUpdateManager.appUpdateInfo
                .addOnCompleteListener { task ->
                    isUpdateInfoRequestInProgress = false
                    if (!canShowUpdateUi || isCompletingUpdate) return@addOnCompleteListener
                    if (!task.isSuccessful) {
                        log.i("checkForUpdate", "Failed: ${task.exception}")
                        return@addOnCompleteListener
                    }
                    val appUpdateInfo = task.result
                    if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                        requestCompleteUpdate()
                        return@addOnCompleteListener
                    }
                    val now = System.currentTimeMillis()
                    if (allowUpdatePrompt && !isUpdateFlowInProgress &&
                        appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                        now - lastUpdateRequestMillis !in 0 until APP_UPDATE_REQUEST_TIMEOUT
                    ) {
                        runCatching {
                            appUpdateManager.startUpdateFlowForResult(
                                appUpdateInfo,
                                updateResultLauncher,
                                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                            )
                        }.onSuccess { started ->
                            if (started) {
                                isUpdateFlowInProgress = true
                                lastUpdateRequestMillis = now
                            } else log.w("checkForUpdate", "startFlexibleUpdate: false")
                        }.onFailure { cause ->
                            log.w("checkForUpdate", "startFlexibleUpdate failed: $cause", cause)
                        }
                    }
                }
        }.onFailure { cause ->
            isUpdateInfoRequestInProgress = false
            log.i("checkForUpdate", "Failed: $cause")
        }
    }

    private fun requestCompleteUpdate() {
        if (canShowUpdateUi && canRequestUpdateRestart) mutableRestartAvailable.value = true
    }

    fun completeUpdate() {
        if (!canShowUpdateUi || !mutableRestartAvailable.value) return
        mutableRestartAvailable.value = false
        isCompletingUpdate = true
        runCatching {
            appUpdateManager.completeUpdate().addOnFailureListener(::onCompleteUpdateFailed)
        }.onFailure(::onCompleteUpdateFailed)
    }

    private fun onCompleteUpdateFailed(cause: Throwable) {
        isCompletingUpdate = false
        log.w("completeUpdate", "Failed: $cause", cause)
        checkForUpdate(allowUpdatePrompt = false)
    }

    fun dismissUpdate() {
        if (!mutableRestartAvailable.value) return
        mutableRestartAvailable.value = false
        lastUpdateRestartRequestMillis = System.currentTimeMillis()
    }
}
