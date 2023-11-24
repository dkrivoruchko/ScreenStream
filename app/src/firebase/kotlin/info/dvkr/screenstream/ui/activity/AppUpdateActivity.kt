package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.ktx.AppUpdateResult
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.requestUpdateFlow
import info.dvkr.screenstream.R
import info.dvkr.screenstream.activity.BaseActivity
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.settings.AppSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

public abstract class AppUpdateActivity(@LayoutRes contentLayoutId: Int) : BaseActivity(contentLayoutId) {

    private companion object {
        private const val APP_UPDATE_REQUEST_TIMEOUT = 8 * 60 * 60 * 1000L  // 8 hours. Don't need exact time frame
    }

    protected val appSettings: AppSettings by inject(mode = LazyThreadSafetyMode.NONE)
    private var appUpdateConfirmationDialog: MaterialDialog? = null

    private val updateResultLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK)
            XLog.w(this@AppUpdateActivity.getLog("AppUpdateResult.updateResultLauncher", "Failed: ${result.resultCode}"))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppUpdateManagerFactory.create(this).requestUpdateFlow().onEach { appUpdateResult ->
            when (appUpdateResult) {
                AppUpdateResult.NotAvailable -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.NotAvailable"))
                }

                is AppUpdateResult.Available -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available"))
                    if (appUpdateResult.updateInfo.isFlexibleUpdateAllowed) {
                        XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "FlexibleUpdateAllowed"))
                        val lastRequestMillisPassed = System.currentTimeMillis() - appSettings.lastUpdateRequestMillisFlow.first()
                        if (lastRequestMillisPassed >= APP_UPDATE_REQUEST_TIMEOUT) {
                            XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Available", "startFlexibleUpdate"))
                            appSettings.setLastUpdateRequestMillis(System.currentTimeMillis())
                            appUpdateResult.startFlexibleUpdate(updateResultLauncher)
                        }
                    }
                }

                is AppUpdateResult.InProgress -> {
                    XLog.v(this@AppUpdateActivity.getLog("AppUpdateResult.InProgress", appUpdateResult.installState.toString()))
                }

                is AppUpdateResult.Downloaded -> {
                    XLog.d(this@AppUpdateActivity.getLog("AppUpdateResult.Downloaded"))
                    showUpdateConfirmationDialog(appUpdateResult)
                }
            }
        }
            .catch { cause -> XLog.i(this@AppUpdateActivity.getLog("AppUpdateManager.requestUpdateFlow.catch: $cause")) }
            .launchIn(lifecycleScope)
    }

    private fun showUpdateConfirmationDialog(appUpdateResult: AppUpdateResult.Downloaded) {
        XLog.d(getLog("showUpdateConfirmationDialog"))

        appUpdateConfirmationDialog?.dismiss()
        appUpdateConfirmationDialog = MaterialDialog(this).show {
            lifecycleOwner(this@AppUpdateActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.app_activity_update_dialog_title)
            message(R.string.app_activity_update_dialog_message)
            positiveButton(R.string.app_activity_update_dialog_restart) {
                dismiss()
                onUpdateConfirmationDialogClick(appUpdateResult, true)
            }
            negativeButton(android.R.string.cancel) {
                dismiss()
                onUpdateConfirmationDialogClick(appUpdateResult, false)
            }
            cancelable(false)
            cancelOnTouchOutside(false)
            noAutoDismiss()
            onDismiss { appUpdateConfirmationDialog = null }
        }
    }

    private fun onUpdateConfirmationDialogClick(appUpdateResult: AppUpdateResult.Downloaded, isPositive: Boolean) {
        lifecycleScope.launch {
            XLog.d(this@AppUpdateActivity.getLog("onUpdateConfirmationDialogClick", "isPositive: $isPositive"))
            if (isPositive) appUpdateResult.completeUpdate()
        }
    }
}