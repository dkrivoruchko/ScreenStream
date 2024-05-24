package info.dvkr.screenstream.webrtc.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.isPermissionGranted
import info.dvkr.screenstream.common.shouldShowPermissionRationale
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun AudioCard(
    webRtcState: State<WebRtcState>,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
    webRtcSettings: WebRtcSettings = koinInject()
) {
    val isStreaming = remember { derivedStateOf { webRtcState.value.isStreaming } }

    val webRtcSettingsState = webRtcSettings.data.collectAsStateWithLifecycle()
    val enableMic = remember { derivedStateOf { webRtcSettingsState.value.enableMic } }
    val enableDeviceAudio = remember { derivedStateOf { webRtcSettingsState.value.enableDeviceAudio } }

    val context = LocalContext.current
    val showRecordAudioPermission = rememberSaveable { mutableStateOf(false) }
    val onPermissionsResult = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    ExpandableCard(
        headerContent = {
            Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 48.dp)) {
                Text(text = stringResource(id = R.string.webrtc_stream_audio_select), style = MaterialTheme.typography.titleMedium)
            }
        },
        modifier = modifier,
        initiallyExpanded = true
    ) {
        AudioSource(
            text = stringResource(id = R.string.webrtc_stream_audio_mic),
            selected = enableMic.value,
            enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isStreaming.value.not(),
            onChange = { enabled ->
                if (enabled.not() || context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                    scope.launch { webRtcSettings.updateData { copy(enableMic = enabled) } }
                } else {
                    onPermissionsResult.value = { isGranted ->
                        scope.launch { webRtcSettings.updateData { copy(enableMic = isGranted) } }
                    }
                    showRecordAudioPermission.value = true
                }
            }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioSource(
                text = stringResource(id = R.string.webrtc_stream_audio_device),
                selected = enableDeviceAudio.value,
                enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isStreaming.value.not(),
                onChange = { enabled ->
                    if (enabled.not() || context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                        scope.launch { webRtcSettings.updateData { copy(enableDeviceAudio = enabled) } }
                    } else {
                        onPermissionsResult.value = { isGranted ->
                            scope.launch { webRtcSettings.updateData { copy(enableDeviceAudio = isGranted) } }
                        }
                        showRecordAudioPermission.value = true
                    }
                }
            )
        }
    }

    if (showRecordAudioPermission.value) {
        RequestPermission { isGranted ->
            onPermissionsResult.value?.invoke(isGranted)
            onPermissionsResult.value = null
            showRecordAudioPermission.value = false
        }
    }
}

@Composable
private fun AudioSource(
    text: String,
    enabled: Boolean,
    selected: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .conditional(enabled) { toggleable(value = selected, onValueChange = { onChange.invoke(selected.not()) }) }
            .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, modifier = Modifier.weight(1F))
        Switch(checked = selected, onCheckedChange = null, modifier = Modifier.scale(0.7F), enabled = enabled)
    }
}

@Composable
private fun RequestPermission(
    permission: String = Manifest.permission.RECORD_AUDIO,
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    if (context.isPermissionGranted(permission)) {
        onResult.invoke(true)
        return
    }

    val activity = remember(context) { context.findActivity() }
    val showRationaleDialog = rememberSaveable { mutableStateOf(false) }
    val showSettingsDialog = rememberSaveable { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted.not()) {
            val showRationale = activity.shouldShowPermissionRationale(permission)
            showRationaleDialog.value = showRationale
            showSettingsDialog.value = showRationale.not()
        }
    }

    val permissionCheckerObserver = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                when {
                    context.isPermissionGranted(permission) -> onResult.invoke(true)
                    showRationaleDialog.value.not() && showSettingsDialog.value.not() -> requestPermissionLauncher.launch(permission)
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
            dismissButton = {
                TextButton(
                    onClick = {
                        showSettingsDialog.value = false
                        onResult.invoke(false)
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            icon = { Icon(Icon_Mic, contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_title)) },
            text = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_message)) },
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
                        runCatching {
                            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                addCategory(Intent.CATEGORY_DEFAULT)
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            }
                            context.startActivity(i)
                        }.onFailure { error ->
                            XLog.e(context.getLog("startActivity", error.toString()), error)
                            showSettingsDialog.value = false
                            onResult.invoke(false)
                        }
                    }
                ) {
                    Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSettingsDialog.value = false
                        onResult.invoke(false)
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            icon = { Icon(Icon_Mic, contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_title)) },
            text = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_message_settings)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

private val Icon_Mic: ImageVector = materialIcon(name = "Filled.Mic") {
    materialPath {
        moveTo(12.0f, 14.0f)
        curveToRelative(1.66f, 0.0f, 2.99f, -1.34f, 2.99f, -3.0f)
        lineTo(15.0f, 5.0f)
        curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
        reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
        verticalLineToRelative(6.0f)
        curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
        close()
        moveTo(17.3f, 11.0f)
        curveToRelative(0.0f, 3.0f, -2.54f, 5.1f, -5.3f, 5.1f)
        reflectiveCurveTo(6.7f, 14.0f, 6.7f, 11.0f)
        lineTo(5.0f, 11.0f)
        curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
        lineTo(11.0f, 21.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-3.28f)
        curveToRelative(3.28f, -0.48f, 6.0f, -3.3f, 6.0f, -6.72f)
        horizontalLineToRelative(-1.7f)
        close()
    }
}