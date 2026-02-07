package info.dvkr.screenstream.webrtc.ui.main

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.generateQRBitmap
import info.dvkr.screenstream.common.openStringUrl
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun StreamCard(
    webRtcState: State<WebRtcState>,
    onGetNewStreamId: () -> Unit,
    onCreateNewPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        if (webRtcState.value.streamId.isBlank()) {
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
            if (webRtcState.value.networkRecovery) {
                Text(
                    text = stringResource(id = R.string.webrtc_stream_network_recovery),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error)
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onError,
                    textAlign = TextAlign.Center
                )
            }

            val fullAddress =
                webRtcState.value.signalingServerUrl + "/?id=${webRtcState.value.streamId}&p=${webRtcState.value.streamPassword}"
            val context = LocalContext.current

            Text(
                text = stringResource(id = R.string.webrtc_stream_server_address, webRtcState.value.signalingServerUrl)
                    .stylePlaceholder(webRtcState.value.signalingServerUrl, SpanStyle(color = MaterialTheme.colorScheme.primary)),
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .clickable(role = Role.Button) {
                        context.openStringUrl(fullAddress) { error ->
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
                    text = stringResource(id = R.string.webrtc_stream_stream_id, webRtcState.value.streamId)
                        .stylePlaceholder(webRtcState.value.streamId, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                AnimatedVisibility(
                    visible = webRtcState.value.isStreaming.not(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    IconButton(onClick = onGetNewStreamId) {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px),
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
                val text = if (webRtcState.value.isStreaming.not() || isPressed.value) webRtcState.value.streamPassword else "*"

                Text(
                    text = stringResource(id = R.string.webrtc_stream_stream_password, text)
                        .stylePlaceholder(text, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                Crossfade(
                    targetState = webRtcState.value.isStreaming,
                    label = "StreamPasswordButtonCrossfade"
                ) { isStreaming ->
                    if (isStreaming) {
                        IconButton(onClick = { }, interactionSource = interactionSource) {
                            Icon(
                                painter = painterResource(R.drawable.visibility_24px),
                                contentDescription = stringResource(id = R.string.webrtc_stream_description_show_password)
                            )
                        }
                    } else {
                        IconButton(onClick = onCreateNewPassword) {
                            Icon(
                                painter = painterResource(R.drawable.refresh_24px),
                                contentDescription = stringResource(id = R.string.webrtc_stream_description_create_password)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                OpenInBrowserButton(fullAddress)
                CopyAddressButton(fullAddress)
                ShareAddressButton(fullAddress)
                ShowQRCodeButton(fullAddress)
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
            painter = painterResource(R.drawable.open_in_new_24px),
            contentDescription = stringResource(id = R.string.webrtc_stream_description_open_address)
        )
    }
}

@Composable
private fun CopyAddressButton(
    fullAddress: String,
    clipboard: Clipboard = LocalClipboard.current,
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val context = LocalContext.current

    IconButton(onClick = {
        scope.launch {
            runCatching {
                clipboard.setClipEntry(
                    ClipEntry(
                        ClipData.newPlainText(fullAddress, fullAddress).apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                description.extras = PersistableBundle().apply {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                    else
                                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                                }
                            }
                        }
                    )
                )
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, R.string.webrtc_stream_copied, Toast.LENGTH_LONG).show()
        }
    }) {
        Icon(
            painter = painterResource(R.drawable.content_copy_24px),
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
            painter = painterResource(R.drawable.share_24px),
            contentDescription = stringResource(id = R.string.webrtc_stream_description_share_address)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShowQRCodeButton(
    fullAddress: String
) {
    val showQRDialog = remember { mutableStateOf(false) }

    IconButton(onClick = { showQRDialog.value = true }) {
        Icon(
            painter = painterResource(R.drawable.qr_code_24px),
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