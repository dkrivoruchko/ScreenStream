package info.dvkr.screenstream.network

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.getAppSettingsIntent
import info.dvkr.screenstream.common.isLocalNetworkPermissionGranted
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.common.shouldShowPermissionRationale
import org.koin.compose.koinInject

@Composable
@RequiresApi(37)
@SuppressLint("InlinedApi")
internal fun LocalNetworkPermission(
    permission: String = Manifest.permission.ACCESS_LOCAL_NETWORK,
    streamingModuleManager: StreamingModuleManager = koinInject(),
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val selectedModuleId = streamingModuleManager.selectedModuleIdFlow
        .collectAsStateWithLifecycle(initialValue = AppSettings.Default.STREAMING_MODULE)
    val activeModule = streamingModuleManager.activeModuleStateFlow.collectAsStateWithLifecycle()
    val currentActiveModule by rememberUpdatedState(activeModule.value)

    val selectedModule: StreamingModule? = remember(selectedModuleId.value, streamingModuleManager.modules) {
        streamingModuleManager.modules.firstOrNull { it.id == selectedModuleId.value }
    }
    if (selectedModule?.requiresLocalNetworkPermission != true) return

    val activity = remember(context) { context.findActivity() }
    var permissionUiState by rememberSaveable { mutableStateOf(PermissionUiState.Idle) }
    var hadPermission by rememberSaveable { mutableStateOf(context.isLocalNetworkPermissionGranted()) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            hadPermission = true
            permissionUiState = PermissionUiState.Idle
            currentActiveModule?.recoverError()
        } else {
            hadPermission = false
            permissionUiState = if (activity.shouldShowPermissionRationale(permission)) {
                PermissionUiState.Rationale
            } else {
                PermissionUiState.Settings
            }
        }
    }

    fun requestPermissionIfNeeded() {
        if (enabled.not()) return
        if (context.isLocalNetworkPermissionGranted()) return
        if (permissionUiState != PermissionUiState.Idle) return
        requestPermissionLauncher.launch(permission)
    }

    LaunchedEffect(selectedModuleId.value, enabled, permissionUiState) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requestPermissionIfNeeded()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val isPermissionGranted = context.isLocalNetworkPermissionGranted()
        when {
            isPermissionGranted -> {
                if (hadPermission.not() || permissionUiState == PermissionUiState.OpenedSettings) {
                    currentActiveModule?.recoverError()
                }
                hadPermission = true
                permissionUiState = PermissionUiState.Idle
            }

            enabled.not() -> hadPermission = false

            permissionUiState == PermissionUiState.OpenedSettings -> {
                hadPermission = false
                permissionUiState = PermissionUiState.Settings
            }

            else -> {
                hadPermission = false
                requestPermissionIfNeeded()
            }
        }
    }

    if (context.isLocalNetworkPermissionGranted() && permissionUiState != PermissionUiState.OpenedSettings) return

    if (permissionUiState == PermissionUiState.Rationale) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionUiState = PermissionUiState.Idle
                        requestPermissionLauncher.launch(permission)
                    }
                ) {
                    Text(text = stringResource(id = R.string.app_permission_try_again))
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.info_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.app_permission_local_network_title)) },
            text = { Text(text = stringResource(id = R.string.app_permission_local_network_message)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }

    if (permissionUiState == PermissionUiState.Settings) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionUiState = PermissionUiState.OpenedSettings
                        context.startActivity(context.getAppSettingsIntent())
                    }
                ) {
                    Text(text = stringResource(id = R.string.app_permission_open_settings))
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.info_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.app_permission_local_network_off_title)) },
            text = { Text(text = stringResource(id = R.string.app_permission_local_network_settings_message)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

private enum class PermissionUiState {
    Idle,
    Rationale,
    Settings,
    OpenedSettings
}
