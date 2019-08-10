package info.dvkr.screenstream.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.tasks.Task
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.coroutines.suspendCoroutine

abstract class AppUpdateActivity : BaseActivity() {

    companion object {
        private const val APP_UPDATE_FLEXIBLE_PENDING_KEY = "APP_UPDATE_FLEXIBLE_PENDING_KEY"
        private const val APP_UPDATE_FLEXIBLE_REQUEST_CODE = 15
    }

    protected val settings: Settings by inject()

    private var isAppUpdatePending: Boolean = false
    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val installStateListener = InstallStateUpdatedListener {
        if (it.installStatus() == InstallStatus.DOWNLOADED && isIAURequestTimeoutPassed()) {
            settings.lastIAURequestTimeStamp = System.currentTimeMillis()
            showUpdateConfirmationDialog()
        }
    }
    private var appUpdateConfirmationDialog: MaterialDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAppUpdatePending = savedInstanceState?.getBoolean(APP_UPDATE_FLEXIBLE_PENDING_KEY) ?: false
        XLog.d(getLog("onCreate", "isAppUpdatePending: $isAppUpdatePending"))

        appUpdateManager.registerListener(installStateListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        XLog.d(getLog("onSaveInstanceState", "isAppUpdatePending: $isAppUpdatePending"))
        outState.putBoolean(APP_UPDATE_FLEXIBLE_PENDING_KEY, isAppUpdatePending)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        isIAURequestTimeoutPassed() || return

        launch(coroutineContext + CoroutineExceptionHandler { _, throwable ->
            XLog.w(getLog("onCoroutineException", throwable.toString()))
        }) {
            val appUpdateInfo = appUpdateManager.appUpdateInfo.await()
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                settings.lastIAURequestTimeStamp = System.currentTimeMillis()
                showUpdateConfirmationDialog()
            }

            if (isAppUpdatePending.not())
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    XLog.d(this@AppUpdateActivity.getLog("appUpdateInfo", "startUpdateFlowForResult"))
                    isAppUpdatePending = true
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo, AppUpdateType.FLEXIBLE, this@AppUpdateActivity, APP_UPDATE_FLEXIBLE_REQUEST_CODE
                    )
                }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == APP_UPDATE_FLEXIBLE_REQUEST_CODE) {
            isAppUpdatePending = false
            if (resultCode == Activity.RESULT_OK) {
                XLog.d(getLog("onActivityResult", "Update permitted"))
            } else {
                XLog.d(getLog("onActivityResult", "Update canceled"))
                settings.lastIAURequestTimeStamp = System.currentTimeMillis()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        appUpdateManager.unregisterListener(installStateListener)
        super.onDestroy()
    }

    private fun showUpdateConfirmationDialog() {
        XLog.d(getLog("showUpdateConfirmationDialog", "Invoked"))

        appUpdateConfirmationDialog?.dismiss()

        appUpdateConfirmationDialog = MaterialDialog(this).show {
            lifecycleOwner(this@AppUpdateActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.app_update_activity_dialog_title)
            message(R.string.app_update_activity_dialog_message)
            positiveButton(R.string.app_update_activity_dialog_restart) { appUpdateManager.completeUpdate() }
            negativeButton(android.R.string.cancel)
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }

    private suspend fun Task<AppUpdateInfo>.await(): AppUpdateInfo = suspendCoroutine { continuation ->
        addOnCompleteListener { task ->
            continuation.resumeWith(
                if (task.isSuccessful) Result.success(task.result)
                else Result.failure(task.exception)
            )
        }
    }

    private fun isIAURequestTimeoutPassed(): Boolean =
        // 8 hours. Don't need exact time frame
        System.currentTimeMillis() - settings.lastIAURequestTimeStamp >= 8 * 60 * 60 * 1000L
}