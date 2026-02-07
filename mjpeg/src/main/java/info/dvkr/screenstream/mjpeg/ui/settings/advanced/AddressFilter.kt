package info.dvkr.screenstream.mjpeg.ui.settings.advanced

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object AddressFilter : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ADDRESS_FILTER.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_address_filter).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_address_filter_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()

        AddressFilterUI(horizontalPadding, state.value.addressFilter, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        AddressFilterDetailUI(headerContent, state.value.addressFilter) {
            if (state.value.addressFilter != it) {
                scope.launch { mjpegSettings.updateData { copy(addressFilter = it) } }
            }
        }
    }
}

@Composable
private fun AddressFilterUI(horizontalPadding: Dp, addressFilter: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.ip_network_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_address_filter),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_address_filter_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val allAddressesMask = MjpegSettings.Values.ADDRESS_PRIVATE or
                MjpegSettings.Values.ADDRESS_LOCALHOST or
                MjpegSettings.Values.ADDRESS_PUBLIC

        TriStateCheckbox(
            state = when {
                addressFilter == MjpegSettings.Values.ADDRESS_ALL -> ToggleableState.Off
                addressFilter == allAddressesMask -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = null,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
}

private val addressOptions = listOf(
    R.string.mjpeg_pref_filter_private to MjpegSettings.Values.ADDRESS_PRIVATE,
    R.string.mjpeg_pref_filter_localhost to MjpegSettings.Values.ADDRESS_LOCALHOST,
    R.string.mjpeg_pref_filter_public to MjpegSettings.Values.ADDRESS_PUBLIC
)

@Composable
private fun AddressFilterDetailUI(
    headerContent: @Composable (String) -> Unit,
    addressFilter: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_address_filter))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {

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
                    text = stringResource(id = R.string.mjpeg_pref_filter_no_restriction),
                    modifier = Modifier.weight(1F)
                )
                Switch(
                    checked = addressFilter == MjpegSettings.Values.ADDRESS_ALL,
                    onCheckedChange = null,
                    modifier = Modifier.scale(0.8F)
                )
            }

            addressOptions.forEach { (stringResId, flag) ->
                AddressCheckboxRow(
                    text = stringResource(id = stringResId),
                    checked = addressFilter and flag != 0,
                    enabled = addressFilter != MjpegSettings.Values.ADDRESS_ALL,
                    onCheckedChange = { checked ->
                        val newValue = if (checked) {
                            addressFilter or flag
                        } else {
                            addressFilter and flag.inv()
                        }
                        onValueChange(if (newValue == MjpegSettings.Values.ADDRESS_ALL) MjpegSettings.Values.ADDRESS_ALL else newValue)
                    }
                )
            }
        }
    }
}

@Composable
private fun AddressCheckboxRow(
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
                role = Role.Checkbox
            )
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .minimumInteractiveComponentSize()
            .conditional(!enabled) { alpha(0.5F) }
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
        Text(
            text = text,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
