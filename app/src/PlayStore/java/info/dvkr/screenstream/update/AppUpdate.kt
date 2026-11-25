package info.dvkr.screenstream.update

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import info.dvkr.screenstream.SingleActivity
import io.screenstream.core.StreamingModuleManager
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.android.inject
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import com.google.android.play.core.install.model.ActivityResult as PlayActivityResult

/** Owns Play calls and their results on the main thread for one Activity. */
@Factory
internal class AppUpdate(
    @InjectedParam private val activity: SingleActivity,
    private val streamingModulesManager: StreamingModuleManager,
) : DefaultLifecycleObserver {

    internal enum class State { Hidden, Available, Preparing, Downloading, Downloaded, ConfirmInstall, Installing }

    private enum class Action { CheckDownload, AwaitConsent, AwaitDownload, ConfirmInstall, CheckInstall, Install }

    private companion object {
        private val PLAY_STATUS_GRACE = 2.seconds
        private val PLAY_INFO_TIMEOUT = 2.seconds
    }

    private val log: Logger by activity.inject { parametersOf(this) }
    private val appUpdateManager = AppUpdateManagerFactory.create(activity)
    private val flexibleUpdateOptions = AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)

    val state: StateFlow<State>
        field = MutableStateFlow(State.Hidden)

    val canInstallInModuleState: Flow<Boolean> = streamingModulesManager.state
        .map { it.allowsUpdateInstall }
        .distinctUntilChanged()

    private var playUpdateState = State.Hidden
    private var action: Action? = null
    private var updateInfoJob: Job? = null
    private var recoveryJob: Job? = null
    private var installRequestToken: Any? = null
    private var verifyInstallOnRetry = false

    private val canRunForegroundUpdate: Boolean
        get() = !activity.isDestroyed && !activity.isFinishing && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private val installStateListener = InstallStateUpdatedListener { onInstallStatusChanged(it.installStatus()) }

    private val downloadConsentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { consentResult ->
        val tag = "AppUpdateResult.updateResultLauncher"
        val intentSenderFailed = consentResult.data?.hasExtra(
            ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
        ) == true
        when (consentResult.resultCode) {
            Activity.RESULT_OK -> log.d(tag, "Accepted")
            Activity.RESULT_CANCELED -> if (intentSenderFailed) log.w(tag, "Failed") else log.i(tag, "Canceled")
            PlayActivityResult.RESULT_IN_APP_UPDATE_FAILED -> log.w(tag, "Failed")
            else -> log.w(tag, if (intentSenderFailed) "Failed" else "Unknown: ${consentResult.resultCode}")
        }

        val downloadNotObserved = playUpdateState == State.Hidden || playUpdateState == State.Available
        if (consentResult.resultCode == Activity.RESULT_OK && downloadNotObserved &&
            (action == Action.AwaitConsent || action == null)
        ) {
            // A restored Activity can receive the result before onResume, without a local launch action.
            action = Action.AwaitDownload
        } else if (action == Action.AwaitConsent) {
            action = null
        }
        publishState()
        if (downloadNotObserved && action != Action.Install) requestUpdateInfo()
        if (action == Action.AwaitDownload) scheduleRecovery()
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner): Unit = appUpdateManager.registerListener(installStateListener)

    override fun onResume(owner: LifecycleOwner) {
        requestUpdateInfo()
        if (needsRecovery) scheduleRecovery()
    }

    override fun onPause(owner: LifecycleOwner) {
        // A tap awaiting Play info must not launch anything after the user leaves the Activity.
        updateInfoJob?.cancel()
        recoveryJob?.cancel()
        if (action == Action.CheckDownload || action == Action.CheckInstall || action == Action.ConfirmInstall) {
            action = null
        }
        publishState()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        updateInfoJob?.cancel()
        recoveryJob?.cancel()
        installRequestToken = null
        appUpdateManager.unregisterListener(installStateListener)
    }

    fun requestDownload() {
        if (state.value == State.Available && action == null) requestUpdateInfo(forDownload = true)
    }

    fun requestInstall() {
        if (state.value == State.Downloaded) installDownloadedUpdate(streamInterruptionConfirmed = false)
    }

    fun confirmInstall() {
        if (action == Action.ConfirmInstall) installDownloadedUpdate(streamInterruptionConfirmed = true)
    }

    fun dismissInstallConfirmation() {
        if (action != Action.ConfirmInstall) return
        action = null
        publishState()
    }

    private fun requestUpdateInfo(
        forDownload: Boolean = false,
        bounded: Boolean = false,
        onResult: (AppUpdateInfo?) -> Unit = ::handleUpdateInfo,
    ) {
        if (!canRunForegroundUpdate || action == Action.CheckDownload) return
        updateInfoJob?.cancel()
        if (forDownload) action = Action.CheckDownload
        publishState()
        // Store the job before a synchronous failure can deliver a result and start another lookup.
        val lookup = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val info = if (bounded) withTimeoutOrNull(PLAY_INFO_TIMEOUT) { awaitUpdateInfo() } else awaitUpdateInfo()
            if (!isActive) return@launch
            updateInfoJob = null
            if (canRunForegroundUpdate) onResult(info)
        }
        updateInfoJob = lookup
        lookup.start()
    }

    private suspend fun awaitUpdateInfo(): AppUpdateInfo? = suspendCancellableCoroutine { continuation ->
        runCatching {
            appUpdateManager.appUpdateInfo.addOnCompleteListener { task ->
                if (continuation.isActive) {
                    val info = if (task.isSuccessful) task.result else {
                        log.i("checkForUpdate", "Failed: ${task.exception}")
                        null
                    }
                    continuation.resume(info)
                }
            }
        }.onFailure { cause ->
            log.i("checkForUpdate", "Failed: $cause")
            continuation.resume(null)
        }
    }

    private fun handleUpdateInfo(updateInfo: AppUpdateInfo?) {
        val observedState = updateInfo?.toUpdateState()
        // An early snapshot can still show the downloaded payload after Play accepted the install.
        if (action == Action.Install && observedState != State.Installing) return
        if (observedState != null) {
            playUpdateState = observedState
            if (observedState != State.Downloaded) verifyInstallOnRetry = false
        }
        when (action) {
            Action.Install -> if (installRequestToken == null) clearRecoveryAction()
            Action.ConfirmInstall -> if (playUpdateState != State.Downloaded) action = null
            Action.CheckDownload -> {
                action = null
                if (updateInfo != null && observedState == State.Available) launchDownloadConsent(updateInfo)
            }

            Action.AwaitDownload -> {
                if (observedState == State.Downloading || observedState == State.Downloaded || observedState == State.Installing) {
                    clearRecoveryAction()
                }
            }

            else -> Unit
        }
        publishState()
    }

    private val needsRecovery: Boolean
        get() = action == Action.AwaitDownload || (action == Action.Install && installRequestToken == null)

    private fun scheduleRecovery() {
        if (!canRunForegroundUpdate) return
        recoveryJob?.cancel()
        recoveryJob = activity.lifecycleScope.launch {
            delay(PLAY_STATUS_GRACE)
            if (needsRecovery) requestUpdateInfo(bounded = true) { updateInfo ->
                // This settles the UI. A retry after an ambiguous install still needs fresh Play status.
                if (action == Action.Install && playUpdateState == State.Downloaded) verifyInstallOnRetry = true
                clearRecoveryAction()
                handleUpdateInfo(updateInfo)
            }
        }
    }

    private fun clearRecoveryAction() {
        recoveryJob?.cancel()
        action = null
    }

    private fun AppUpdateInfo.toUpdateState(): State? = when (updateAvailability()) {
        UpdateAvailability.UPDATE_AVAILABLE -> if (isUpdateTypeAllowed(flexibleUpdateOptions)) State.Available else State.Hidden
        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> installStatus().toUpdateState()
        UpdateAvailability.UPDATE_NOT_AVAILABLE -> State.Hidden
        else -> null
    }

    private fun Int.toUpdateState(): State? = when (this) {
        InstallStatus.PENDING, InstallStatus.DOWNLOADING -> State.Downloading
        InstallStatus.DOWNLOADED -> State.Downloaded
        InstallStatus.INSTALLING -> State.Installing
        InstallStatus.FAILED, InstallStatus.CANCELED, InstallStatus.INSTALLED -> State.Hidden
        else -> null
    }

    private fun onInstallStatusChanged(status: Int) {
        val reportedState = status.toUpdateState() ?: return
        if (reportedState != State.Hidden && playUpdateState == reportedState) return
        if (reportedState == State.Downloading && playUpdateState == State.Downloaded) return
        if (playUpdateState == State.Installing && (reportedState == State.Downloading || reportedState == State.Downloaded)) return
        updateInfoJob?.cancel()
        recoveryJob?.cancel()

        val shouldRefreshUpdateInfo = status == InstallStatus.FAILED || status == InstallStatus.CANCELED
        playUpdateState = reportedState
        verifyInstallOnRetry = false
        if (reportedState == State.Hidden) installRequestToken = null
        val keepCompletingUpdate = action == Action.Install && (installRequestToken != null || reportedState == State.Installing)
        if (!keepCompletingUpdate && (action != Action.AwaitConsent || reportedState == State.Hidden)) {
            action = null
        }
        publishState()
        if (shouldRefreshUpdateInfo) requestUpdateInfo()
    }

    private fun launchDownloadConsent(updateInfo: AppUpdateInfo) {
        action = Action.AwaitConsent
        val started = runCatching {
            appUpdateManager.startUpdateFlowForResult(updateInfo, downloadConsentLauncher, flexibleUpdateOptions)
        }.onSuccess { if (!it) log.w("checkForUpdate", "startFlexibleUpdate: false") }
            .getOrElse { cause ->
                log.w("checkForUpdate", "startFlexibleUpdate failed: $cause", cause)
                false
            }
        if (!started) {
            action = null
            requestUpdateInfo()
        }
    }

    private val StreamingModuleManager.State.allowsUpdateInstall: Boolean
        get() = this != StreamingModuleManager.State.Switching && this != StreamingModuleManager.State.Exiting

    private fun installDownloadedUpdate(streamInterruptionConfirmed: Boolean) {
        if (!canRunForegroundUpdate || playUpdateState != State.Downloaded) return
        val moduleState = streamingModulesManager.state.value
        if (!moduleState.allowsUpdateInstall) return
        val isStreaming = when (moduleState) {
            is StreamingModuleManager.State.Running -> moduleState.status.isStreaming
            is StreamingModuleManager.State.Unresponsive -> moduleState.lastStatus.isStreaming
            else -> false
        }
        if (isStreaming && !streamInterruptionConfirmed) {
            action = Action.ConfirmInstall
            publishState()
            return
        }

        if (verifyInstallOnRetry) {
            action = Action.CheckInstall
            requestUpdateInfo(bounded = true) { updateInfo ->
                action = null
                if (updateInfo?.toUpdateState() == State.Downloaded) {
                    verifyInstallOnRetry = false
                    installDownloadedUpdate(streamInterruptionConfirmed)
                    publishState()
                } else {
                    handleUpdateInfo(updateInfo)
                }
            }
            return
        }

        // Ignore results from lookups started before submitting the install.
        updateInfoJob?.cancel()
        val requestToken = Any()
        installRequestToken = requestToken
        action = Action.Install
        runCatching {
            appUpdateManager.completeUpdate().addOnCompleteListener { task ->
                if (installRequestToken !== requestToken) return@addOnCompleteListener
                installRequestToken = null
                if (!task.isSuccessful) {
                    handleInstallRequestFailure(task.exception)
                } else {
                    updateInfoJob?.cancel()
                    if (playUpdateState == State.Installing) clearRecoveryAction() else scheduleRecovery()
                    publishState()
                }
            }
        }.onFailure { cause ->
            if (installRequestToken === requestToken) handleInstallRequestFailure(cause)
        }
        publishState()
    }

    private fun handleInstallRequestFailure(cause: Throwable?) {
        updateInfoJob?.cancel()
        installRequestToken = null
        if (playUpdateState == State.Downloaded && (cause as? InstallException)?.errorCode == InstallErrorCode.ERROR_DOWNLOAD_NOT_PRESENT) {
            playUpdateState = State.Hidden
        }
        if (cause != null) log.w("completeUpdate", "Failed: $cause", cause)
        clearRecoveryAction()
        verifyInstallOnRetry = playUpdateState == State.Downloaded
        publishState()
        requestUpdateInfo()
    }

    private fun publishState() {
        state.value = when (action) {
            Action.CheckDownload, Action.AwaitConsent, Action.AwaitDownload ->
                if (playUpdateState == State.Available) State.Preparing else playUpdateState

            Action.ConfirmInstall -> State.ConfirmInstall
            Action.CheckInstall, Action.Install -> State.Installing
            null -> playUpdateState
        }
    }
}
