package info.dvkr.screenstream.rtsp.ui.main.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.settings.client.ClientProtocolEditor
import info.dvkr.screenstream.rtsp.ui.main.settings.client.ClientProtocolRow
import info.dvkr.screenstream.rtsp.ui.main.settings.common.RtspSettingModal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClientSettingsCard(
    settings: RtspSettings.Data,
    updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val selectedSheet = rememberSaveable { mutableStateOf<ClientSettingSheet?>(null) }
    val expanded = rememberSaveable { mutableStateOf(false) }
    val serverAddressError = runCatching { RtspUrl.parse(settings.serverAddress) }.isFailure

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
                    text = stringResource(R.string.rtsp_client_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = settings.serverAddress,
            onValueChange = { serverAddress ->
                if (settings.serverAddress != serverAddress) {
                    updateSettings { copy(serverAddress = serverAddress) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            enabled = enabled,
            label = { Text(text = stringResource(R.string.rtsp_server_address)) },
            isError = serverAddressError,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Uri
            ),
            singleLine = true
        )

        ClientProtocolRow(
            enabled = enabled,
            protocol = settings.clientProtocol
        ) { selectedSheet.value = ClientSettingSheet.Protocol }

        selectedSheet.value?.let { sheet ->
            RtspSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet.value = null }
            ) {
                sheet.Editor(settings = settings, updateSettings = updateSettings)
            }
        }
    }
}

private enum class ClientSettingSheet(@get:StringRes val titleRes: Int) {
    Protocol(R.string.rtsp_pref_protocol)
}

@Composable
private fun ClientSettingSheet.Editor(
    settings: RtspSettings.Data,
    updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit
) {
    when (this) {
        ClientSettingSheet.Protocol -> ClientProtocolEditor(
            protocol = settings.clientProtocol,
            onValueChange = { value ->
                if (settings.clientProtocol != value) {
                    updateSettings { copy(clientProtocol = value) }
                }
            }
        )
    }
}
