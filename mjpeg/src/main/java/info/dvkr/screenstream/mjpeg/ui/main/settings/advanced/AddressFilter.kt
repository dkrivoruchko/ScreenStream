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

private val addressOptions = listOf(
    R.string.mjpeg_pref_filter_private to MjpegSettings.Values.ADDRESS_PRIVATE,
    R.string.mjpeg_pref_filter_localhost to MjpegSettings.Values.ADDRESS_LOCALHOST,
    R.string.mjpeg_pref_filter_public to MjpegSettings.Values.ADDRESS_PUBLIC
)

@Composable
internal fun AddressFilterRow(
    addressFilter: Int,
    onDetailShow: () -> Unit
) {
    val allAddressesMask = MjpegSettings.Values.ADDRESS_PRIVATE or
            MjpegSettings.Values.ADDRESS_LOCALHOST or
            MjpegSettings.Values.ADDRESS_PUBLIC

    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.ip_network_24px,
        title = stringResource(R.string.mjpeg_pref_address_filter),
        summary = stringResource(R.string.mjpeg_pref_address_filter_summary),
        onClick = onDetailShow,
        trailingContent = {
            TriStateCheckbox(
                state = when (addressFilter) {
                    MjpegSettings.Values.ADDRESS_ALL -> ToggleableState.Off
                    allAddressesMask -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                },
                onClick = null,
                modifier = Modifier.padding(end = 8.dp)
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
        Row(
            modifier = Modifier
                .toggleable(
                    value = addressFilter == MjpegSettings.Values.ADDRESS_ALL,
                    onValueChange = { isChecked ->
                        onValueChange(if (isChecked) MjpegSettings.Values.ADDRESS_ALL else MjpegSettings.Default.ADDRESS_FILTER)
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
                checked = addressFilter == MjpegSettings.Values.ADDRESS_ALL,
                onCheckedChange = null,
                modifier = Modifier.scale(0.8F)
            )
        }

        addressOptions.forEach { (stringResId, flag) ->
            FilterCheckboxRow(
                text = stringResource(stringResId),
                checked = addressFilter and flag != 0,
                enabled = addressFilter != MjpegSettings.Values.ADDRESS_ALL,
                onCheckedChange = { checked ->
                    val newValue = if (checked) addressFilter or flag else addressFilter and flag.inv()
                    onValueChange(if (newValue == MjpegSettings.Values.ADDRESS_ALL) MjpegSettings.Values.ADDRESS_ALL else newValue)
                }
            )
        }
    }
}
