package info.dvkr.screenstream.ui.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.annotation.StringRes
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction


abstract class PermissionActivity : BaseActivity() {

    companion object {
        private const val CAST_PERMISSION_PENDING_KEY = "CAST_PERMISSION_PENDING_KEY"
    }

    private val requestCodeScreenCapture = 10
    private var permissionsErrorDialog: MaterialDialog? = null
    private var isCastPermissionsPending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCastPermissionsPending = savedInstanceState?.getBoolean(CAST_PERMISSION_PENDING_KEY) ?: false
        XLog.d(getLog("onCreate", "isCastPermissionsPending: $isCastPermissionsPending"))
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        XLog.d(getLog("onSaveInstanceState", "isCastPermissionsPending: $isCastPermissionsPending"))
        outState?.putBoolean(CAST_PERMISSION_PENDING_KEY, isCastPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                if (serviceMessage.isWaitingForPermission.not()) isCastPermissionsPending = false
                else if (isCastPermissionsPending.not()) {
                    isCastPermissionsPending = true

                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    try {
                        startActivityForResult(projectionManager.createScreenCaptureIntent(), requestCodeScreenCapture)
                    } catch (ex: ActivityNotFoundException) {
                        showErrorDialog(
                            R.string.permission_activity_error_title_activity_not_found,
                            R.string.permission_activity_error_activity_not_found
                        )
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        XLog.d(getLog("onActivityResult", "requestCode: $requestCode"))

        if (requestCode != requestCodeScreenCapture) {
            XLog.e(getLog("onActivityResult"), IllegalStateException("Unknown requestCode: $requestCode"))
            showErrorDialog()
            return
        }

        if (Activity.RESULT_OK != resultCode) {
            XLog.w(getLog("onActivityResult", "Cast permission denied"))
            showErrorDialog(
                R.string.permission_activity_cast_permission_required_title,
                R.string.permission_activity_cast_permission_required
            )
            return
        }

        if (data == null) {
            XLog.e(getLog("onActivityResult"), IllegalStateException("onActivityResult: data = null"))
            showErrorDialog()
            return
        }

        XLog.d(getLog("onActivityResult", "Cast permission granted"))
        IntentAction.CastIntent(data).sendToAppService(this@PermissionActivity)
    }

    private fun showErrorDialog(
        @StringRes titleRes: Int = R.string.permission_activity_error_title,
        @StringRes messageRes: Int = R.string.permission_activity_error_unknown
    ) {
        permissionsErrorDialog?.dismiss()

        permissionsErrorDialog = MaterialDialog(this).show {
            lifecycleOwner(this@PermissionActivity)
            icon(R.drawable.ic_permission_dialog_24dp)
            title(titleRes)
            message(messageRes)
            cancelable(false)
            cancelOnTouchOutside(false)
            positiveButton(android.R.string.ok)
            onDismiss {
                IntentAction.CastPermissionsDenied.sendToAppService(this@PermissionActivity)
                isCastPermissionsPending = false
            }
        }
    }
}