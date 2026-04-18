package info.dvkr.screenstream.common.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.R
import info.dvkr.screenstream.common.getLog

@Composable
internal fun MediaProjectionPermission(
    startAttemptId: String?,
    shouldShowEducationDialog: Boolean,
    onEducationConfirmed: () -> Unit,
    onEducationCancelled: () -> Unit,
    onPermissionGranted: (String, Intent) -> Unit,
    onPermissionDenied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
    val currentOnPermissionDenied by rememberUpdatedState(onPermissionDenied)
    var launchedPermissionRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val mediaProjectionPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { activityResult ->
            val requestId = launchedPermissionRequestId
            if (requestId == null) {
                XLog.i(activityResult.getLog("MediaProjectionPermission"), "MP_UI result id=none ignored")
                return@rememberLauncherForActivityResult
            }

            launchedPermissionRequestId = null
            val hasData = activityResult.data != null
            XLog.i(
                activityResult.getLog(
                    "MediaProjectionPermission",
                    "MP_UI result id=$requestId ok=${activityResult.resultCode == Activity.RESULT_OK} data=$hasData"
                )
            )

            if (activityResult.resultCode == Activity.RESULT_OK && hasData) {
                currentOnPermissionGranted(requestId, activityResult.data!!)
            } else {
                currentOnPermissionDenied(requestId)
            }
        }
    )

    LaunchedEffect(startAttemptId) {
        val requestId = startAttemptId ?: run {
            launchedPermissionRequestId = null
            return@LaunchedEffect
        }
        if (launchedPermissionRequestId == requestId) return@LaunchedEffect

        // TODO media projection for multi-display devices
        val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
        XLog.i(context.getLog("MediaProjectionPermission", "MP_UI launch id=$requestId"))
        launchedPermissionRequestId = requestId
        runCatching {
            mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }.onFailure {
            launchedPermissionRequestId = null
            XLog.e(context.getLog("MediaProjectionPermission", "MP_UI launch_failed id=$requestId"), it)
            currentOnPermissionDenied(requestId)
        }
    }

    if (shouldShowEducationDialog) {
        AlertDialog(
            onDismissRequest = onEducationCancelled,
            confirmButton = {
                TextButton(onClick = onEducationConfirmed) {
                    Text(text = stringResource(id = R.string.common_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onEducationCancelled) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            modifier = modifier,
            icon = { Icon(painter = painterResource(R.drawable.cast_warning_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.common_screen_capture_permission_required_title)) },
            text = { Text(text = stringResource(id = R.string.common_screen_capture_permission_education_message)) },
            shape = MaterialTheme.shapes.large
        )
    }
}
