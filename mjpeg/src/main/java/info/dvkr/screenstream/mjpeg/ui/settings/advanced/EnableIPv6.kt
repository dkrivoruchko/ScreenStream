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

internal object EnableIPv6 : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ENABLE_IPV6.name
    override val position: Int = 3
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_enable_ipv6).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_enable_ipv6_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        EnableIPv6UI(horizontalPadding, mjpegSettingsState.value.enableIPv6) { newValue ->
            coroutineScope.launch {
                mjpegSettings.updateData {
                    if (!newValue && !enableIPv4) {
                        copy(enableIPv6 = false, enableIPv4 = true)
                    } else {
                        copy(enableIPv6 = newValue)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnableIPv6UI(
    horizontalPadding: Dp,
    enableIPv6: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = enableIPv6, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_IPv6, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_ipv6),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_ipv6_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = enableIPv6, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_IPv6: ImageVector = materialIcon(name = "IPv6") {
    materialPath {
        moveTo(1.32f, 16.71f)
        verticalLineTo(7.166f)
        horizontalLineToRelative(1.575f)
        verticalLineToRelative(9.544f)
        close()
    }
    materialPath {
        moveTo(4.406f, 16.71f)
        verticalLineTo(7.166f)
        horizontalLineToRelative(2.539f)
        quadToRelative(1.419f, 0.0f, 1.855f, 0.137f)
        quadToRelative(0.697f, 0.221f, 1.146f, 0.944f)
        quadToRelative(0.456f, 0.722f, 0.456f, 1.862f)
        quadToRelative(0.0f, 1.035f, -0.39f, 1.738f)
        quadToRelative(-0.392f, 0.697f, -0.977f, 0.983f)
        quadToRelative(-0.586f, 0.28f, -2.019f, 0.28f)
        horizontalLineTo(5.981f)
        verticalLineToRelative(3.6f)
        close()
        moveTo(5.98f, 8.78f)
        verticalLineToRelative(2.709f)
        horizontalLineToRelative(0.873f)
        quadToRelative(0.878f, 0.0f, 1.19f, -0.124f)
        quadToRelative(0.32f, -0.123f, 0.522f, -0.442f)
        quadToRelative(0.202f, -0.326f, 0.202f, -0.795f)
        quadToRelative(0.0f, -0.475f, -0.209f, -0.8f)
        quadToRelative(-0.208f, -0.326f, -0.514f, -0.437f)
        quadToRelative(-0.306f, -0.11f, -1.296f, -0.11f)
        close()
    }
    materialPath {
        moveTo(13.246f, 16.71f)
        lineToRelative(-2.285f, -6.914f)
        horizontalLineToRelative(1.575f)
        lineToRelative(1.068f, 3.529f)
        lineToRelative(0.306f, 1.178f)
        lineToRelative(0.319f, -1.178f)
        lineToRelative(1.08f, -3.529f)
        horizontalLineToRelative(1.537f)
        lineToRelative(-2.253f, 6.914f)
        close()
    }
    materialPath {
        moveTo(22.53f, 9.503f)
        lineToRelative(-1.452f, 0.196f)
        quadToRelative(-0.104f, -1.068f, -0.853f, -1.068f)
        quadToRelative(-0.488f, 0.0f, -0.814f, 0.534f)
        quadToRelative(-0.319f, 0.534f, -0.404f, 2.155f)
        quadToRelative(0.28f, -0.404f, 0.625f, -0.606f)
        quadToRelative(0.345f, -0.202f, 0.762f, -0.202f)
        quadToRelative(0.918f, 0.0f, 1.602f, 0.86f)
        quadToRelative(0.683f, 0.853f, 0.683f, 2.272f)
        quadToRelative(0.0f, 1.51f, -0.722f, 2.37f)
        quadToRelative(-0.723f, 0.859f, -1.79f, 0.859f)
        quadToRelative(-1.173f, 0.0f, -1.947f, -1.113f)
        quadToRelative(-0.769f, -1.12f, -0.769f, -3.705f)
        quadToRelative(0.0f, -2.623f, 0.801f, -3.776f)
        quadToRelative(0.801f, -1.152f, 2.058f, -1.152f)
        quadToRelative(0.865f, 0.0f, 1.458f, 0.592f)
        quadToRelative(0.599f, 0.586f, 0.762f, 1.784f)
        close()
        moveTo(19.138f, 13.494f)
        quadToRelative(0.0f, 0.905f, 0.332f, 1.387f)
        quadToRelative(0.338f, 0.475f, 0.768f, 0.475f)
        quadToRelative(0.417f, 0.0f, 0.69f, -0.397f)
        quadToRelative(0.28f, -0.397f, 0.28f, -1.302f)
        quadToRelative(0.0f, -0.938f, -0.3f, -1.367f)
        quadToRelative(-0.299f, -0.43f, -0.742f, -0.43f)
        quadToRelative(-0.43f, 0.0f, -0.729f, 0.41f)
        quadToRelative(-0.3f, 0.41f, -0.3f, 1.224f)
        close()
    }
}