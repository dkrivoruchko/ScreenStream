package info.dvkr.screenstream.rtsp.ui.main.settings.server

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
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.settings.common.FilterCheckboxRow
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingValueRow

private val addressOptions = listOf(
    R.string.rtsp_pref_address_filter_private to RtspSettings.Values.ADDRESS_PRIVATE,
    R.string.rtsp_pref_address_filter_localhost to RtspSettings.Values.ADDRESS_LOCALHOST,
    R.string.rtsp_pref_address_filter_public to RtspSettings.Values.ADDRESS_PUBLIC
)

@Composable
internal fun AddressFilterRow(
    enabled: Boolean,
    addressFilter: Int,
    onDetailShow: () -> Unit
) {
    val allAddressesMask = RtspSettings.Values.ADDRESS_PRIVATE or
            RtspSettings.Values.ADDRESS_LOCALHOST or
            RtspSettings.Values.ADDRESS_PUBLIC

    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.ip_network_24px,
        title = stringResource(id = R.string.rtsp_pref_address_filter),
        summary = stringResource(id = R.string.rtsp_pref_address_filter_summary),
        onClick = onDetailShow,
        trailingContent = {
            TriStateCheckbox(
                state = when {
                    addressFilter == RtspSettings.Values.ADDRESS_ALL -> ToggleableState.Off
                    addressFilter == allAddressesMask -> ToggleableState.On
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
internal fun AddressFilterEditor(
    addressFilter: Int,
    onValueChange: (Int) -> Unit
) {
    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.rtsp_pref_address_filter_summary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Row(
            modifier = Modifier
                .toggleable(
                    value = addressFilter == RtspSettings.Values.ADDRESS_ALL,
                    onValueChange = { isChecked ->
                        onValueChange(if (isChecked) RtspSettings.Values.ADDRESS_ALL else RtspSettings.Default.ADDRESS_FILTER)
                    },
                    role = Role.Switch
                )
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .minimumInteractiveComponentSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_filter_no_restriction),
                modifier = Modifier.weight(1F)
            )
            Switch(
                checked = addressFilter == RtspSettings.Values.ADDRESS_ALL,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8F)
            )
        }

        addressOptions.forEach { (stringResId, flag) ->
            FilterCheckboxRow(
                text = stringResource(id = stringResId),
                checked = addressFilter and flag != 0,
                enabled = addressFilter != RtspSettings.Values.ADDRESS_ALL,
                onCheckedChange = { checked ->
                    val newValue = if (checked) {
                        addressFilter or flag
                    } else {
                        addressFilter and flag.inv()
                    }
                    onValueChange(if (newValue == RtspSettings.Values.ADDRESS_ALL) RtspSettings.Values.ADDRESS_ALL else newValue)
                }
            )
        }
    }
}
