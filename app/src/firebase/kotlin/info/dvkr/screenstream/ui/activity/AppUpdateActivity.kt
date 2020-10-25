package info.dvkr.screenstream.ui.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    companion object {
        private const val APP_UPDATE_PENDING_KEY = "info.dvkr.screenstream.key.APP_UPDATE_PENDING"
        private const val APP_UPDATE_FLEXIBLE_REQUEST_CODE = 15
    }

    protected val settings: Settings by inject()

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private var isAppUpdatePending: Boolean = false
    private var appUpdateConfirmationDialog: MaterialDialog? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.w(getLog("onCoroutineException", throwable.toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAppUpdatePending = savedInstanceState?.getBoolean(APP_UPDATE_PENDING_KEY) ?: false
        XLog.d(getLog("onCreate", "isAppUpdatePending: $isAppUpdatePending"))

        lifecycleScope.launch(exceptionHandler) {
            appUpdateManager.requestUpdateFlow().onEach { updateResult ->
                ensureActive()
                if (isAppUpdatePending.not() && isIAURequestTimeoutPassed() &&
                    updateResult is AppUpdateResult.Available && updateResult.updateInfo.isFlexibleUpdateAllowed
                ) {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateManager", "startUpdateFlowForResult"))
                    isAppUpdatePending = true
                    appUpdateManager.startUpdateFlowForResult(
                        updateResult.updateInfo,
                        AppUpdateType.FLEXIBLE,
                        this@AppUpdateActivity,
                        APP_UPDATE_FLEXIBLE_REQUEST_CODE
                    )
                }

                if (updateResult is AppUpdateResult.Downloaded && isIAURequestTimeoutPassed()) showUpdateConfirmationDialog()
            }
                .catch { cause -> XLog.e(getLog("AppUpdateManager.catch: $cause")) }
                .collect()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        XLog.d(getLog("onSaveInstanceState", "isAppUpdatePending: $isAppUpdatePending"))
        outState.putBoolean(APP_UPDATE_PENDING_KEY, isAppUpdatePending)
        super.onSaveInstanceState(outState)
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

    private fun showUpdateConfirmationDialog() {
        XLog.d(getLog("showUpdateConfirmationDialog", "Invoked"))

        appUpdateConfirmationDialog?.dismiss()

        appUpdateConfirmationDialog = MaterialDialog(this).show {
            lifecycleOwner(this@AppUpdateActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.app_update_activity_dialog_title)
            message(R.string.app_update_activity_dialog_message)
            positiveButton(R.string.app_update_activity_dialog_restart) {
                XLog.d(this@AppUpdateActivity.getLog("showUpdateConfirmationDialog", "positiveAction"))
                dismiss()
                appUpdateManager.completeUpdate()
            }
            negativeButton(android.R.string.cancel) {
                XLog.d(this@AppUpdateActivity.getLog("showUpdateConfirmationDialog", "negativeAction"))
                dismiss()
                settings.lastIAURequestTimeStamp = System.currentTimeMillis()
            }
            cancelable(false)
            cancelOnTouchOutside(false)
            noAutoDismiss()
            onDismiss { appUpdateConfirmationDialog = null }
        }
    }

    private fun isIAURequestTimeoutPassed(): Boolean =
        // 8 hours. Don't need exact time frame
        System.currentTimeMillis() - settings.lastIAURequestTimeStamp >= 8 * 60 * 60 * 1000L
}