package info.dvkr.screenstream.mjpeg.ui.main.cards

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
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.AddressFilterEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.AddressFilterRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.EnableIPv4Row
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.EnableIPv6Row
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.InterfaceFilterEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.InterfaceFilterRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.ServerPortEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.advanced.ServerPortRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.MjpegSettingModal

@Composable
internal fun AdvancedSettingsCard(
    settings: MjpegSettings.Data,
    updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    var selectedSheet by rememberSaveable { mutableStateOf<AdvancedSettingSheet?>(null) }
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
                    text = stringResource(R.string.mjpeg_pref_settings_advanced),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        InterfaceFilterRow(settings.interfaceFilter) { selectedSheet = AdvancedSettingSheet.InterfaceFilter }
        HorizontalDivider()

        AddressFilterRow(settings.addressFilter) { selectedSheet = AdvancedSettingSheet.AddressFilter }
        HorizontalDivider()

        EnableIPv4Row(settings.enableIPv4) { newValue ->
            updateSettings {
                if (!newValue && !enableIPv6) copy(enableIPv4 = false, enableIPv6 = true)
                else copy(enableIPv4 = newValue)
            }
        }
        HorizontalDivider()

        EnableIPv6Row(settings.enableIPv6) { newValue ->
            updateSettings {
                if (!newValue && !enableIPv4) copy(enableIPv6 = false, enableIPv4 = true)
                else copy(enableIPv6 = newValue)
            }
        }
        HorizontalDivider()

        ServerPortRow(settings.serverPort) { selectedSheet = AdvancedSettingSheet.ServerPort }

        selectedSheet?.let { sheet ->
            MjpegSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                when (sheet) {
                    AdvancedSettingSheet.InterfaceFilter -> InterfaceFilterEditor(settings.interfaceFilter) { value ->
                        if (settings.interfaceFilter != value) updateSettings { copy(interfaceFilter = value) }
                    }

                    AdvancedSettingSheet.AddressFilter -> AddressFilterEditor(settings.addressFilter) { value ->
                        if (settings.addressFilter != value) updateSettings { copy(addressFilter = value) }
                    }

                    AdvancedSettingSheet.ServerPort -> ServerPortEditor(settings.serverPort) { value ->
                        if (settings.serverPort != value) updateSettings { copy(serverPort = value) }
                    }
                }
            }
        }
    }
}

private enum class AdvancedSettingSheet(@get:StringRes val titleRes: Int) {
    InterfaceFilter(R.string.mjpeg_pref_interface_filter),
    AddressFilter(R.string.mjpeg_pref_address_filter),
    ServerPort(R.string.mjpeg_pref_server_port)
}
