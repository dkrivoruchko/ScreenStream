package info.dvkr.screenstream.rtsp.ui.main

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
            mainIconId = R.drawable.mic_24px,
            muteIconId = R.drawable.mic_off_24px,
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
                mainIconId = R.drawable.mobile_speaker_24px,
                muteIconId = R.drawable.volume_off_24px,
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
    @DrawableRes mainIconId: Int,
    @DrawableRes muteIconId: Int,
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
            Icon(painter = painterResource(mainIconId), contentDescription = null)

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
                    painter = painterResource(muteIconId),
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
                .conditional(isAutoSelect.not()) { clickable(enabled = enabled) { expanded = true } }
                .alpha(if (isAutoSelect || enabled.not()) 0.5f else 1f)
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
        var isDragging by remember { mutableStateOf(false) }
        var sliderPosition by remember { mutableFloatStateOf((bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()) }

        LaunchedEffect(bitrateBits, bitrateRangeKbits) {
            if (!isDragging) {
                sliderPosition = (bitrateBits / 1000).coerceIn(bitrateRangeKbits).toFloat()
            }
        }

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
                onValueChange = {
                    isDragging = true
                    sliderPosition = it
                },
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .weight(1f)
                    .align(Alignment.CenterVertically),
                enabled = enabled,
                valueRange = bitrateRangeKbits.start.toFloat()..bitrateRangeKbits.endInclusive.toFloat(),
                onValueChangeFinished = {
                    isDragging = false
                    onValueChange.invoke((sliderPosition * 1000).roundToInt())
                }
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
                        showRationaleDialog.value = false
                        onResult(false)
                    }
                ) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.mic_24px), contentDescription = null) },
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
            icon = { Icon(painter = painterResource(R.drawable.mic_24px), contentDescription = null) },
            title = { Text(text = stringResource(R.string.rtsp_audio_permission_title)) },
            text = { Text(text = stringResource(R.string.rtsp_audio_permission_message_settings)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}
