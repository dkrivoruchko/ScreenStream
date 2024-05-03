package info.dvkr.screenstream.webrtc.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import info.dvkr.screenstream.common.generateQRBitmap
import info.dvkr.screenstream.common.openStringUrl
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.WebRtcState


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
                horizontalArrangement = Arrangement.End
            ) {
                OpenInBrowserButton(fullAddress.value)
                CopyAddressButton(fullAddress.value)
                ShareAddressButton(fullAddress.value)
                ShowQRCodeButton(fullAddress.value)
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
    val showQRDialog = remember { mutableStateOf(false) }

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