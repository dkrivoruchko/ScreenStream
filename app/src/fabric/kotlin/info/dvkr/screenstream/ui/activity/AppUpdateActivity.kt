package info.dvkr.screenstream.ui.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.Settings
import org.koin.android.ext.android.inject

abstract class AppUpdateActivity : AppCompatActivity() {

    companion object {
        private const val APP_UPDATE_FLEXIBLE_REQUEST_CODE = 159
    }

    protected val settings: Settings by inject()

    private val appUpdateManager by lazy { AppUpdateManagerFactory.create(this) }
    private val installStateListener = InstallStateUpdatedListener {
        if (it.installStatus() == InstallStatus.DOWNLOADED && isIAURequestTimeoutPassed())
            showUpdateConfirmationDialog()
    }

    private var isConfirmationDialogShowing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appUpdateManager.registerListener(installStateListener)

        if (isIAURequestTimeoutPassed())
            appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    XLog.d(getLog("appUpdateInfo", "startUpdateFlowForResult"))
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo, AppUpdateType.FLEXIBLE, this, APP_UPDATE_FLEXIBLE_REQUEST_CODE
                    )
                }
            }
                .addOnCompleteListener {
                    settings.lastIAURequestTimeStamp = System.currentTimeMillis()
                }
    }

//    This cause memory leaks
//    override fun onResume() {
//        super.onResume()
//        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
//            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED && isIAURequestTimeoutPassed())
//                showUpdateConfirmationDialog()
//        }
//    }

    override fun onDestroy() {
        appUpdateManager.unregisterListener(installStateListener)
        super.onDestroy()
    }

    private fun showUpdateConfirmationDialog() {
        XLog.d(getLog("showUpdateConfirmationDialog", "Invoked"))
        if (isConfirmationDialogShowing) return
        isConfirmationDialogShowing = true

        MaterialDialog(this)
            .lifecycleOwner(this)
            .title(R.string.app_update_activity_dialog_title)
            .icon(R.drawable.ic_permission_dialog_24dp)
            .message(R.string.app_update_activity_dialog_message)
            .positiveButton(R.string.app_update_activity_dialog_restart) {
                settings.lastIAURequestTimeStamp = System.currentTimeMillis()
                appUpdateManager.completeUpdate()
            }
            .negativeButton(android.R.string.cancel) {
                settings.lastIAURequestTimeStamp = System.currentTimeMillis()
            }
            .cancelOnTouchOutside(false)
            .show()
    }

    private fun isIAURequestTimeoutPassed(): Boolean =
        // 8 hours. Don't need exact time frame
        System.currentTimeMillis() - settings.lastIAURequestTimeStamp >= 8 * 60 * 60 * 1000L
}