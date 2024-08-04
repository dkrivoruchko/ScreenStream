package info.dvkr.screenstream.common.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.elvishew.xlog.XLog
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
    val mediaProjectionRequested = rememberSaveable { mutableStateOf(false) }
    val showMediaProjectionPermissionErrorDialog = rememberSaveable { mutableStateOf(false) }
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
            icon = { Icon(Icon_CastConnected, contentDescription = null) },
            title = { Text(text = requiredDialogTitle) },
            text = { Text(text = requiredDialogText) },
            shape = MaterialTheme.shapes.large
        )
    }
}

private val Icon_CastConnected: ImageVector = materialIcon(name = "Filled.CastConnected") {
    materialPath {
        moveTo(1.0f, 18.0f)
        verticalLineToRelative(3.0f)
        horizontalLineToRelative(3.0f)
        curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
        close()
        moveTo(1.0f, 14.0f)
        verticalLineToRelative(2.0f)
        curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
        horizontalLineToRelative(2.0f)
        curveToRelative(0.0f, -3.87f, -3.13f, -7.0f, -7.0f, -7.0f)
        close()
        moveTo(19.0f, 7.0f)
        lineTo(5.0f, 7.0f)
        verticalLineToRelative(1.63f)
        curveToRelative(3.96f, 1.28f, 7.09f, 4.41f, 8.37f, 8.37f)
        lineTo(19.0f, 17.0f)
        lineTo(19.0f, 7.0f)
        close()
        moveTo(1.0f, 10.0f)
        verticalLineToRelative(2.0f)
        curveToRelative(4.97f, 0.0f, 9.0f, 4.03f, 9.0f, 9.0f)
        horizontalLineToRelative(2.0f)
        curveToRelative(0.0f, -6.08f, -4.93f, -11.0f, -11.0f, -11.0f)
        close()
        moveTo(21.0f, 3.0f)
        lineTo(3.0f, 3.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(3.0f)
        horizontalLineToRelative(2.0f)
        lineTo(3.0f, 5.0f)
        horizontalLineToRelative(18.0f)
        verticalLineToRelative(14.0f)
        horizontalLineToRelative(-7.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(7.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        lineTo(23.0f, 5.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
    }
}