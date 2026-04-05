package info.dvkr.screenstream.mjpeg.ui.main.settings.advanced

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow

private val interfaceOptions = listOf(
    R.string.mjpeg_pref_filter_wifi to MjpegSettings.Values.INTERFACE_WIFI,
    R.string.mjpeg_pref_filter_mobile to MjpegSettings.Values.INTERFACE_MOBILE,
    R.string.mjpeg_pref_filter_ethernet to MjpegSettings.Values.INTERFACE_ETHERNET,
    R.string.mjpeg_pref_filter_vpn to MjpegSettings.Values.INTERFACE_VPN
)

@Composable
internal fun InterfaceFilterRow(
    interfaceFilter: Int,
    onDetailShow: () -> Unit
) {
    val allInterfacesMask = MjpegSettings.Values.INTERFACE_WIFI or
            MjpegSettings.Values.INTERFACE_MOBILE or
            MjpegSettings.Values.INTERFACE_ETHERNET or
            MjpegSettings.Values.INTERFACE_VPN

    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.lan_24px,
        title = stringResource(R.string.mjpeg_pref_interface_filter),
        summary = stringResource(R.string.mjpeg_pref_interface_filter_summary),
        onClick = onDetailShow,
        trailingContent = {
            TriStateCheckbox(
                state = when (interfaceFilter) {
                    MjpegSettings.Values.INTERFACE_ALL -> ToggleableState.Off
                    allInterfacesMask -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = null,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    )
}

@Composable
internal fun InterfaceFilterEditor(
    interfaceFilter: Int,
    onValueChange: (Int) -> Unit
) {
    SettingEditorLayout {
        Row(
            modifier = Modifier
                .toggleable(
                    value = interfaceFilter == MjpegSettings.Values.INTERFACE_ALL,
                    onValueChange = { isChecked ->
                        onValueChange(if (isChecked) MjpegSettings.Values.INTERFACE_ALL else MjpegSettings.Default.INTERFACE_FILTER)
                    },
                    role = Role.Switch
                )
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mjpeg_pref_filter_no_restriction),
                modifier = Modifier.weight(1F)
            )
            Switch(
                checked = interfaceFilter == MjpegSettings.Values.INTERFACE_ALL,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8F)
            )
        }

        interfaceOptions.forEach { (stringResId, flag) ->
            FilterCheckboxRow(
                text = stringResource(stringResId),
                checked = interfaceFilter and flag != 0,
                enabled = interfaceFilter != MjpegSettings.Values.INTERFACE_ALL,
                onCheckedChange = { checked ->
                    val newValue = if (checked) interfaceFilter or flag else interfaceFilter and flag.inv()
                    onValueChange(if (newValue == MjpegSettings.Values.INTERFACE_ALL) MjpegSettings.Values.INTERFACE_ALL else newValue)
                }
            )
        }
    }
}
