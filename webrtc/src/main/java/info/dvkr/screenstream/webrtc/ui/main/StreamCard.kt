package info.dvkr.screenstream.webrtc.ui.main

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BasicTooltipDefaults
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.findActivity
import info.dvkr.screenstream.common.generateQRBitmap
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.isPermissionGranted
import info.dvkr.screenstream.common.openStringUrl
import info.dvkr.screenstream.common.shouldShowPermissionRationale
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject


@Composable
internal fun StreamCard(
    webRtcState: State<WebRtcState>,
    onGetNewStreamId: () -> Unit,
    onCreateNewPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val isStreaming = remember { derivedStateOf { webRtcState.value.isStreaming } }
    val streamId = remember { derivedStateOf { webRtcState.value.streamId } }
    val signalingServerUrl = remember { derivedStateOf { webRtcState.value.signalingServerUrl } }
    val streamPassword = remember { derivedStateOf { webRtcState.value.streamPassword } }

    val fullAddress = remember {
        derivedStateOf { webRtcState.value.signalingServerUrl + "/?id=${webRtcState.value.streamId}&p=${webRtcState.value.streamPassword}" }
    }

    ElevatedCard(modifier = modifier) {
        if (streamId.value.isBlank()) {
            Text(
                text = stringResource(id = R.string.webrtc_stream_stream_id_getting),
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium
            )
        } else {
            val context = LocalContext.current
            Text(
                text = stringResource(id = R.string.webrtc_stream_server_address, signalingServerUrl.value)
                    .stylePlaceholder(signalingServerUrl.value, SpanStyle(color = MaterialTheme.colorScheme.primary)),
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .clickable(role = Role.Button) {
                        context.openStringUrl(fullAddress.value) { error ->
                            val messageId = when (error) {
                                is ActivityNotFoundException -> R.string.webrtc_stream_no_web_browser_found
                                else -> R.string.webrtc_stream_external_app_error
                            }
                            Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
                        }
                    }
                    .padding(horizontal = 12.dp)
                    .align(alignment = Alignment.CenterHorizontally),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.webrtc_stream_stream_id, streamId.value)
                        .stylePlaceholder(streamId.value, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                AnimatedVisibility(
                    visible = isStreaming.value.not(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(onClick = onGetNewStreamId) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.webrtc_stream_description_get_new_id)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed = interactionSource.collectIsPressedAsState()
                val text = remember { derivedStateOf { if (isStreaming.value.not() || isPressed.value) streamPassword.value else "*" } }

                Text(
                    text = stringResource(id = R.string.webrtc_stream_stream_password, text.value)
                        .stylePlaceholder(text.value, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                Crossfade(
                    targetState = isStreaming.value,
                    label = "StreamPasswordButtonCrossfade"
                ) { isStreaming ->
                    if (isStreaming) {
                        IconButton(onClick = { }, interactionSource = interactionSource) {
                            Icon(
                                imageVector = Icons.Outlined.Visibility,
                                contentDescription = stringResource(id = R.string.webrtc_stream_description_show_password)
                            )
                        }
                    } else {
                        IconButton(onClick = onCreateNewPassword) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(id = R.string.webrtc_stream_description_create_password)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MicButton(webRtcState = webRtcState, modifier = Modifier.padding(start = 4.dp))
                Row {
                    OpenInBrowserButton(fullAddress.value)
                    CopyAddressButton(fullAddress.value)
                    ShareAddressButton(fullAddress.value)
                    ShowQRCodeButton(fullAddress.value)
                }
            }
        }
    }
}

@Composable
private fun OpenInBrowserButton(
    fullAddress: String
) {
    val context = LocalContext.current

    IconButton(
        onClick = {
            context.openStringUrl(fullAddress) { error ->
                val messageId = when (error) {
                    is ActivityNotFoundException -> R.string.webrtc_stream_no_web_browser_found
                    else -> R.string.webrtc_stream_external_app_error
                }
                Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
            }
        }
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(id = R.string.webrtc_stream_description_open_address)
        )
    }
}

@Composable
private fun CopyAddressButton(
    fullAddress: String,
    clipboardManager: ClipboardManager = LocalClipboardManager.current
) {
    val context = LocalContext.current

    IconButton(onClick = {
        clipboardManager.setText(AnnotatedString(fullAddress))
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, R.string.webrtc_stream_copied, Toast.LENGTH_LONG).show()
        }
    }) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = stringResource(id = R.string.webrtc_stream_description_copy_address)
        )
    }
}

