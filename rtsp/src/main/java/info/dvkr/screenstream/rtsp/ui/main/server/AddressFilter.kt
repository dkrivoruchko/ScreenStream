package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object AddressFilter : ModuleSettings.Item {
    override val id: String = RtspSettings.Key.ADDRESS_FILTER.name
    override val position: Int = 2
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.rtsp_pref_address_filter).contains(text, ignoreCase = true) ||
                getString(R.string.rtsp_pref_address_filter_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()

        AddressFilterUI(horizontalPadding, enabled, state.value.addressFilter, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        AddressFilterDetailUI(headerContent, state.value.addressFilter) {
            if (state.value.addressFilter != it) {
                scope.launch { rtspSettings.updateData { copy(addressFilter = it) } }
            }
        }
    }
}

@Composable
private fun AddressFilterUI(horizontalPadding: Dp, enabled: Boolean, addressFilter: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 12.dp, end = horizontalPadding + 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.ip_network_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_address_filter),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.rtsp_pref_address_filter_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val allAddressesMask = RtspSettings.Values.ADDRESS_PRIVATE or
                RtspSettings.Values.ADDRESS_LOCALHOST or
                RtspSettings.Values.ADDRESS_PUBLIC

        TriStateCheckbox(
            state = when {
                addressFilter == RtspSettings.Values.ADDRESS_ALL -> ToggleableState.Off
                addressFilter == allAddressesMask -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = null,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

private val addressOptions = listOf(
    R.string.rtsp_pref_address_filter_private to RtspSettings.Values.ADDRESS_PRIVATE,
    R.string.rtsp_pref_address_filter_localhost to RtspSettings.Values.ADDRESS_LOCALHOST,
    R.string.rtsp_pref_address_filter_public to RtspSettings.Values.ADDRESS_PUBLIC
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
        headerContent.invoke(stringResource(id = R.string.rtsp_pref_address_filter))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_address_filter_summary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .toggleable(
                        value = addressFilter == RtspSettings.Values.ADDRESS_ALL,
                        onValueChange = { isChecked ->
                            onValueChange(if (isChecked) RtspSettings.Values.ADDRESS_ALL else RtspSettings.Default.ADDRESS_FILTER)
                        },
                        role = Role.Switch
                    )
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 24.dp),
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
                AddressCheckboxRow(
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

        Spacer(modifier = Modifier.height(8.dp))
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
            .padding(start = 24.dp),
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