package info.dvkr.screenstream.vnc.ui.main.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReconnectDelayEditor
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReconnectDelayRow
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReverseConnectHostEditor
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReverseConnectHostRow
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReverseConnectPortEditor
import info.dvkr.screenstream.vnc.ui.main.settings.client.ReverseConnectPortRow
import info.dvkr.screenstream.vnc.ui.main.settings.common.VncSettingModal

@Composable
internal fun ClientSettingsCard(
    settings: VncSettings.Data,
    updateSettings: (VncSettings.Data.() -> VncSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedSheet by rememberSaveable { mutableStateOf<ClientSettingSheet?>(null) }
    val expanded = rememberSaveable { mutableStateOf(false) }
    val reverseConnectHostError = settings.reverseConnectHost.isBlank()

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
                    text = stringResource(R.string.vnc_client_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        ReverseConnectHostRow(
            enabled = enabled,
            reverseConnectHost = settings.reverseConnectHost
        ) { selectedSheet = ClientSettingSheet.ReverseConnectHost }

        HorizontalDivider()

        ReverseConnectPortRow(
            enabled = enabled,
            reverseConnectPort = settings.reverseConnectPort
        ) { selectedSheet = ClientSettingSheet.ReverseConnectPort }

        HorizontalDivider()

        ReconnectDelayRow(
            enabled = enabled,
            reconnectDelaySeconds = settings.reconnectDelaySeconds
        ) { selectedSheet = ClientSettingSheet.ReconnectDelay }

        selectedSheet?.let { sheet ->
            VncSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                sheet.Editor(settings = settings, updateSettings = updateSettings)
            }
        }
    }
}

private enum class ClientSettingSheet(@get:StringRes val titleRes: Int) {
    ReverseConnectHost(R.string.vnc_pref_reverse_host),
    ReverseConnectPort(R.string.vnc_pref_reverse_port),
    ReconnectDelay(R.string.vnc_pref_reconnect_delay)
}

@Composable
private fun ClientSettingSheet.Editor(
    settings: VncSettings.Data,
    updateSettings: (VncSettings.Data.() -> VncSettings.Data) -> Unit
) {
    when (this) {
        ClientSettingSheet.ReverseConnectHost -> ReverseConnectHostEditor(
            reverseConnectHost = settings.reverseConnectHost,
            onValueChange = { value ->
                if (settings.reverseConnectHost != value) {
                    updateSettings { copy(reverseConnectHost = value) }
                }
            }
        )

        ClientSettingSheet.ReverseConnectPort -> ReverseConnectPortEditor(
            reverseConnectPort = settings.reverseConnectPort,
            onValueChange = { value ->
                if (settings.reverseConnectPort != value) {
                    updateSettings { copy(reverseConnectPort = value) }
                }
            }
        )

        ClientSettingSheet.ReconnectDelay -> ReconnectDelayEditor(
            reconnectDelaySeconds = settings.reconnectDelaySeconds,
            onValueChange = { value ->
                if (settings.reconnectDelaySeconds != value) {
                    updateSettings { copy(reconnectDelaySeconds = value) }
                }
            }
        )
    }
}