@Composable
private fun ShareAddressButton(
    fullAddress: String
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.webrtc_stream_share_address)

    IconButton(
        onClick = {
            val sharingIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, fullAddress) }
            context.startActivity(Intent.createChooser(sharingIntent, shareTitle))
        }
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = stringResource(id = R.string.webrtc_stream_description_share_address)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShowQRCodeButton(
    fullAddress: String
) {
    val showQRDialog = rememberSaveable { mutableStateOf(false) }

    IconButton(onClick = { showQRDialog.value = true }) {
        Icon(
            imageVector = Icons.Default.QrCode,
            contentDescription = stringResource(id = R.string.webrtc_stream_description_qr_address)
        )
    }

    if (showQRDialog.value) {
        BasicAlertDialog(
            onDismissRequest = { showQRDialog.value = false },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(Color.White)
                .padding(16.dp)
                .size(192.dp + 32.dp)
        ) {
            val qrCodeSizePx = with(LocalDensity.current) { 192.dp.roundToPx() }
            val qrImageBitmap = remember(fullAddress) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(fullAddress) {
                qrImageBitmap.value = fullAddress.generateQRBitmap(qrCodeSizePx).asImageBitmap()
            }
            if (qrImageBitmap.value != null) {
                Image(bitmap = qrImageBitmap.value!!, contentDescription = stringResource(id = R.string.webrtc_stream_description_qr))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MicButton(
    webRtcState: State<WebRtcState>,
    modifier: Modifier = Modifier,
    webRtcSettings: WebRtcSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    val isStreaming = remember { derivedStateOf { webRtcState.value.isStreaming } }
    val enableMic = remember { derivedStateOf { webRtcState.value.enableMic } }

    val enableMicWithPermissions = rememberSaveable { mutableStateOf(false) }

    val tooltipState = rememberTooltipState(tooltipDuration = 3000L)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(caretProperties = TooltipDefaults.caretProperties) {
                Text(text = stringResource(id = if (enableMic.value) R.string.webrtc_stream_mic_on else R.string.webrtc_stream_mic_off))
            }
        },
        state = tooltipState,
    ) {
        IconButton(
            onClick = {
                if (enableMic.value) {
                    scope.launch { withContext(NonCancellable) { webRtcSettings.updateData { copy(enableMic = false) } } }
                } else {
                    if (context.isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
                        scope.launch { withContext(NonCancellable) { webRtcSettings.updateData { copy(enableMic = true) } } }
                    } else {
                        enableMicWithPermissions.value = true
                    }
                }
            },
            enabled = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || isStreaming.value.not(),
            modifier = modifier
        ) {
            Icon(
                imageVector = if (enableMic.value) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = stringResource(id = if (enableMic.value) R.string.webrtc_stream_mic_on else R.string.webrtc_stream_mic_off)
            )
        }
    }

    LaunchedEffect(enableMic.value) { tooltipState.show() }

    if (enableMicWithPermissions.value) {
        EnableMicWithPermissions { isGranted ->
            scope.launch { withContext(NonCancellable) { webRtcSettings.updateData { copy(enableMic = isGranted) } } }
            enableMicWithPermissions.value = false
        }
    }
}

@Composable
private fun EnableMicWithPermissions(
    permission: String = Manifest.permission.RECORD_AUDIO,
    onDone: (Boolean) -> Unit
) {
    val context = LocalContext.current
    if (context.isPermissionGranted(permission)) {
        onDone.invoke(true)
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
                    context.isPermissionGranted(permission) -> onDone.invoke(true)
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
            onDismissRequest = { },
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
                        onDone.invoke(false)
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
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
                            onDone.invoke(false)
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
                        onDone.invoke(false)
                    }
                ) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_title)) },
            text = { Text(text = stringResource(id = R.string.webrtc_stream_audio_permission_message_settings)) },
            shape = MaterialTheme.shapes.large,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        )
    }
}

@ExperimentalMaterial3Api
@ExperimentalFoundationApi
@Composable
internal fun rememberTooltipState(
    initialIsVisible: Boolean = false,
    isPersistent: Boolean = false,
    tooltipDuration: Long = BasicTooltipDefaults.TooltipDuration,
    mutatorMutex: MutatorMutex = BasicTooltipDefaults.GlobalMutatorMutex
): TooltipState = remember(isPersistent, mutatorMutex) {
    TooltipStateImpl(
        initialIsVisible = initialIsVisible,
        isPersistent = isPersistent,
        tooltipDuration = tooltipDuration,
        mutatorMutex = mutatorMutex
    )
}

@ExperimentalMaterial3Api
@Stable
private class TooltipStateImpl(
    initialIsVisible: Boolean,
    override val isPersistent: Boolean,
    private val tooltipDuration: Long,
    private val mutatorMutex: MutatorMutex
) : TooltipState {
    override val transition: MutableTransitionState<Boolean> = MutableTransitionState(initialIsVisible)

    override val isVisible: Boolean
        get() = transition.currentState || transition.targetState

    private var job: (CancellableContinuation<Unit>)? = null

    override suspend fun show(mutatePriority: MutatePriority) {
        val cancellableShow: suspend () -> Unit = {
            suspendCancellableCoroutine { continuation ->
                transition.targetState = true
                job = continuation
            }
        }

        mutatorMutex.mutate(mutatePriority) {
            try {
                if (isPersistent) {
                    cancellableShow()
                } else {
                    withTimeout(tooltipDuration) { cancellableShow() }
                }
            } finally {
                dismiss()
            }
        }
    }

    override fun dismiss() {
        transition.targetState = false
    }

    override fun onDispose() {
        job?.cancel()
    }
}