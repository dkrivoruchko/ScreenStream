package info.dvkr.screenstream.notification

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.isPermissionGranted
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.common.shouldShowPermissionRationale
import org.koin.compose.koinInject

@Composable
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun NotificationPermission(
    permission: String = Manifest.permission.POST_NOTIFICATIONS,
    notificationHelper: NotificationHelper = koinInject()
) {
    val context = LocalContext.current
    if (context.isPermissionGranted(permission)) return

    val activity = remember(context) { context.findActivity() }
    val showRationaleDialog = rememberSaveable { mutableStateOf(false) }
    val showSettingsDialog = rememberSaveable { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showRationaleDialog.value = false
            showSettingsDialog.value = false
        } else {
            val showRationale = activity.shouldShowPermissionRationale(permission)
            showRationaleDialog.value = showRationale
            showSettingsDialog.value = showRationale.not()
        }
    }

    val permissionCheckerObserver = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val isGranted = context.isPermissionGranted(permission)
                if (isGranted.not() && showRationaleDialog.value.not() && showSettingsDialog.value.not()) {
                    requestPermissionLauncher.launch(permission)
                }
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, permissionCheckerObserver) {
        lifecycle.addObserver(permissionCheckerObserver)
        onDispose { lifecycle.removeObserver(permissionCheckerObserver) }
    }

    if (showRationaleDialog.value) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationaleDialog.value = false
                        requestPermissionLauncher.launch(permission)
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.info_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.app_permission_required_title)) },
            text = { Text(text = stringResource(id = R.string.app_permission_allow_notifications)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    if (showSettingsDialog.value) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        showSettingsDialog.value = false
                        context.startActivity(notificationHelper.getNotificationSettingsIntent())
                    }
                ) {
                    Text(text = stringResource(id = R.string.app_permission_notification_settings))
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.info_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.app_permission_required_title)) },
            text = { Text(text = stringResource(id = R.string.app_permission_allow_notifications_settings)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}