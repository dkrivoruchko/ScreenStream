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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import androidx.compose.ui.graphics.vector.ImageVector
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

internal object InterfaceFilter : ModuleSettings.Item {
    override val id: String = RtspSettings.Key.INTERFACE_FILTER.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_interface_filter).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_interface_filter_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<RtspSettings>()
        val state = mjpegSettings.data.collectAsStateWithLifecycle()

        InterfaceFilterUI(horizontalPadding, enabled, state.value.interfaceFilter, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        InterfaceFilterDetailUI(headerContent, state.value.interfaceFilter) {
            if (state.value.interfaceFilter != it) {
                scope.launch { rtspSettings.updateData { copy(interfaceFilter = it) } }
            }
        }
    }
}

@Composable
private fun InterfaceFilterUI(horizontalPadding: Dp, enabled: Boolean, interfaceFilter: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 12.dp, end = horizontalPadding + 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Network, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_interface_filter),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_interface_filter_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val allInterfacesMask = RtspSettings.Values.INTERFACE_WIFI or
                RtspSettings.Values.INTERFACE_MOBILE or
                RtspSettings.Values.INTERFACE_ETHERNET or
                RtspSettings.Values.INTERFACE_VPN

        TriStateCheckbox(
            state = when {
                interfaceFilter == RtspSettings.Values.INTERFACE_ALL -> ToggleableState.Off
                interfaceFilter == allInterfacesMask -> ToggleableState.On
                else -> ToggleableState.Indeterminate
            },
            onClick = null,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

private val interfaceOptions = listOf(
    R.string.rtsp_pref_filter_wifi to RtspSettings.Values.INTERFACE_WIFI,
    R.string.rtsp_pref_filter_mobile to RtspSettings.Values.INTERFACE_MOBILE,
    R.string.rtsp_pref_filter_ethernet to RtspSettings.Values.INTERFACE_ETHERNET,
    R.string.rtsp_pref_filter_vpn to RtspSettings.Values.INTERFACE_VPN
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_interface_filter_summary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .toggleable(
                        value = interfaceFilter == RtspSettings.Values.INTERFACE_ALL,
                        onValueChange = { isChecked ->
                            onValueChange(if (isChecked) RtspSettings.Values.INTERFACE_ALL else RtspSettings.Default.INTERFACE_FILTER)
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
                    text = stringResource(id = R.string.mjpeg_pref_filter_no_restriction),
                    modifier = Modifier.weight(1F)
                )
                Switch(
                    checked = interfaceFilter == RtspSettings.Values.INTERFACE_ALL,
                    onCheckedChange = null,
                    modifier = Modifier.scale(0.8F)
                )
            }

            interfaceOptions.forEach { (stringResId, flag) ->
                InterfaceCheckboxRow(
                    text = stringResource(id = stringResId),
                    checked = interfaceFilter and flag != 0,
                    enabled = interfaceFilter != RtspSettings.Values.INTERFACE_ALL,
                    onCheckedChange = { checked ->
                        val newValue = if (checked) {
                            interfaceFilter or flag
                        } else {
                            interfaceFilter and flag.inv()
                        }
                        onValueChange(if (newValue == RtspSettings.Values.INTERFACE_ALL) RtspSettings.Values.INTERFACE_ALL else newValue)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
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
            .padding(horizontal = 24.dp),
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

private val Icon_Network: ImageVector = materialIcon(name = "Network") {
    materialPath {
        moveTo(10.0F, 2.0F)
        curveTo(8.89F, 2.0F, 8.0F, 2.89F, 8.0F, 4.0F)
        verticalLineTo(7.0F)
        curveTo(8.0F, 8.11F, 8.89F, 9.0F, 10.0F, 9.0F)
        horizontalLineTo(11.0F)
        verticalLineTo(11.0F)
        horizontalLineTo(2.0F)
        verticalLineTo(13.0F)
        horizontalLineTo(6.0F)
        verticalLineTo(15.0F)
        horizontalLineTo(5.0F)
        curveTo(3.89F, 15.0F, 3.0F, 15.89F, 3.0F, 17.0F)
        verticalLineTo(20.0F)
        curveTo(3.0F, 21.11F, 3.89F, 22.0F, 5.0F, 22.0F)
        horizontalLineTo(9.0F)
        curveTo(10.11F, 22.0F, 11.0F, 21.11F, 11.0F, 20.0F)
        verticalLineTo(17.0F)
        curveTo(11.0F, 15.89F, 10.11F, 15.0F, 9.0F, 15.0F)
        horizontalLineTo(8.0F)
        verticalLineTo(13.0F)
        horizontalLineTo(16.0F)
        verticalLineTo(15.0F)
        horizontalLineTo(15.0F)
        curveTo(13.89F, 15.0F, 13.0F, 15.89F, 13.0F, 17.0F)
        verticalLineTo(20.0F)
        curveTo(13.0F, 21.11F, 13.89F, 22.0F, 15.0F, 22.0F)
        horizontalLineTo(19.0F)
        curveTo(20.11F, 22.0F, 21.0F, 21.11F, 21.0F, 20.0F)
        verticalLineTo(17.0F)
        curveTo(21.0F, 15.89F, 20.11F, 15.0F, 19.0F, 15.0F)
        horizontalLineTo(18.0F)
        verticalLineTo(13.0F)
        horizontalLineTo(22.0F)
        verticalLineTo(11.0F)
        horizontalLineTo(13.0F)
        verticalLineTo(9.0F)
        horizontalLineTo(14.0F)
        curveTo(15.11F, 9.0F, 16.0F, 8.11F, 16.0F, 7.0F)
        verticalLineTo(4.0F)
        curveTo(16.0F, 2.89F, 15.11F, 2.0F, 14.0F, 2.0F)
        horizontalLineTo(10.0F)
        close()
        moveTo(10.0F, 4.0F)
        horizontalLineTo(14.0F)
        verticalLineTo(7.0F)
        horizontalLineTo(10.0F)
        verticalLineTo(4.0F)
        close()
        moveTo(5.0F, 17.0F)
        horizontalLineTo(9.0F)
        verticalLineTo(20.0F)
        horizontalLineTo(5.0F)
        verticalLineTo(17.0F)
        close()
        moveTo(15.0F, 17.0F)
        horizontalLineTo(19.0F)
        verticalLineTo(20.0F)
        horizontalLineTo(15.0F)
        verticalLineTo(17.0F)
        close()
    }
}