package info.dvkr.screenstream.common.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

@Composable
public fun MediaProjectionPermission(
    requestCastPermission: Boolean,
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
            if (activityResult.resultCode == Activity.RESULT_OK) {
                onPermissionGranted.invoke(activityResult.data!!)
            } else {
                onPermissionDenied.invoke()
                showMediaProjectionPermissionErrorDialog.value = true
            }
        }
    )

    if (requestCastPermission.not()) mediaProjectionRequested.value = false

    if (requestCastPermission && mediaProjectionRequested.value.not()) {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            // TODO media projection for multi-display devices
            val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
            mediaProjectionPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())

            showMediaProjectionPermissionErrorDialog.value = false
            mediaProjectionRequested.value = true
        }
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
            icon = { Icon(Icons.Default.CastConnected, contentDescription = null) },
            title = { Text(text = requiredDialogTitle) },
            text = { Text(text = requiredDialogText) },
            shape = MaterialTheme.shapes.large
        )
    }
}