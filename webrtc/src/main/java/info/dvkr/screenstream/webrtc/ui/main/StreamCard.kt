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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import androidx.compose.ui.graphics.vector.ImageVector
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
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
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
                modifier = Modifier.padding(start = 12.dp).fillMaxWidth().defaultMinSize(minHeight = 48.dp),
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
                            imageVector = Icon_Refresh,
                            contentDescription = stringResource(id = R.string.webrtc_stream_description_get_new_id)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.padding(start = 12.dp).fillMaxWidth(),
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
                                imageVector = Icon_Visibility,
                                contentDescription = stringResource(id = R.string.webrtc_stream_description_show_password)
                            )
                        }
                    } else {
                        IconButton(onClick = onCreateNewPassword) {
                            Icon(
                                imageVector = Icon_Refresh,
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
        Icon(imageVector = Icon_OpenInNew, contentDescription = stringResource(id = R.string.webrtc_stream_description_open_address))
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
        Icon(imageVector = Icon_ContentCopy, contentDescription = stringResource(id = R.string.webrtc_stream_description_copy_address))
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
        Icon(imageVector = Icon_Share, contentDescription = stringResource(id = R.string.webrtc_stream_description_share_address))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ShowQRCodeButton(
    fullAddress: String
) {
    val showQRDialog = remember { mutableStateOf(false) }

    IconButton(onClick = { showQRDialog.value = true }) {
        Icon(imageVector = Icon_QrCode, contentDescription = stringResource(id = R.string.webrtc_stream_description_qr_address))
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

private val Icon_Refresh: ImageVector = materialIcon(name = "Filled.Refresh") {
    materialPath {
        moveTo(17.65f, 6.35f)
        curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
        curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
        reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
        curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
        horizontalLineToRelative(-2.08f)
        curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
        curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
        reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
        curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
        lineTo(13.0f, 11.0f)
        horizontalLineToRelative(7.0f)
        verticalLineTo(4.0f)
        lineToRelative(-2.35f, 2.35f)
        close()
    }
}

private val Icon_OpenInNew: ImageVector = materialIcon(name = "AutoMirrored.Filled.OpenInNew", autoMirror = true) {
    materialPath {
        moveTo(19.0f, 19.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(5.0f)
        horizontalLineToRelative(7.0f)
        verticalLineTo(3.0f)
        horizontalLineTo(5.0f)
        curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(14.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        verticalLineToRelative(-7.0f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(7.0f)
        close()
        moveTo(14.0f, 3.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(3.59f)
        lineToRelative(-9.83f, 9.83f)
        lineToRelative(1.41f, 1.41f)
        lineTo(19.0f, 6.41f)
        verticalLineTo(10.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(3.0f)
        horizontalLineToRelative(-7.0f)
        close()
    }
}

private val Icon_Share: ImageVector = materialIcon(name = "Filled.Share") {
    materialPath {
        moveTo(18.0f, 16.08f)
        curveToRelative(-0.76f, 0.0f, -1.44f, 0.3f, -1.96f, 0.77f)
        lineTo(8.91f, 12.7f)
        curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
        reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
        lineToRelative(7.05f, -4.11f)
        curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
        curveToRelative(1.66f, 0.0f, 3.0f, -1.34f, 3.0f, -3.0f)
        reflectiveCurveToRelative(-1.34f, -3.0f, -3.0f, -3.0f)
        reflectiveCurveToRelative(-3.0f, 1.34f, -3.0f, 3.0f)
        curveToRelative(0.0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
        lineTo(8.04f, 9.81f)
        curveTo(7.5f, 9.31f, 6.79f, 9.0f, 6.0f, 9.0f)
        curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
        reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
        curveToRelative(0.79f, 0.0f, 1.5f, -0.31f, 2.04f, -0.81f)
        lineToRelative(7.12f, 4.16f)
        curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
        curveToRelative(0.0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
        curveToRelative(1.61f, 0.0f, 2.92f, -1.31f, 2.92f, -2.92f)
        reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
        close()
    }
}

private val Icon_ContentCopy: ImageVector = materialIcon(name = "Filled.ContentCopy") {
    materialPath {
        moveTo(16.0f, 1.0f)
        lineTo(4.0f, 1.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        horizontalLineToRelative(2.0f)
        lineTo(4.0f, 3.0f)
        horizontalLineToRelative(12.0f)
        lineTo(16.0f, 1.0f)
        close()
        moveTo(19.0f, 5.0f)
        lineTo(8.0f, 5.0f)
        curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
        verticalLineToRelative(14.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(11.0f)
        curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
        lineTo(21.0f, 7.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        close()
        moveTo(19.0f, 21.0f)
        lineTo(8.0f, 21.0f)
        lineTo(8.0f, 7.0f)
        horizontalLineToRelative(11.0f)
        verticalLineToRelative(14.0f)
        close()
    }
}

private val Icon_QrCode: ImageVector = materialIcon(name = "Filled.QrCode") {
    materialPath {
        moveTo(3.0f, 11.0f)
        horizontalLineToRelative(8.0f)
        verticalLineTo(3.0f)
        horizontalLineTo(3.0f)
        verticalLineTo(11.0f)
        close()
        moveTo(5.0f, 5.0f)
        horizontalLineToRelative(4.0f)
        verticalLineToRelative(4.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(5.0f)
        close()
    }
    materialPath {
        moveTo(3.0f, 21.0f)
        horizontalLineToRelative(8.0f)
        verticalLineToRelative(-8.0f)
        horizontalLineTo(3.0f)
        verticalLineTo(21.0f)
        close()
        moveTo(5.0f, 15.0f)
        horizontalLineToRelative(4.0f)
        verticalLineToRelative(4.0f)
        horizontalLineTo(5.0f)
        verticalLineTo(15.0f)
        close()
    }
    materialPath {
        moveTo(13.0f, 3.0f)
        verticalLineToRelative(8.0f)
        horizontalLineToRelative(8.0f)
        verticalLineTo(3.0f)
        horizontalLineTo(13.0f)
        close()
        moveTo(19.0f, 9.0f)
        horizontalLineToRelative(-4.0f)
        verticalLineTo(5.0f)
        horizontalLineToRelative(4.0f)
        verticalLineTo(9.0f)
        close()
    }
    materialPath {
        moveTo(19.0f, 19.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(13.0f, 13.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(15.0f, 15.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(13.0f, 17.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(15.0f, 19.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(17.0f, 17.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(17.0f, 13.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
    materialPath {
        moveTo(19.0f, 15.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(-2.0f)
        close()
    }
}

private val Icon_Visibility: ImageVector = materialIcon(name = "Outlined.Visibility") {
    materialPath {
        moveTo(12.0f, 6.0f)
        curveToRelative(3.79f, 0.0f, 7.17f, 2.13f, 8.82f, 5.5f)
        curveTo(19.17f, 14.87f, 15.79f, 17.0f, 12.0f, 17.0f)
        reflectiveCurveToRelative(-7.17f, -2.13f, -8.82f, -5.5f)
        curveTo(4.83f, 8.13f, 8.21f, 6.0f, 12.0f, 6.0f)
        moveToRelative(0.0f, -2.0f)
        curveTo(7.0f, 4.0f, 2.73f, 7.11f, 1.0f, 11.5f)
        curveTo(2.73f, 15.89f, 7.0f, 19.0f, 12.0f, 19.0f)
        reflectiveCurveToRelative(9.27f, -3.11f, 11.0f, -7.5f)
        curveTo(21.27f, 7.11f, 17.0f, 4.0f, 12.0f, 4.0f)
        close()
        moveTo(12.0f, 9.0f)
        curveToRelative(1.38f, 0.0f, 2.5f, 1.12f, 2.5f, 2.5f)
        reflectiveCurveTo(13.38f, 14.0f, 12.0f, 14.0f)
        reflectiveCurveToRelative(-2.5f, -1.12f, -2.5f, -2.5f)
        reflectiveCurveTo(10.62f, 9.0f, 12.0f, 9.0f)
        moveToRelative(0.0f, -2.0f)
        curveToRelative(-2.48f, 0.0f, -4.5f, 2.02f, -4.5f, 4.5f)
        reflectiveCurveTo(9.52f, 16.0f, 12.0f, 16.0f)
        reflectiveCurveToRelative(4.5f, -2.02f, 4.5f, -4.5f)
        reflectiveCurveTo(14.48f, 7.0f, 12.0f, 7.0f)
        close()
    }
}