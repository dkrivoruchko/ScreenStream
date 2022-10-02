package info.dvkr.screenstream.ui.activity

import android.content.ActivityNotFoundException
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction


abstract class CastPermissionActivity(@LayoutRes contentLayoutId: Int) : NotificationPermissionActivity(contentLayoutId) {

    private companion object {
        private const val KEY_CAST_PERMISSION_PENDING = "KEY_CAST_PERMISSION_PENDING"
    }

    private var permissionsErrorDialog: MaterialDialog? = null
    private var castPermissionsPending: Boolean = false

    private val startMediaProjection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            XLog.d(getLog("registerForActivityResult", "Cast permission granted"))
            IntentAction.CastIntent(result.data!!).sendToAppService(this@CastPermissionActivity)
        } else {
            XLog.w(getLog("registerForActivityResult", "Cast permission denied"))
            IntentAction.CastPermissionsDenied.sendToAppService(this@CastPermissionActivity)
            permissionsErrorDialog?.dismiss()
            showErrorDialog(
                R.string.permission_activity_permission_required_title,
                R.string.permission_activity_cast_permission_required
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        castPermissionsPending = savedInstanceState?.getBoolean(KEY_CAST_PERMISSION_PENDING) ?: false
        XLog.d(getLog("onCreate", "castPermissionsPending: $castPermissionsPending"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        XLog.d(getLog("onSaveInstanceState", "castPermissionsPending: $castPermissionsPending"))
        outState.putBoolean(KEY_CAST_PERMISSION_PENDING, castPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        if (serviceMessage is ServiceMessage.ServiceState) {
            if (serviceMessage.waitingForCastPermission.not()) {
                castPermissionsPending = false
                return
            }

            if (castPermissionsPending) {
                XLog.i(getLog("onServiceMessage", "Ignoring: castPermissionsPending == true"))
                return
            }

            permissionsErrorDialog?.dismiss()
            castPermissionsPending = true
            try {
                val projectionManager = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)!!
                startMediaProjection.launch(projectionManager.createScreenCaptureIntent())
            } catch (ignore: ActivityNotFoundException) {
                IntentAction.CastPermissionsDenied.sendToAppService(this@CastPermissionActivity)
                showErrorDialog(
                    R.string.permission_activity_error_title_activity_not_found,
                    R.string.permission_activity_error_activity_not_found
                )
            }
        }
    }

    private fun showErrorDialog(@StringRes titleRes: Int, @StringRes messageRes: Int) {
        permissionsErrorDialog = MaterialDialog(this).show {
            lifecycleOwner(this@CastPermissionActivity)
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