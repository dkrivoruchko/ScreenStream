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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.R
import info.dvkr.screenstream.common.getLog

@Composable
public fun MediaProjectionPermission(
    shouldRequestPermission: Boolean,
    onPermissionGranted: (Intent) -> Unit,
    onPermissionDenied: () -> Unit,
    requiredDialogTitle: String,
    requiredDialogText: String,
    modifier: Modifier = Modifier
) {
    val mediaProjectionRequested = retain { mutableStateOf(false) }
    val showMediaProjectionPermissionErrorDialog = retain { mutableStateOf(false) }
    val mediaProjectionPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { activityResult ->
            if (shouldRequestPermission.not()) {
                XLog.i(activityResult.getLog("MediaProjectionPermission"), IllegalStateException("MediaProjectionPermission: ignoring result"))
                return@rememberLauncherForActivityResult
            }
            if (activityResult.resultCode == Activity.RESULT_OK) {
                onPermissionGranted.invoke(activityResult.data!!)
            } else {
                onPermissionDenied.invoke()
                showMediaProjectionPermissionErrorDialog.value = true
            }
        }
    )

    if (shouldRequestPermission) {
        if (mediaProjectionRequested.value.not()) {
            val context = LocalContext.current
            SideEffect {
                // TODO media projection for multi-display devices
                val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
                mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())

                showMediaProjectionPermissionErrorDialog.value = false
                mediaProjectionRequested.value = true
            }
        }
    } else {
        mediaProjectionRequested.value = false
    }

    if (showMediaProjectionPermissionErrorDialog.value) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { showMediaProjectionPermissionErrorDialog.value = false }) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            modifier = modifier,
            icon = { Icon(painter = painterResource(R.drawable.cast_warning_24px), contentDescription = null) },
            title = { Text(text = requiredDialogTitle) },
            text = { Text(text = requiredDialogText) },
            shape = MaterialTheme.shapes.large
        )
    }
}