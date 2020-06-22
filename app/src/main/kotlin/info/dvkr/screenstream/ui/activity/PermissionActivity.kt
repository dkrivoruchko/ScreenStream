package info.dvkr.screenstream.ui.activity

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.lifecycle.lifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.service.ServiceMessage
import info.dvkr.screenstream.service.helper.IntentAction


abstract class PermissionActivity(@LayoutRes contentLayoutId: Int) : ServiceActivity(contentLayoutId) {

    companion object {
        private const val CAST_PERMISSION_PENDING_KEY = "CAST_PERMISSION_PENDING_KEY"
        private const val SCREEN_CAPTURE_REQUEST_CODE = 10
    }

    private var permissionsErrorDialog: MaterialDialog? = null
    private var isCastPermissionsPending: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCastPermissionsPending = savedInstanceState?.getBoolean(CAST_PERMISSION_PENDING_KEY) ?: false
        XLog.d(getLog("onCreate", "isCastPermissionsPending: $isCastPermissionsPending"))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        XLog.d(getLog("onSaveInstanceState", "isCastPermissionsPending: $isCastPermissionsPending"))
        outState.putBoolean(CAST_PERMISSION_PENDING_KEY, isCastPermissionsPending)
        super.onSaveInstanceState(outState)
    }

    override fun onServiceMessage(serviceMessage: ServiceMessage) {
        super.onServiceMessage(serviceMessage)

        when (serviceMessage) {
            is ServiceMessage.ServiceState -> {
                if (serviceMessage.isWaitingForPermission) {
                    if (isCastPermissionsPending) {
                        XLog.i(getLog("onServiceMessage", "Ignoring: isCastPermissionsPending == true"))
                    } else {
                        isCastPermissionsPending = true
                        permissionsErrorDialog?.dismiss()
                        permissionsErrorDialog = null
                        val projectionManager =
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        try {
                            startActivityForResult(
                                projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST_CODE
                            )
                        } catch (ex: ActivityNotFoundException) {
                            showErrorDialog(
                                R.string.permission_activity_error_title_activity_not_found,
                                R.string.permission_activity_error_activity_not_found
                            )
                        }
                    }
                } else {
                    isCastPermissionsPending = false
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                XLog.d(getLog("onActivityResult", "Cast permission granted"))
                require(data != null) { "onActivityResult: data = null" }
                IntentAction.CastIntent(data).sendToAppService(this@PermissionActivity)
            } else {
                XLog.w(getLog("onActivityResult", "Cast permission denied"))

                IntentAction.CastPermissionsDenied.sendToAppService(this@PermissionActivity)
                isCastPermissionsPending = false

                showErrorDialog(
                    R.string.permission_activity_cast_permission_required_title,
                    R.string.permission_activity_cast_permission_required
                )
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
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
            positiveButton(android.R.string.ok)
            cancelable(false)
            cancelOnTouchOutside(false)
        }
    }
}