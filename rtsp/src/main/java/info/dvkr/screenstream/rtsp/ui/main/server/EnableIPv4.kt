package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object EnableIPv4 : ModuleSettings.Item {
    override val id: String = RtspSettings.Key.ENABLE_IPV4.name
    override val position: Int = 3
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.rtsp_pref_enable_ipv4).contains(text, ignoreCase = true) ||
                getString(R.string.rtsp_pref_enable_ipv4_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()

        EnableIpv4UI(horizontalPadding, enabled, state.value.enableIPv4) { newValue ->
            coroutineScope.launch {
                rtspSettings.updateData {
                    if (!newValue && !enableIPv6) {
                        copy(enableIPv4 = false, enableIPv6 = true)
                    } else {
                        copy(enableIPv4 = newValue)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnableIpv4UI(horizontalPadding: Dp, enabled: Boolean, enableIPv4: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .toggleable(value = enableIPv4, enabled = enabled, role = Role.Checkbox, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 12.dp, end = horizontalPadding + 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.ip_v4), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_enable_ipv4),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.rtsp_pref_enable_ipv4_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = enableIPv4, onCheckedChange = null, modifier = Modifier.scale(0.7F), enabled = enabled)
    }
}
