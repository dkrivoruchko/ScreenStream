package info.dvkr.screenstream.rtsp.ui.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
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
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.AudioCodecInfo
import info.dvkr.screenstream.rtsp.internal.EncoderUtils
import info.dvkr.screenstream.rtsp.internal.EncoderUtils.getBitRateInKbits
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
internal fun AudioCard(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
    rtspSettings: RtspSettings = koinInject()
) {
    val showRecordAudioPermission = rememberSaveable { mutableStateOf(false) }
    val onPermissionsResult = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_audio_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        initiallyExpanded = false
    ) {
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        val context = LocalContext.current

        AudioSource(
            text = stringResource(R.string.rtsp_audio_mic),
            mainIcon = Icon_Outline_Mic,
            muteIcon = Icon_Filled_MicOff,
            muteIconContentDescription = stringResource(R.string.rtsp_audio_mic_mute),
            isStreaming = rtspState.value.isStreaming,
            active = rtspSettingsState.value.enableMic,
            onActiveChange = { active ->
                if (active.not() || context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                    scope.launch { rtspSettings.updateData { copy(enableMic = active) } }
                } else {
                    onPermissionsResult.value = { isGranted ->
                        scope.launch { rtspSettings.updateData { copy(enableMic = isGranted) } }
                    }
                    showRecordAudioPermission.value = true
                }
            },
            volume = rtspSettingsState.value.volumeMic,
            onVolumeChange = {
                scope.launch {
                    rtspSettings.updateData { copy(volumeMic = it, muteMic = if (it > 0F) false else muteMic) }
                }
            },
            muted = rtspSettingsState.value.muteMic,
            onMutedChange = { scope.launch { rtspSettings.updateData { copy(muteMic = it) } } },
            modifier = Modifier.padding(top = 4.dp)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AudioSource(
                text = stringResource(R.string.rtsp_audio_device),
                mainIcon = Icon_Outline_DeviceSound,
                muteIcon = Icon_Filled_VolumeOff,
                muteIconContentDescription = stringResource(R.string.rtsp_audio_device_mute),
                isStreaming = rtspState.value.isStreaming,
                active = rtspSettingsState.value.enableDeviceAudio,
                onActiveChange = { active ->
                    if (active.not() || context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                        scope.launch { rtspSettings.updateData { copy(enableDeviceAudio = active) } }
                    } else {
                        onPermissionsResult.value = { isGranted ->
                            scope.launch { rtspSettings.updateData { copy(enableDeviceAudio = isGranted) } }
                        }
                        showRecordAudioPermission.value = true
                    }
                },
                volume = rtspSettingsState.value.volumeDeviceAudio,
                onVolumeChange = {
                    scope.launch {
                        rtspSettings.updateData { copy(volumeDeviceAudio = it, muteDeviceAudio = if (it > 0F) false else muteDeviceAudio) }
                    }
                },
                muted = rtspSettingsState.value.muteDeviceAudio,
                onMutedChange = { scope.launch { rtspSettings.updateData { copy(muteDeviceAudio = it) } } },
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        AudioEncoder(
            isAutoSelect = rtspSettingsState.value.audioCodecAutoSelect,
            onAutoSelectChange = { scope.launch { rtspSettings.updateData { copy(audioCodecAutoSelect = audioCodecAutoSelect.not()) } } },
            selectedEncoder = rtspState.value.selectedAudioEncoder,
            availableEncoders = EncoderUtils.availableAudioEncoders,
            onCodecSelected = { scope.launch { rtspSettings.updateData { copy(audioCodec = it ?: "") } } },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth()
        )

        if (rtspState.value.selectedAudioEncoder?.capabilities?.audioCapabilities == null) return@ExpandableCard

        val audioCapabilities = remember(rtspState.value.selectedAudioEncoder) {
            rtspState.value.selectedAudioEncoder?.capabilities?.audioCapabilities!!
        }

        val bitrateRangeKbits = remember(audioCapabilities) { audioCapabilities.getBitRateInKbits() }

        Bitrate(
            bitrateRangeKbits = bitrateRangeKbits,
            bitrateBits = rtspSettingsState.value.audioBitrateBits,
            onValueChange = { scope.launch { rtspSettings.updateData { copy(audioBitrateBits = it) } } },
            enabled = rtspState.value.isStreaming.not(),
            modifier = Modifier
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth()
        )
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
    mainIcon: ImageVector,
    muteIcon: ImageVector,
    muteIconContentDescription: String,
    isStreaming: Boolean,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    muted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .conditional(isStreaming.not()) { toggleable(value = active, onValueChange = { onActiveChange(it) }) }
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = mainIcon, contentDescription = null)

            Text(
                text = text, modifier = Modifier
                    .weight(1F)
                    .padding(start = 8.dp)
            )

            Switch(checked = active, onCheckedChange = null, modifier = Modifier.scale(0.7F), enabled = isStreaming.not())
        }

        Row(
            modifier = Modifier.padding(start = 48.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var sliderPosition by remember(volume, muted) { mutableFloatStateOf(if (muted) 0F else volume.coerceIn(0f, 2f) * 100) }

            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                modifier = Modifier.weight(1f),
                enabled = active,
                valueRange = 0f..200f,
                onValueChangeFinished = { onVolumeChange((sliderPosition / 100).coerceIn(0f, 2f)) },
            )

            Text(text = "${sliderPosition.roundToInt()}%", modifier = Modifier.padding(start = 16.dp, end = 8.dp))

            IconButton(
                onClick = { onMutedChange(muted.not()) },
                enabled = active
            ) {
                Icon(
                    imageVector = muteIcon,
                    tint = when {
                        active.not() -> LocalContentColor.current
                        muted -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    contentDescription = muteIconContentDescription
                )
            }
        }
    }
}

