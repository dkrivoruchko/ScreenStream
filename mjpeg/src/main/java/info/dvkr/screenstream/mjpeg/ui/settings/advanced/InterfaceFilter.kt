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

internal object InterfaceFilter : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.INTERFACE_FILTER.name
    override val position: Int = 0
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_interface_filter).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_interface_filter_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()

        InterfaceFilterUI(horizontalPadding, state.value.interfaceFilter, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        InterfaceFilterDetailUI(headerContent, state.value.interfaceFilter) {
            if (state.value.interfaceFilter != it) {
                scope.launch { mjpegSettings.updateData { copy(interfaceFilter = it) } }
            }
        }
    }
}

@Composable
private fun InterfaceFilterUI(horizontalPadding: Dp, interfaceFilter: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.lan_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_interface_filter),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_interface_filter_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val allInterfacesMask = MjpegSettings.Values.INTERFACE_WIFI or
                MjpegSettings.Values.INTERFACE_MOBILE or
                MjpegSettings.Values.INTERFACE_ETHERNET or
                MjpegSettings.Values.INTERFACE_VPN

        TriStateCheckbox(
            state = when {
                interfaceFilter == MjpegSettings.Values.INTERFACE_ALL -> ToggleableState.Off
                interfaceFilter == allInterfacesMask -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = null,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
}

private val interfaceOptions = listOf(
    R.string.mjpeg_pref_filter_wifi to MjpegSettings.Values.INTERFACE_WIFI,
    R.string.mjpeg_pref_filter_mobile to MjpegSettings.Values.INTERFACE_MOBILE,
    R.string.mjpeg_pref_filter_ethernet to MjpegSettings.Values.INTERFACE_ETHERNET,
    R.string.mjpeg_pref_filter_vpn to MjpegSettings.Values.INTERFACE_VPN
)

@Composable
private fun InterfaceFilterDetailUI(
    headerContent: @Composable (String) -> Unit,
    interfaceFilter: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_interface_filter))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
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
                    text = stringResource(id = R.string.mjpeg_pref_filter_no_restriction),
                    modifier = Modifier.weight(1F)
                )
                Switch(
                    checked = interfaceFilter == MjpegSettings.Values.INTERFACE_ALL,
                    onCheckedChange = null,
                    modifier = Modifier.scale(0.8F)
                )
            }

            interfaceOptions.forEach { (stringResId, flag) ->
                InterfaceCheckboxRow(
                    text = stringResource(id = stringResId),
                    checked = interfaceFilter and flag != 0,
                    enabled = interfaceFilter != MjpegSettings.Values.INTERFACE_ALL,
                    onCheckedChange = { checked ->
                        val newValue = if (checked) {
                            interfaceFilter or flag
                        } else {
                            interfaceFilter and flag.inv()
                        }
                        onValueChange(if (newValue == MjpegSettings.Values.INTERFACE_ALL) MjpegSettings.Values.INTERFACE_ALL else newValue)
                    }
                )
            }
        }
    }
}

@Composable
private fun InterfaceCheckboxRow(
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
