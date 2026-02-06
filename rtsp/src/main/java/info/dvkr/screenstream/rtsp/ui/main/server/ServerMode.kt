package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.generateQRBitmap
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.RtspEvent
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.ui.RtspBindError
import info.dvkr.screenstream.rtsp.ui.RtspBinding
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun ServerMode(
    rtspState: State<RtspState>,
    sendEvent: (RtspEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (rtspState.value.serverBindings.isEmpty()) {
            val isError = rtspState.value.error != null
            val messageId = if (isError) R.string.rtsp_interfaces_no_address else R.string.rtsp_interfaces_discovering
            val messageColor = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant

            Text(
                text = stringResource(id = messageId),
                modifier = Modifier.padding(12.dp),
                color = messageColor,
                style = MaterialTheme.typography.titleMedium
            )
        } else {
            rtspState.value.serverBindings.forEachIndexed { index, binding ->
                AddressRow(
                    binding = binding,
                    isStreaming = rtspState.value.isStreaming,
                    sendEvent = sendEvent,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp, end = 0.dp)
                )
                if (index != rtspState.value.serverBindings.lastIndex) {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    binding: RtspBinding,
    isStreaming: Boolean,
    sendEvent: (RtspEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = stringResource(id = R.string.rtsp_interfaces_title))

        val fullAddress = binding.fullAddress
        val bindingFailed = binding.bindError != null

        Text(
            text = fullAddress,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            color = if (bindingFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium.copy(
                textDecoration = if (bindingFailed) TextDecoration.None else TextDecoration.Underline
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = stringResource(id = R.string.rtsp_interfaces_interface_label, binding.label),
                modifier = Modifier.weight(1F),
                style = MaterialTheme.typography.bodySmall,
            )
            CopyAddressButton(fullAddress)
            ShareAddressButton(fullAddress)
            ShowQRCodeButton(fullAddress)
        }

        binding.bindError?.let { bindError ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = bindError.toText(),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
                FilledTonalButton(
                    onClick = { sendEvent(RtspStreamingService.InternalEvent.RetryBindings) },
                    enabled = isStreaming.not(),
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(text = stringResource(id = R.string.rtsp_bindings_retry))
                }
            }
        }
    }
}

@Composable
private fun RtspBindError.toText(): String {
    val base = when (this) {
        RtspBindError.PortInUse -> stringResource(R.string.rtsp_bind_error_port_in_use)
        RtspBindError.AddressNotAvailable -> stringResource(R.string.rtsp_bind_error_address_not_available)
        RtspBindError.PermissionDenied -> stringResource(R.string.rtsp_bind_error_permission_denied)
        is RtspBindError.Unknown -> stringResource(R.string.rtsp_bind_error_unknown)
    }

    return if (this is RtspBindError.Unknown && technicalDetails.isNullOrBlank().not()) "$base [$technicalDetails]" else base
}

@Composable
private fun CopyAddressButton(
    fullAddress: String,
    clipboard: Clipboard = LocalClipboard.current,
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val context = LocalContext.current

    IconButton(
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(fullAddress, fullAddress))) }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, R.string.rtsp_interfaces_copied, Toast.LENGTH_LONG).show()
            }
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.content_copy_24px),
            contentDescription = stringResource(id = R.string.rtsp_interfaces_description_copy)
        )
    }
}

@Composable
private fun ShareAddressButton(
    fullAddress: String
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.rtsp_interfaces_share_title)

    IconButton(
        onClick = {
            val sharingIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, fullAddress)
            }
            context.startActivity(Intent.createChooser(sharingIntent, shareTitle))
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.share_24px),
            contentDescription = stringResource(id = R.string.rtsp_interfaces_description_share)
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
            contentDescription = stringResource(id = R.string.rtsp_interfaces_description_qr)
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
                Image(
                    bitmap = qrImageBitmap.value!!,
                    contentDescription = stringResource(id = R.string.rtsp_interfaces_qr_content_description)
                )
            }
        }
    }
}
