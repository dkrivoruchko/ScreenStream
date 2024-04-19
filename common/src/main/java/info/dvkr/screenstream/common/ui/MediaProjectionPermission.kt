package info.dvkr.screenstream.common.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import androidx.core.content.ContextCompat

@Composable
public fun MediaProjectionPermission(
    requestCastPermission: Boolean,
    onPermissionGranted: (Intent) -> Unit,
    onPermissionDenied: () -> Unit,
    requiredDialogTitle: String,
    requiredDialogText: String,
    modifier: Modifier = Modifier,
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
        LaunchedEffect(true) {
            val projectionManager = ContextCompat.getSystemService(context, MediaProjectionManager::class.java)!!
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // TODO https://developer.android.com/reference/android/media/projection/package-summary
                // TODO MediaProjectionConfig.createConfigForDefaultDisplay()
                // https://developer.android.com/about/versions/14/features/partial-screen-sharing#media_projection_callbacks
                // https://developer.android.com/about/versions/14/behavior-changes-14#media-projection-consent
                projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                projectionManager.createScreenCaptureIntent()
            }
            mediaProjectionPermissionLauncher.launch(intent)

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