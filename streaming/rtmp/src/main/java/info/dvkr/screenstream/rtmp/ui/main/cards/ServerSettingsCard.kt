package info.dvkr.screenstream.rtmp.ui.main.cards

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
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.settings.RtmpSettings
import info.dvkr.screenstream.rtmp.ui.main.settings.common.RtmpSettingModal
import info.dvkr.screenstream.rtmp.ui.main.settings.server.AddressFilterEditor
import info.dvkr.screenstream.rtmp.ui.main.settings.server.AddressFilterRow
import info.dvkr.screenstream.rtmp.ui.main.settings.server.EnableIPv4Row
import info.dvkr.screenstream.rtmp.ui.main.settings.server.EnableIPv6Row
import info.dvkr.screenstream.rtmp.ui.main.settings.server.InterfaceFilterEditor
import info.dvkr.screenstream.rtmp.ui.main.settings.server.InterfaceFilterRow
import info.dvkr.screenstream.rtmp.ui.main.settings.server.ServerPortEditor
import info.dvkr.screenstream.rtmp.ui.main.settings.server.ServerPortRow

@Composable
internal fun ServerSettingsCard(
    settings: RtmpSettings.Data,
    updateSettings: (RtmpSettings.Data.() -> RtmpSettings.Data) -> Unit,
    windowSizeClass: WindowSizeClass,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedSheet by rememberSaveable { mutableStateOf<ServerSettingSheet?>(null) }
    val expanded = rememberSaveable { mutableStateOf(false) }

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
                    text = stringResource(R.string.rtmp_server_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        InterfaceFilterRow(
            enabled = enabled,
            interfaceFilter = settings.interfaceFilter
        ) { selectedSheet = ServerSettingSheet.InterfaceFilter }

        HorizontalDivider()

        AddressFilterRow(
            enabled = enabled,
            addressFilter = settings.addressFilter
        ) { selectedSheet = ServerSettingSheet.AddressFilter }

        HorizontalDivider()

        EnableIPv4Row(
            enabled = enabled,
            enableIPv4 = settings.enableIPv4
        ) { newValue ->
            updateSettings {
                if (!newValue && !enableIPv6) {
                    copy(enableIPv4 = false, enableIPv6 = true)
                } else {
                    copy(enableIPv4 = newValue)
                }
            }
        }

        HorizontalDivider()

        EnableIPv6Row(
            enabled = enabled,
            enableIPv6 = settings.enableIPv6
        ) { newValue ->
            updateSettings {
                if (!newValue && !enableIPv4) {
                    copy(enableIPv6 = false, enableIPv4 = true)
                } else {
                    copy(enableIPv6 = newValue)
                }
            }
        }

        HorizontalDivider()

        ServerPortRow(
            enabled = enabled,
            serverPort = settings.serverPort
        ) { selectedSheet = ServerSettingSheet.ServerPort }

        selectedSheet?.let { sheet ->
            RtmpSettingModal(
                windowSizeClass = windowSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                sheet.Editor(settings = settings, updateSettings = updateSettings)
            }
        }
    }
}

private enum class ServerSettingSheet(@get:StringRes val titleRes: Int) {
    InterfaceFilter(R.string.rtmp_pref_interface_filter),
    AddressFilter(R.string.rtmp_pref_address_filter),
    ServerPort(R.string.rtmp_pref_server_port)
}

@Composable
private fun ServerSettingSheet.Editor(
    settings: RtmpSettings.Data,
    updateSettings: (RtmpSettings.Data.() -> RtmpSettings.Data) -> Unit
) {
    when (this) {
        ServerSettingSheet.InterfaceFilter -> InterfaceFilterEditor(
            interfaceFilter = settings.interfaceFilter,
            onValueChange = { value ->
                if (settings.interfaceFilter != value) {
                    updateSettings { copy(interfaceFilter = value) }
                }
            }
        )

        ServerSettingSheet.AddressFilter -> AddressFilterEditor(
            addressFilter = settings.addressFilter,
            onValueChange = { value ->
                if (settings.addressFilter != value) {
                    updateSettings { copy(addressFilter = value) }
                }
            }
        )

        ServerSettingSheet.ServerPort -> ServerPortEditor(
            serverPort = settings.serverPort,
            onValueChange = { value ->
                if (settings.serverPort != value) {
                    updateSettings { copy(serverPort = value) }
                }
            }
        )
    }
}
