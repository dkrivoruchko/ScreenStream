package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
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
        Icon(imageVector = Icon_IPv4, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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

private val Icon_IPv4: ImageVector = materialIcon(name = "IPv4") {
    materialPath {
        moveTo(1.255f, 16.792f)
        verticalLineTo(7.247f)
        horizontalLineTo(2.83f)
        verticalLineToRelative(9.545f)
        close()
    }
    materialPath {
        moveTo(4.341f, 16.792f)
        verticalLineTo(7.247f)
        horizontalLineTo(6.88f)
        quadToRelative(1.419f, 0.0f, 1.855f, 0.137f)
        quadToRelative(0.697f, 0.221f, 1.146f, 0.944f)
        quadToRelative(0.456f, 0.723f, 0.456f, 1.862f)
        quadToRelative(0.0f, 1.035f, -0.391f, 1.738f)
        quadToRelative(-0.39f, 0.697f, -0.977f, 0.983f)
        quadToRelative(-0.586f, 0.28f, -2.018f, 0.28f)
        horizontalLineTo(5.916f)
        verticalLineToRelative(3.6f)
        close()
        moveTo(5.916f, 8.862f)
        verticalLineToRelative(2.708f)
        horizontalLineToRelative(0.872f)
        quadToRelative(0.88f, 0.0f, 1.192f, -0.123f)
        quadToRelative(0.319f, -0.124f, 0.52f, -0.443f)
        quadToRelative(0.202f, -0.326f, 0.202f, -0.794f)
        quadToRelative(0.0f, -0.476f, -0.208f, -0.801f)
        quadToRelative(-0.208f, -0.326f, -0.514f, -0.436f)
        quadToRelative(-0.306f, -0.111f, -1.296f, -0.111f)
        close()
    }
    materialPath {
        moveTo(13.182f, 16.792f)
        lineToRelative(-2.286f, -6.914f)
        horizontalLineToRelative(1.576f)
        lineToRelative(1.068f, 3.528f)
        lineToRelative(0.306f, 1.179f)
        lineToRelative(0.319f, -1.179f)
        lineToRelative(1.08f, -3.528f)
        horizontalLineToRelative(1.537f)
        lineToRelative(-2.253f, 6.914f)
        close()
    }
    materialPath {
        moveTo(20.324f, 16.792f)
        verticalLineToRelative(-1.92f)
        horizontalLineTo(17.12f)
        verticalLineTo(13.27f)
        lineToRelative(3.392f, -6.062f)
        horizontalLineToRelative(1.263f)
        verticalLineToRelative(6.055f)
        horizontalLineToRelative(0.97f)
        verticalLineToRelative(1.608f)
        horizontalLineToRelative(-0.97f)
        verticalLineToRelative(1.92f)
        close()
        moveTo(20.324f, 13.263f)
        verticalLineToRelative(-3.262f)
        lineToRelative(-1.797f, 3.262f)
        close()
    }
}