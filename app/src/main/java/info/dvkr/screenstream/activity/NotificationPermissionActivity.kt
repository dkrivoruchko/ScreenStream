package info.dvkr.screenstream.activity

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.NotificationHelper
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.ui.activity.AdActivity
import org.koin.android.ext.android.inject

public abstract class NotificationPermissionActivity(@LayoutRes contentLayoutId: Int) : AdActivity(contentLayoutId) {

    private val notificationHelper: NotificationHelper by inject(mode = LazyThreadSafetyMode.NONE)

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        XLog.d(getLog("requestPermissionLauncher", "registerForActivityResult: $isGranted"))

        if (isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@registerForActivityResult

        val deniedAndDisabled = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not()
        XLog.d(getLog("requestPermissionLauncher", "deniedAndDisabled: $deniedAndDisabled"))

        showPermissionsMandatoryDialog(deniedAndDisabled)
    }

    @SuppressLint("InlinedApi")
    override fun onStart() {
        super.onStart()
        when {
            notificationHelper.notificationPermissionGranted(this).not() ->
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

            notificationHelper.areNotificationsEnabled().not() ->
                showNotificationMandatoryDialog()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun showPermissionsMandatoryDialog(showSettings: Boolean) {
        XLog.d(getLog("showPermissionsMandatoryDialog", "showSettings: $showSettings"))

        MaterialDialog(this).show {
            lifecycleOwner(this@NotificationPermissionActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.app_activity_permission_permission_required_title)
            if (showSettings) {
                message(R.string.app_activity_permission_allow_notifications_settings)
                positiveButton(R.string.app_activity_permission_notification_settings) {
                    startActivity(notificationHelper.getNotificationSettingsIntent(this@NotificationPermissionActivity))
                }
            } else {
                message(R.string.app_activity_permission_allow_notifications)
                positiveButton(android.R.string.ok) { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun showNotificationMandatoryDialog() {
        XLog.d(getLog("showNotificationMandatoryDialog"))

        MaterialDialog(this).show {
            lifecycleOwner(this@NotificationPermissionActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.app_activity_permission_notifications_required_title)
            message(R.string.app_activity_permission_enable_notifications)
            positiveButton(R.string.app_activity_permission_notification_settings) {
                startActivity(notificationHelper.getNotificationSettingsIntent(this@NotificationPermissionActivity))
            }
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }
}