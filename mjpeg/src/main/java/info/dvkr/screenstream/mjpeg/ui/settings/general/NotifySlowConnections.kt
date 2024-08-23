package info.dvkr.screenstream.mjpeg.ui.settings.general

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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object NotifySlowConnections : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.NOTIFY_SLOW_CONNECTIONS.name
    override val position: Int = 3
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_detect_slow_connections).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_detect_slow_connections_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        NotifySlowConnectionsUI(horizontalPadding, mjpegSettingsState.value.notifySlowConnections) {
            if (mjpegSettingsState.value.notifySlowConnections != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(notifySlowConnections = it) } }
            }
        }
    }
}

@Composable
private fun NotifySlowConnectionsUI(
    horizontalPadding: Dp,
    notifySlowConnections: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .toggleable(value = notifySlowConnections, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_SlowMotion, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_detect_slow_connections),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_detect_slow_connections_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = notifySlowConnections, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private var Icon_SlowMotion: ImageVector = materialIcon(name = "SlowMotion") {
    materialPath {
        moveTo(10.574f, 15.775f)
        lineToRelative(4.908f, -3.146f)
        quadToRelative(0.352f, -0.226f, 0.352f, -0.629f)
        reflectiveQuadToRelative(-0.352f, -0.63f)
        lineToRelative(-4.908f, -3.145f)
        quadToRelative(-0.378f, -0.252f, -0.768f, -0.038f)
        reflectiveQuadToRelative(-0.39f, 0.667f)
        verticalLineToRelative(6.292f)
        quadToRelative(0f, 0.453f, 0.39f, 0.667f)
        quadToRelative(0.39f, 0.214f, 0.768f, -0.038f)
        close()
        moveToRelative(-7.676f, -2.768f)
        quadToRelative(0.427f, 0f, 0.767f, 0.277f)
        quadToRelative(0.34f, 0.276f, 0.466f, 0.704f)
        quadToRelative(0.15f, 0.58f, 0.365f, 1.095f)
        quadToRelative(0.214f, 0.516f, 0.49f, 1.02f)
        quadToRelative(0.227f, 0.377f, 0.19f, 0.805f)
        quadToRelative(-0.038f, 0.428f, -0.34f, 0.73f)
        quadToRelative(-0.277f, 0.276f, -0.68f, 0.251f)
        quadToRelative(-0.403f, -0.025f, -0.63f, -0.352f)
        quadToRelative(-0.553f, -0.78f, -0.93f, -1.674f)
        quadToRelative(-0.378f, -0.893f, -0.58f, -1.85f)
        quadToRelative(-0.075f, -0.402f, 0.19f, -0.704f)
        quadToRelative(0.264f, -0.302f, 0.692f, -0.302f)
        close()
        moveToRelative(1.938f, -6.645f)
        quadToRelative(0.302f, 0.302f, 0.327f, 0.73f)
        quadToRelative(0.025f, 0.428f, -0.176f, 0.78f)
        quadToRelative(-0.277f, 0.504f, -0.491f, 1.032f)
        quadToRelative(-0.214f, 0.529f, -0.365f, 1.108f)
        quadToRelative(-0.126f, 0.428f, -0.466f, 0.704f)
        quadToRelative(-0.34f, 0.277f, -0.767f, 0.277f)
        quadToRelative(-0.428f, 0f, -0.692f, -0.314f)
        quadToRelative(-0.265f, -0.315f, -0.164f, -0.718f)
        quadToRelative(0.201f, -0.956f, 0.579f, -1.85f)
        quadToRelative(0.377f, -0.893f, 0.906f, -1.673f)
        quadToRelative(0.226f, -0.327f, 0.629f, -0.34f)
        quadToRelative(0.403f, -0.012f, 0.68f, 0.264f)
        close()
        moveToRelative(1.409f, 12.71f)
        quadToRelative(0.302f, -0.327f, 0.742f, -0.352f)
        quadToRelative(0.44f, -0.025f, 0.818f, 0.201f)
        quadToRelative(0.504f, 0.277f, 1.02f, 0.504f)
        quadToRelative(0.516f, 0.226f, 1.07f, 0.377f)
        quadToRelative(0.427f, 0.126f, 0.704f, 0.453f)
        quadToRelative(0.277f, 0.327f, 0.277f, 0.755f)
        reflectiveQuadToRelative(-0.315f, 0.68f)
        quadToRelative(-0.314f, 0.251f, -0.717f, 0.176f)
        quadToRelative(-0.956f, -0.202f, -1.8f, -0.58f)
        quadToRelative(-0.843f, -0.377f, -1.648f, -0.88f)
        quadToRelative(-0.352f, -0.226f, -0.39f, -0.63f)
        quadToRelative(-0.038f, -0.402f, 0.239f, -0.704f)
        close()
        moveTo(10.926f, 2.99f)
        quadToRelative(0f, 0.428f, -0.264f, 0.755f)
        reflectiveQuadToRelative(-0.692f, 0.453f)
        quadToRelative(-0.58f, 0.151f, -1.108f, 0.365f)
        quadToRelative(-0.528f, 0.214f, -1.031f, 0.516f)
        quadToRelative(-0.378f, 0.226f, -0.818f, 0.189f)
        quadToRelative(-0.44f, -0.038f, -0.743f, -0.34f)
        quadToRelative(-0.302f, -0.302f, -0.264f, -0.717f)
        quadToRelative(0.038f, -0.416f, 0.39f, -0.642f)
        quadTo(7.2f, 3.065f, 8.07f, 2.7f)
        quadToRelative(0.868f, -0.365f, 1.824f, -0.567f)
        quadToRelative(0.403f, -0.075f, 0.718f, 0.176f)
        quadToRelative(0.314f, 0.252f, 0.314f, 0.68f)
        close()
        moveToRelative(9.06f, 9.01f)
        quadToRelative(0f, -2.844f, -1.749f, -5.021f)
        reflectiveQuadTo(13.77f, 4.148f)
        quadToRelative(-0.377f, -0.101f, -0.604f, -0.428f)
        quadToRelative(-0.226f, -0.327f, -0.226f, -0.73f)
        quadToRelative(0f, -0.403f, 0.276f, -0.667f)
        quadToRelative(0.277f, -0.264f, 0.63f, -0.189f)
        quadToRelative(3.523f, 0.705f, 5.839f, 3.448f)
        quadTo(22f, 8.326f, 22f, 12f)
        quadToRelative(0f, 3.675f, -2.315f, 6.418f)
        quadToRelative(-2.316f, 2.743f, -5.84f, 3.448f)
        quadToRelative(-0.352f, 0.075f, -0.629f, -0.189f)
        quadToRelative(-0.276f, -0.264f, -0.276f, -0.667f)
        reflectiveQuadToRelative(0.226f, -0.73f)
        quadToRelative(0.227f, -0.327f, 0.604f, -0.428f)
        quadToRelative(2.718f, -0.654f, 4.467f, -2.831f)
        quadToRelative(1.75f, -2.177f, 1.75f, -5.021f)
        close()
    }
}

