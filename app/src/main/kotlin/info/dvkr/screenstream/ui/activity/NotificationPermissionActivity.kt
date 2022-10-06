package info.dvkr.screenstream.ui.activity

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.service.helper.NotificationHelper
import org.koin.android.ext.android.inject


abstract class NotificationPermissionActivity(@LayoutRes contentLayoutId: Int) : ServiceActivity(contentLayoutId) {

    private val notificationHelper: NotificationHelper by inject()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        XLog.d(getLog("requestPermissionLauncher", "registerForActivityResult: $isGranted"))

        if (isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@registerForActivityResult

        val deniedAndDisabled = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not()
        XLog.d(getLog("requestPermissionLauncher", "deniedAndDisabled: $deniedAndDisabled"))

        showMandatoryDialog(deniedAndDisabled)
    }

    override fun onStart() {
        super.onStart()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isNotificationPermissionGranted().not()) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    protected fun isNotificationPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun showMandatoryDialog(showSettings: Boolean) {
        XLog.d(getLog("showMandatoryDialog", "showSettings: $showSettings"))

        MaterialDialog(this).show {
            lifecycleOwner(this@NotificationPermissionActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(R.string.permission_activity_permission_required_title)
            if (showSettings) {
                message(R.string.permission_activity_allow_notifications_settings)
                positiveButton(R.string.permission_activity_notification_settings) {
                    try {
                        startActivity(notificationHelper.getNotificationSettingsIntent())
                    } catch (cause: ActivityNotFoundException) {
                        XLog.e(getLog("showMandatoryDialog", "showSettings: $showSettings: $cause"), cause)
                    }
                }
            } else {
                message(R.string.permission_activity_allow_notifications)
                positiveButton(android.R.string.ok) { requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }
}