@Composable
private fun AudioEncoder(
    isAutoSelect: Boolean,
    onAutoSelectChange: (Boolean) -> Unit,
    selectedEncoder: AudioCodecInfo?,
    availableEncoders: List<AudioCodecInfo>,
    onCodecSelected: (String?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .conditional(enabled) { toggleable(value = isAutoSelect, onValueChange = onAutoSelectChange) }
                .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.rtsp_audio_encoder))
            Spacer(modifier = Modifier.weight(1f))
            Row {
                Text(text = stringResource(R.string.rtsp_audio_encoder_auto), modifier = Modifier.align(Alignment.CenterVertically))
                Switch(checked = isAutoSelect, enabled = enabled, onCheckedChange = null, modifier = Modifier.scale(0.7F))
            }
        }

        var expanded by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .conditional(isAutoSelect.not()) { clickable { expanded = true } }
                .alpha(if (isAutoSelect) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EncoderItem(
                codecName = "${selectedEncoder?.codec?.name} ${selectedEncoder?.vendorName}",
                encoderName = "[${selectedEncoder?.name}]",
                isHardwareAccelerated = selectedEncoder?.isHardwareAccelerated == true,
                isCBRModeSupported = selectedEncoder?.isCBRModeSupported == true
            )
            Spacer(Modifier.weight(1f))
            val iconRotation = remember { Animatable(0F) }
            Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(iconRotation.value))
            LaunchedEffect(expanded) { iconRotation.animateTo(targetValue = if (expanded) 180F else 0F, animationSpec = tween(500)) }
        }

        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableEncoders.forEachIndexed { index, encoder ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                if (index != 0) HorizontalDivider()
                                Row {
                                    if (selectedEncoder?.name == encoder.name) {
                                        Icon(
                                            Icons.Default.Done,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(start = 16.dp)
                                                .align(Alignment.CenterVertically)
                                        )
                                    }
                                    EncoderItem(
                                        codecName = "${encoder.codec.name} ${encoder.vendorName}",
                                        encoderName = "[${encoder.name}]",
                                        isHardwareAccelerated = encoder.isHardwareAccelerated,
                                        isCBRModeSupported = encoder.isCBRModeSupported,
                                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onCodecSelected(encoder.name)
                        },
                        contentPadding = PaddingValues()
                    )
                }

            }
        }
    }
}

