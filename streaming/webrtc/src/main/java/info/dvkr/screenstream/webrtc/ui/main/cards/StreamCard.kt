package info.dvkr.screenstream.webrtc.ui.main.cards

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.WebRtcState
import io.screenstream.foundation.qr.generateQRBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StreamCard(
    state: WebRtcState,
    onGetNewStreamId: () -> Unit,
    onCreateNewPassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        if (state.streamId.isBlank()) {
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
            if (state.networkRecovery) {
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

            val fullAddress = state.signalingServerUrl + "/?id=${state.streamId}#p=${state.streamPassword}"
            val context = LocalContext.current
            fun openInBrowser() {
                val intent = Intent(Intent.ACTION_VIEW, fullAddress.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val messageId = try {
                    context.startActivity(intent)
                    return
                } catch (error: Exception) {
                    when (error) {
                        is ActivityNotFoundException -> R.string.webrtc_stream_no_web_browser_found
                        else -> R.string.webrtc_stream_external_app_error
                    }
                }
                Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
            }

            Text(
                text = stringResource(id = R.string.webrtc_stream_server_address, state.signalingServerUrl)
                    .stylePlaceholder(state.signalingServerUrl, SpanStyle(color = MaterialTheme.colorScheme.primary)),
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .clickable(role = Role.Button, onClick = ::openInBrowser)
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
                    text = stringResource(id = R.string.webrtc_stream_stream_id, state.streamId)
                        .stylePlaceholder(state.streamId, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                AnimatedVisibility(
                    visible = state.isStreaming.not(),
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
                val text = if (state.isStreaming.not() || isPressed.value) state.streamPassword else "*"

                Text(
                    text = stringResource(id = R.string.webrtc_stream_stream_password, text)
                        .stylePlaceholder(text, SpanStyle(fontWeight = FontWeight.Bold, fontFamily = RobotoMonoBold)),
                    modifier = Modifier.weight(1F)
                )

                Crossfade(
                    targetState = state.isStreaming,
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
                OpenInBrowserButton(onClick = ::openInBrowser)
                CopyAddressButton(fullAddress)
                ShareAddressButton(fullAddress)
                ShowQRCodeButton(fullAddress)
            }
        }
    }
}

@Composable
private fun OpenInBrowserButton(
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
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
                            description.extras = PersistableBundle().apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                else
                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
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
        var qrCodeSizePx by remember { mutableIntStateOf(0) }
        BasicAlertDialog(
            onDismissRequest = { showQRDialog.value = false },
            modifier = Modifier
                .sizeIn(maxWidth = 208.dp, maxHeight = 208.dp)
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .background(Color.White)
        ) {
            Box(
                modifier = Modifier
                    .onSizeChanged { qrCodeSizePx = it.width },
                contentAlignment = Alignment.Center,
            ) {
                val imageSizePx = qrCodeSizePx
                key(fullAddress, imageSizePx) {
                    var loadFailed by remember { mutableStateOf(false) }
                    val bitmap by produceState<ImageBitmap?>(initialValue = null) {
                        if (imageSizePx > 0) {
                            try {
                                value = withContext(Dispatchers.Default) {
                                    fullAddress.generateQRBitmap(imageSizePx).asImageBitmap()
                                }
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (_: Exception) {
                                loadFailed = true
                            }
                        }
                    }
                    val image = bitmap
                    when {
                        loadFailed -> Text(
                            text = stringResource(R.string.webrtc_error_unspecified),
                            color = MaterialTheme.colorScheme.error,
                        )

                        image == null -> CircularProgressIndicator(modifier = Modifier.size(32.dp))

                        else -> Image(
                            bitmap = image,
                            contentDescription = stringResource(R.string.webrtc_stream_description_qr),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.None,
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }
        }
    }
}
