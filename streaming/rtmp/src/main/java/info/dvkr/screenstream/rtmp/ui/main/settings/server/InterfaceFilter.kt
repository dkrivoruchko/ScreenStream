package info.dvkr.screenstream.rtmp.ui.main.settings.server

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
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.settings.RtmpSettings
import info.dvkr.screenstream.rtmp.ui.main.settings.common.FilterCheckboxRow
import info.dvkr.screenstream.rtmp.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.rtmp.ui.main.settings.common.SettingValueRow

private val interfaceOptions = listOf(
    R.string.rtmp_pref_interface_filter_wifi to RtmpSettings.Values.INTERFACE_WIFI,
    R.string.rtmp_pref_interface_filter_mobile to RtmpSettings.Values.INTERFACE_MOBILE,
    R.string.rtmp_pref_interface_filter_ethernet to RtmpSettings.Values.INTERFACE_ETHERNET,
    R.string.rtmp_pref_interface_filter_vpn to RtmpSettings.Values.INTERFACE_VPN
)

@Composable
internal fun InterfaceFilterRow(
    enabled: Boolean,
    interfaceFilter: Int,
    onDetailShow: () -> Unit
) {
    val allInterfacesMask = RtmpSettings.Values.INTERFACE_WIFI or
            RtmpSettings.Values.INTERFACE_MOBILE or
            RtmpSettings.Values.INTERFACE_ETHERNET or
            RtmpSettings.Values.INTERFACE_VPN

    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.lan_24px,
        title = stringResource(id = R.string.rtmp_pref_interface_filter),
        summary = stringResource(id = R.string.rtmp_pref_interface_filter_summary),
        onClick = onDetailShow,
        trailingContent = {
            TriStateCheckbox(
                state = when {
                    interfaceFilter == RtmpSettings.Values.INTERFACE_ALL -> ToggleableState.Off
                    interfaceFilter == allInterfacesMask -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = null,
                modifier = Modifier.padding(end = 12.dp),
                enabled = enabled
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
        Text(
            text = stringResource(id = R.string.rtmp_pref_interface_filter_summary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .toggleable(
                    value = interfaceFilter == RtmpSettings.Values.INTERFACE_ALL,
                    onValueChange = { isChecked ->
                        onValueChange(if (isChecked) RtmpSettings.Values.INTERFACE_ALL else RtmpSettings.Default.INTERFACE_FILTER)
                    },
                    role = Role.Switch
                )
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.rtmp_pref_filter_no_restriction),
                modifier = Modifier.weight(1F)
            )
            Switch(
                checked = interfaceFilter == RtmpSettings.Values.INTERFACE_ALL,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8F)
            )
        }

        interfaceOptions.forEach { (stringResId, flag) ->
            FilterCheckboxRow(
                text = stringResource(id = stringResId),
                checked = interfaceFilter and flag != 0,
                enabled = interfaceFilter != RtmpSettings.Values.INTERFACE_ALL,
                onCheckedChange = { checked ->
                    val newValue = if (checked) {
                        interfaceFilter or flag
                    } else {
                        interfaceFilter and flag.inv()
                    }
                    onValueChange(if (newValue == RtmpSettings.Values.INTERFACE_ALL) RtmpSettings.Values.INTERFACE_ALL else newValue)
                }
            )
        }
    }
}
