package info.dvkr.screenstream.rtmp.ui.main.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpUrl
import info.dvkr.screenstream.rtmp.settings.RtmpSettings

@Composable
internal fun ClientSettingsCard(
    settings: RtmpSettings.Data,
    updateSettings: (RtmpSettings.Data.() -> RtmpSettings.Data) -> Unit,
    windowSizeClass: WindowSizeClass,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val expanded = rememberSaveable { mutableStateOf(false) }
    var serverAddressValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = settings.serverAddress,
                selection = TextRange(settings.serverAddress.length)
            )
        )
    }
    val serverAddressError = runCatching { RtmpUrl.parse(serverAddressValue.text) }.isFailure

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtmp_client_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = serverAddressValue,
            onValueChange = { serverAddress ->
                val textChanged = serverAddress.text != serverAddressValue.text
                serverAddressValue = serverAddress
                if (textChanged) {
                    updateSettings { copy(serverAddress = serverAddress.text) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            enabled = enabled,
            label = { Text(text = stringResource(R.string.rtmp_server_address)) },
            isError = serverAddressError,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false
            ),
            singleLine = true
        )

    }
}

