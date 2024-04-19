package info.dvkr.screenstream.mjpeg.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.generateQRBitmap
import info.dvkr.screenstream.common.openStringUrl
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.MjpegState

@Composable
internal fun InterfacesCard(
    mjpegState: State<MjpegState>,
    modifier: Modifier = Modifier,
) {
    val interfaces = remember { derivedStateOf { mjpegState.value.serverNetInterfaces } }

    ElevatedCard(modifier = modifier) {
        if (interfaces.value.isEmpty()) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(text = stringResource(id = R.string.mjpeg_item_address))
                Text(
                    text = stringResource(id = R.string.mjpeg_stream_no_address),
                    modifier = Modifier.padding(top = 16.dp),
                    fontSize = 18.sp,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        } else {
            interfaces.value.forEachIndexed { index, netInterface ->
                AddressCard(
                    fullAddress = netInterface.fullAddress,
                    interfaceName = netInterface.name,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 12.dp, end = 0.dp)
                )

                if (index != interfaces.value.lastIndex) {
                    HorizontalDivider(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AddressCard(
    fullAddress: String,
    interfaceName: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(text = stringResource(id = R.string.mjpeg_item_address))

        val context = LocalContext.current
        Text(
            text = fullAddress,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 4.dp)
                .clickable(role = Role.Button) {
                    context.openStringUrl(fullAddress) { error ->
                        val messageId = when (error) {
                            is ActivityNotFoundException -> R.string.mjpeg_stream_no_web_browser_found
                            else -> R.string.mjpeg_stream_external_app_error
                        }
                        Toast
                            .makeText(context, messageId, Toast.LENGTH_LONG)
                            .show()
                    }
                },
            color = MaterialTheme.colorScheme.primary,
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.Underline)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_stream_interface, interfaceName),
                modifier = Modifier.weight(1F),
                style = MaterialTheme.typography.bodySmall,
            )
            OpenInBrowserButton(fullAddress)
            CopyAddressButton(fullAddress)
            ShareAddressButton(fullAddress)
            ShowQRCodeButton(fullAddress)
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
                    is ActivityNotFoundException -> R.string.mjpeg_stream_no_web_browser_found
                    else -> R.string.mjpeg_stream_external_app_error
                }
                Toast.makeText(context, messageId, Toast.LENGTH_LONG).show()
            }
        }
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(id = R.string.mjpeg_item_address_description_open_address)
        )
    }
}

@Composable
private fun CopyAddressButton(
    fullAddress: String,
    clipboardManager: ClipboardManager = LocalClipboardManager.current
) {
    val context = LocalContext.current

    IconButton(
        onClick = {
            clipboardManager.setText(AnnotatedString(fullAddress))
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                Toast.makeText(context, R.string.mjpeg_stream_copied, Toast.LENGTH_LONG).show()
            }
        }
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = stringResource(id = R.string.mjpeg_item_address_description_copy_address)
        )
    }
}

@Composable
private fun ShareAddressButton(
    fullAddress: String
) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.mjpeg_stream_share_address)

    IconButton(
        onClick = {
            val sharingIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, fullAddress) }
            context.startActivity(Intent.createChooser(sharingIntent, shareTitle))
        }
    ) {
        Icon(
            imageVector = Icons.Default.Share,
            contentDescription = stringResource(id = R.string.mjpeg_item_address_description_share_address)
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
            contentDescription = stringResource(id = R.string.mjpeg_item_address_description_qr_address)
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
                Image(bitmap = qrImageBitmap.value!!, contentDescription = stringResource(id = R.string.mjpeg_item_address_description_qr))
            }
        }
    }
}