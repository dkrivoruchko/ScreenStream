package info.dvkr.screenstream.ui.activity

import android.content.ActivityNotFoundException
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction


abstract class PermissionActivity(@LayoutRes contentLayoutId: Int) : ServiceActivity(contentLayoutId) {

    private companion object {
        private const val KEY_CAST_PERMISSION_PENDING = "KEY_CAST_PERMISSION_PENDING"
    }

    private var permissionsErrorDialog: MaterialDialog? = null
    private var isCastPermissionsPending: Boolean = false

    private val startMediaProjection =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                XLog.d(getLog("registerForActivityResult", "Cast permission granted"))
                IntentAction.CastIntent(result.data!!).sendToAppService(this@PermissionActivity)
            } else {
                XLog.w(getLog("registerForActivityResult", "Cast permission denied"))
                IntentAction.CastPermissionsDenied.sendToAppService(this@PermissionActivity)
                permissionsErrorDialog?.dismiss()
                showErrorDialog(
                    R.string.permission_activity_cast_permission_required_title,
                    R.string.permission_activity_cast_permission_required
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCastPermissionsPending = savedInstanceState?.getBoolean(KEY_CAST_PERMISSION_PENDING) ?: false
        XLog.d(getLog("onCreate", "isCastPermissionsPending: $isCastPermissionsPending"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        XLog.d(getLog("onSaveInstanceState", "isCastPermissionsPending: $isCastPermissionsPending"))
        outState.putBoolean(KEY_CAST_PERMISSION_PENDING, isCastPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        if (serviceMessage is ServiceMessage.ServiceState) {
            if (serviceMessage.isWaitingForPermission.not()) {
                isCastPermissionsPending = false
                return
            }

            if (isCastPermissionsPending) {
                XLog.i(getLog("onServiceMessage", "Ignoring: isCastPermissionsPending == true"))
                return
            }

            permissionsErrorDialog?.dismiss()
            isCastPermissionsPending = true
            try {
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                startMediaProjection.launch(projectionManager.createScreenCaptureIntent())
            } catch (ignore: ActivityNotFoundException) {
                IntentAction.CastPermissionsDenied.sendToAppService(this@PermissionActivity)
                showErrorDialog(
                    R.string.permission_activity_error_title_activity_not_found,
                    R.string.permission_activity_error_activity_not_found
                )
            }
        }
    }

    private fun showErrorDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
        permissionsErrorDialog = MaterialDialog(this).show {
            lifecycleOwner(this@PermissionActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(titleRes)
            message(messageRes)
            positiveButton(android.R.string.ok)
            cancelable(false)
            cancelOnTouchOutside(false)
            onDismiss { permissionsErrorDialog = null }
        }
    }
}