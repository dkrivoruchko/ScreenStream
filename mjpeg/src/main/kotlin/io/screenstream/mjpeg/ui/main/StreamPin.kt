package io.screenstream.mjpeg.ui.main

import android.content.ClipData
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.launch

@Composable
internal fun StreamPinContent(
    pin: SecretValue,
    hidePinOnCapture: Boolean,
    onShowMessage: (String) -> Unit,
) {
    var pinRevealed by remember(pin, hidePinOnCapture) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val hiddenPinDescription = stringResource(R.string.mjpeg_stream_pin_hidden)
    val revealActionDescription = stringResource(if (pinRevealed) R.string.mjpeg_stream_pin_hide else R.string.mjpeg_stream_pin_show)
    val onCopyPin: () -> Unit = {
        coroutineScope.launch {
            val clip = ClipData.newPlainText(resources.getString(R.string.mjpeg_stream_pin_copy), pin.value)
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clipboard.setClipEntry(ClipEntry(clip))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onShowMessage(resources.getString(R.string.mjpeg_stream_pin_copied))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.mjpeg_stream_pin_label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (pinRevealed) pin.value else stringResource(R.string.mjpeg_pref_access_pin_mask),
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = if (pinRevealed) pin.value.toList().joinToString(" ") else hiddenPinDescription
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { pinRevealed = !pinRevealed },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics { contentDescription = revealActionDescription },
                ) {
                    Text(stringResource(if (pinRevealed) R.string.mjpeg_pref_access_pin_hide else R.string.mjpeg_pref_access_pin_show))
                }
                TextButton(
                    onClick = onCopyPin,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.mjpeg_stream_pin_copy))
                }
            }
        }
        Text(
            text = stringResource(R.string.mjpeg_stream_pin_hint_access),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
