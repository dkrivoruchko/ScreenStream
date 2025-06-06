package info.dvkr.screenstream.mjpeg.ui.settings.advanced

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.res.stringResource
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
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
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
        Icon(imageVector = Icon_Network, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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
    }
}

@Composable
private fun InterfaceFilterDetailUI(
    headerContent: @Composable (String) -> Unit,
    interfaceFilter: Int,
    onValueChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_interface_filter))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .toggleable(value = interfaceFilter == 0, onValueChange = { if (it) onValueChange(0) })
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.mjpeg_pref_filter_no_restriction),
                    modifier = Modifier.weight(1F)
                )
                Switch(checked = interfaceFilter == 0, onCheckedChange = null, modifier = Modifier.scale(0.7F))
            }
            val enabled = interfaceFilter != 0
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_wifi),
                checked = interfaceFilter and MjpegSettings.Values.INTERFACE_WIFI != 0,
                enabled = enabled
            ) { checked ->
                var value = interfaceFilter
                value = if (checked) value or MjpegSettings.Values.INTERFACE_WIFI else value and MjpegSettings.Values.INTERFACE_WIFI.inv()
                onValueChange(value)
            }
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_mobile),
                checked = interfaceFilter and MjpegSettings.Values.INTERFACE_MOBILE != 0,
                enabled = enabled
            ) { checked ->
                var value = interfaceFilter
                value = if (checked) value or MjpegSettings.Values.INTERFACE_MOBILE else value and MjpegSettings.Values.INTERFACE_MOBILE.inv()
                onValueChange(value)
            }
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_ethernet),
                checked = interfaceFilter and MjpegSettings.Values.INTERFACE_ETHERNET != 0,
                enabled = enabled
            ) { checked ->
                var value = interfaceFilter
                value = if (checked) value or MjpegSettings.Values.INTERFACE_ETHERNET else value and MjpegSettings.Values.INTERFACE_ETHERNET.inv()
                onValueChange(value)
            }
            FilterCheckBox(
                text = stringResource(id = R.string.mjpeg_pref_filter_vpn),
                checked = interfaceFilter and MjpegSettings.Values.INTERFACE_VPN != 0,
                enabled = enabled
            ) { checked ->
                var value = interfaceFilter
                value = if (checked) value or MjpegSettings.Values.INTERFACE_VPN else value and MjpegSettings.Values.INTERFACE_VPN.inv()
                onValueChange(value)
            }
        }
    }
}

@Composable
private fun FilterCheckBox(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp)
            .conditional(enabled.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = text, modifier = Modifier.padding(start = 8.dp))
    }
}

private val Icon_Network: ImageVector = materialIcon(name = "Network") {
    materialPath { moveTo(12f,2f); lineTo(12f,22f) }
}
