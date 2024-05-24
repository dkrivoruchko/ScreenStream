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

internal object EnableLocalhost : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.ENABLE_LOCAL_HOST.name
    override val position: Int = 2
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_enable_localhost).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_enable_localhost_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val enableLocalHost = remember { derivedStateOf { mjpegSettingsState.value.enableLocalHost } }

        EnableLocalhostUI(horizontalPadding, enableLocalHost.value) {
            if (enableLocalHost.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(enableLocalHost = it) } }
            }
        }
    }
}

@Composable
private fun EnableLocalhostUI(
    horizontalPadding: Dp,
    enableLocalHost: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = enableLocalHost, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_LocalHost, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_localhost),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_enable_localhost_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = enableLocalHost, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_LocalHost: ImageVector = materialIcon(name = "LocalHost") {
    materialPath {
        moveToRelative(5.0F, 1.9763F)
        curveToRelative(-1.1F, 0.0F, -2.0F, 0.9F, -2.0F, 2.0F)
        verticalLineToRelative(4.0F)
        curveTo(3.0F, 9.0863F, 3.9F, 9.9763F, 5.0F, 9.9763F)
        horizontalLineToRelative(6.0F)
        verticalLineToRelative(4.0F)
        horizontalLineToRelative(-1.0F)
        curveToRelative(-0.55F, 0.0F, -1.0F, 0.45F, -1.0F, 1.0F)
        horizontalLineTo(2.0F)
        verticalLineToRelative(2.0F)
        horizontalLineToRelative(7.0F)
        curveToRelative(0.0F, 0.55F, 0.45F, 1.0F, 1.0F, 1.0F)
        horizontalLineToRelative(4.0F)
        curveToRelative(0.55F, 0.0F, 1.0F, -0.45F, 1.0F, -1.0F)
        horizontalLineToRelative(7.0F)
        verticalLineToRelative(-2.0F)
        horizontalLineToRelative(-7.0F)
        curveToRelative(-0.0027F, -0.8173F, -0.377F, -1.0F, -1.0F, -1.0F)
        horizontalLineToRelative(-1.0F)
        verticalLineToRelative(-4.0F)
        horizontalLineToRelative(6.0F)
        curveToRelative(1.11F, 0.0F, 2.0F, -0.89F, 2.0F, -2.0F)
        verticalLineToRelative(-4.0F)
        curveToRelative(0.0F, -1.1F, -0.89F, -2.0F, -2.0F, -2.0F)
        horizontalLineTo(5.0F)
        moveToRelative(1.0F, 3.0F)
        horizontalLineToRelative(2.0F)
        verticalLineToRelative(2.0F)
        horizontalLineTo(6.0F)
        verticalLineToRelative(-2.0F)
        moveToRelative(3.5F, 0.0F)
        horizontalLineToRelative(2.0F)
        verticalLineToRelative(2.0F)
        horizontalLineToRelative(-2.0F)
        verticalLineToRelative(-2.0F)
        moveToRelative(3.5F, 0.0F)
        horizontalLineToRelative(2.0F)
        verticalLineToRelative(2.0F)
        horizontalLineToRelative(-2.0F)
        close()
    }
    materialPath {
        moveToRelative(11.9915F, 22.4F)
        curveToRelative(3.58F, 0.0F, 6.5F, -2.92F, 6.5F, -6.5F)
        verticalLineToRelative(-2.5F)
        horizontalLineToRelative(2.0F)
        lineToRelative(-3.0F, -2.0F)
        lineToRelative(-3.0F, 2.0F)
        horizontalLineToRelative(2.0F)
        verticalLineToRelative(2.5F)
        curveToRelative(0.0F, 2.5F, -2.0F, 4.5F, -4.5F, 4.5F)
        curveToRelative(-2.5F, 0.0F, -4.5F, -2.0F, -4.5F, -4.5F)
        verticalLineToRelative(-4.5F)
        horizontalLineToRelative(-2.0F)
        verticalLineToRelative(4.5F)
        curveToRelative(0.0F, 3.58F, 2.92F, 6.5F, 6.5F, 6.5F)
        close()
    }
}