@Composable
private fun Bitrate(
    bitrateRangeKbits: ClosedRange<Int>,
    bitrateBits: Int,
    onValueChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var sliderPosition by remember { mutableFloatStateOf((bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()) }

        Text(
            text = stringResource(R.string.rtsp_audio_bitrate, sliderPosition.roundToInt().toKOrMBitString()),
            modifier = Modifier
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = bitrateRangeKbits.start.toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                enabled = enabled,
                valueRange = bitrateRangeKbits.start.toFloat()..bitrateRangeKbits.endInclusive.toFloat(),
                onValueChangeFinished = { onValueChange.invoke((sliderPosition * 1000).roundToInt()) }
            )
            Text(
                text = bitrateRangeKbits.endInclusive.toKOrMBitString(),
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun RequestPermission(
    permission: String = Manifest.permission.RECORD_AUDIO,
    onResult: (Boolean) -> Unit
) {
    val context = LocalContext.current
    if (context.isPermissionGranted(permission)) {
        onResult(true)
        return
    }

    val activity = remember(context) { context.findActivity() }
    val showRationaleDialog = rememberSaveable { mutableStateOf(false) }
    val showSettingsDialog = rememberSaveable { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onResult(true)
        } else {
            val showRationale = activity.shouldShowPermissionRationale(permission)
            showRationaleDialog.value = showRationale
            showSettingsDialog.value = showRationale.not()
        }
    }

    val permissionCheckerObserver = remember {
        LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                when {
                    context.isPermissionGranted(permission) -> onResult(true)
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
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSettingsDialog.value = false
                        onResult(false)
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            icon = { Icon(Icon_Outline_Mic, contentDescription = null) },
            title = { Text(text = stringResource(R.string.rtsp_audio_permission_title)) },
            text = { Text(text = stringResource(R.string.rtsp_audio_permission_message)) },
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
                                data = "package:${context.packageName}".toUri()
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            }
                            context.startActivity(i)
                        }.onFailure { error ->
                            XLog.e(context.getLog("startActivity", error.toString()), error)
                            showSettingsDialog.value = false
                            onResult(false)
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.rtsp_audio_permission_open_settings))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSettingsDialog.value = false
                        onResult(false)
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            icon = { Icon(Icon_Outline_Mic, contentDescription = null) },
            title = { Text(text = stringResource(R.string.rtsp_audio_permission_title)) },
            text = { Text(text = stringResource(R.string.rtsp_audio_permission_message_settings)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

private val Icon_Outline_Mic: ImageVector = materialIcon(name = "Outline.Mic") {
    materialPath {
        moveTo(17.3f, 11f)
        curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
        curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
        horizontalLineTo(5f)
        curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
        verticalLineTo(21f)
        horizontalLineTo(13f)
        verticalLineTo(17.72f)
        curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
        moveTo(10.8f, 4.9f)
        curveTo(10.8f, 4.24f, 11.34f, 3.7f, 12f, 3.7f)
        curveTo(12.66f, 3.7f, 13.2f, 4.24f, 13.2f, 4.9f)
        lineTo(13.19f, 11.1f)
        curveTo(13.19f, 11.76f, 12.66f, 12.3f, 12f, 12.3f)
        curveTo(11.34f, 12.3f, 10.8f, 11.76f, 10.8f, 11.1f)
        moveTo(12f, 14f)
        arcTo(3f, 3f, 0f, false, false, 15f, 11f)
        verticalLineTo(5f)
        arcTo(3f, 3f, 0f, false, false, 12f, 2f)
        arcTo(3f, 3f, 0f, false, false, 9f, 5f)
        verticalLineTo(11f)
        arcTo(3f, 3f, 0f, false, false, 12f, 14f)
        close()
    }
}

private val Icon_Filled_MicOff: ImageVector = materialIcon(name = "Filled.MicOff") {
    materialPath {
        moveTo(19.0f, 11.0f)
        horizontalLineToRelative(-1.7f)
        curveToRelative(0.0f, 0.74f, -0.16f, 1.43f, -0.43f, 2.05f)
        lineToRelative(1.23f, 1.23f)
        curveToRelative(0.56f, -0.98f, 0.9f, -2.09f, 0.9f, -3.28f)
        close()
        moveTo(14.98f, 11.17f)
        curveToRelative(0.0f, -0.06f, 0.02f, -0.11f, 0.02f, -0.17f)
        lineTo(15.0f, 5.0f)
        curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
        reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
        verticalLineToRelative(0.18f)
        lineToRelative(5.98f, 5.99f)
        close()
        moveTo(4.27f, 3.0f)
        lineTo(3.0f, 4.27f)
        lineToRelative(6.01f, 6.01f)
        lineTo(9.01f, 11.0f)
        curveToRelative(0.0f, 1.66f, 1.33f, 3.0f, 2.99f, 3.0f)
        curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
        lineToRelative(1.66f, 1.66f)
        curveToRelative(-0.71f, 0.33f, -1.5f, 0.52f, -2.31f, 0.52f)
        curveToRelative(-2.76f, 0.0f, -5.3f, -2.1f, -5.3f, -5.1f)
        lineTo(5.0f, 11.0f)
        curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
        lineTo(11.0f, 21.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-3.28f)
        curveToRelative(0.91f, -0.13f, 1.77f, -0.45f, 2.54f, -0.9f)
        lineTo(19.73f, 21.0f)
        lineTo(21.0f, 19.73f)
        lineTo(4.27f, 3.0f)
        close()
    }
}

private val Icon_Outline_DeviceSound: ImageVector = materialIcon(name = "Outline.Icon_Outline_DeviceSound") {
    materialPath {
        moveTo(4f, 19.538f)
        verticalLineToRelative(-16f)
        close()
        moveToRelative(0f, 2f)
        quadToRelative(-0.825f, 0f, -1.412f, -0.588f)
        quadTo(2f, 20.363f, 2f, 19.538f)
        verticalLineToRelative(-16f)
        quadToRelative(0f, -0.825f, 0.587f, -1.413f)
        quadToRelative(0.588f, -0.587f, 1.413f, -0.587f)
        horizontalLineToRelative(9f)
        quadToRelative(0.825f, 0f, 1.413f, 0.587f)
        quadToRelative(0.587f, 0.588f, 0.587f, 1.413f)
        verticalLineToRelative(2.675f)
        lineToRelative(-2f, 2f)
        verticalLineTo(3.538f)
        horizontalLineTo(4f)
        verticalLineToRelative(16f)
        horizontalLineToRelative(5.675f)
        lineToRelative(2f, 2f)
        close()
        moveToRelative(4f, -4f)
        verticalLineToRelative(-4f)
        horizontalLineToRelative(2.5f)
        lineToRelative(3.5f, -3.5f)
        verticalLineToRelative(11f)
        lineToRelative(-3.5f, -3.5f)
        close()
        moveToRelative(8f, 0.8f)
        verticalLineToRelative(-5.625f)
        quadToRelative(0.875f, 0.3f, 1.437f, 1.075f)
        quadToRelative(0.563f, 0.775f, 0.563f, 1.75f)
        quadToRelative(0f, 0.974f, -0.563f, 1.737f)
        quadToRelative(-0.562f, 0.763f, -1.437f, 1.063f)
        close()
        moveToRelative(0f, 4.124f)
        verticalLineToRelative(-2f)
        quadToRelative(1.75f, -0.374f, 2.875f, -1.75f)
        quadTo(20f, 17.339f, 20f, 15.539f)
        quadToRelative(0f, -1.8f, -1.125f, -3.175f)
        quadTo(17.75f, 10.988f, 16f, 10.637f)
        verticalLineToRelative(-2f)
        quadToRelative(2.6f, 0.35f, 4.3f, 2.313f)
        quadToRelative(1.7f, 1.963f, 1.7f, 4.588f)
        reflectiveQuadToRelative(-1.7f, 4.587f)
        quadToRelative(-1.7f, 1.962f, -4.3f, 2.337f)
        close()
        moveTo(8.5f, 6.538f)
        quadToRelative(0.425f, 0f, 0.713f, -0.288f)
        quadToRelative(0.287f, -0.288f, 0.287f, -0.713f)
        reflectiveQuadToRelative(-0.287f, -0.712f)
        quadToRelative(-0.288f, -0.288f, -0.713f, -0.288f)
        reflectiveQuadToRelative(-0.712f, 0.288f)
        quadToRelative(-0.288f, 0.288f, -0.288f, 0.712f)
        quadToRelative(0f, 0.425f, 0.288f, 0.713f)
        quadToRelative(0.287f, 0.288f, 0.712f, 0.288f)
        close()
    }
}

private val Icon_Filled_VolumeOff: ImageVector = materialIcon(name = "Filled.VolumeOff") {
    materialPath {
        moveTo(16.5f, 12.0f)
        curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
        verticalLineToRelative(2.21f)
        lineToRelative(2.45f, 2.45f)
        curveToRelative(0.03f, -0.2f, 0.05f, -0.41f, 0.05f, -0.63f)
        close()
        moveTo(19.0f, 12.0f)
        curveToRelative(0.0f, 0.94f, -0.2f, 1.82f, -0.54f, 2.64f)
        lineToRelative(1.51f, 1.51f)
        curveTo(20.63f, 14.91f, 21.0f, 13.5f, 21.0f, 12.0f)
        curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
        verticalLineToRelative(2.06f)
        curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
        close()
        moveTo(4.27f, 3.0f)
        lineTo(3.0f, 4.27f)
        lineTo(7.73f, 9.0f)
        lineTo(3.0f, 9.0f)
        verticalLineToRelative(6.0f)
        horizontalLineToRelative(4.0f)
        lineToRelative(5.0f, 5.0f)
        verticalLineToRelative(-6.73f)
        lineToRelative(4.25f, 4.25f)
        curveToRelative(-0.67f, 0.52f, -1.42f, 0.93f, -2.25f, 1.18f)
        verticalLineToRelative(2.06f)
        curveToRelative(1.38f, -0.31f, 2.63f, -0.95f, 3.69f, -1.81f)
        lineTo(19.73f, 21.0f)
        lineTo(21.0f, 19.73f)
        lineToRelative(-9.0f, -9.0f)
        lineTo(4.27f, 3.0f)
        close()
        moveTo(12.0f, 4.0f)
        lineTo(9.91f, 6.09f)
        lineTo(12.0f, 8.18f)
        lineTo(12.0f, 4.0f)
        close()
    }
}