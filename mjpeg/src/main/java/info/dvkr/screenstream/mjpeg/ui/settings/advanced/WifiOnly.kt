package info.dvkr.screenstream.mjpeg.ui.settings.advanced

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
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

internal object WifiOnly : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.USE_WIFI_ONLY.name
    override val position: Int = 0
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_wifi_only).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_wifi_only_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val useWiFiOnly = remember { derivedStateOf { mjpegSettingsState.value.useWiFiOnly } }
        val enabled = remember {
            derivedStateOf { (mjpegSettingsState.value.enableLocalHost && mjpegSettingsState.value.localHostOnly).not() }
        }

        WifiOnlyUI(horizontalPadding, useWiFiOnly.value, enabled.value) {
            if (useWiFiOnly.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(useWiFiOnly = it) } }
            }
        }
    }
}

@Composable
private fun WifiOnlyUI(
    horizontalPadding: Dp,
    useWiFiOnly: Boolean,
    enabled: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = useWiFiOnly, enabled = enabled, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enabled.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Wifi, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_wifi_only),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_wifi_only_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = useWiFiOnly, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Wifi: ImageVector = materialIcon(name = "Outlined.Wifi") {
    materialPath {
        moveTo(1.0f, 9.0f)
        lineToRelative(2.0f, 2.0f)
        curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18.0f, 0.0f)
        lineToRelative(2.0f, -2.0f)
        curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1.0f, 9.0f)
        close()
        moveTo(9.0f, 17.0f)
        lineToRelative(3.0f, 3.0f)
        lineToRelative(3.0f, -3.0f)
        curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6.0f, 0.0f)
        close()
        moveTo(5.0f, 13.0f)
        lineToRelative(2.0f, 2.0f)
        curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10.0f, 0.0f)
        lineToRelative(2.0f, -2.0f)
        curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5.0f, 13.0f)
        close()
    }
}