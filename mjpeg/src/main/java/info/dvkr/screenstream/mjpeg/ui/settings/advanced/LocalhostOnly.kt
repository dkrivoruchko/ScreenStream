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

internal object LocalhostOnly : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.LOCAL_HOST_ONLY.name
    override val position: Int = 3
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_localhost_only).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_localhost_only_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val localHostOnly = remember { derivedStateOf { mjpegSettingsState.value.localHostOnly } }
        val enableLocalHost = remember { derivedStateOf { mjpegSettingsState.value.enableLocalHost } }

        LocalhostOnlyUI(horizontalPadding, localHostOnly.value, enableLocalHost.value) {
            if (localHostOnly.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(localHostOnly = it) } }
            }
        }
    }
}

@Composable
private fun LocalhostOnlyUI(
    horizontalPadding: Dp,
    localHostOnly: Boolean,
    enableLocalHost: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = localHostOnly, enabled = enableLocalHost, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enableLocalHost.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_LocalhostOnly, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_localhost_only),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_localhost_only_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = localHostOnly, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_LocalhostOnly: ImageVector = materialIcon(name = "LocalhostOnly") {
    materialPath {
        moveToRelative(5.0F, 1.9763F)
        curveToRelative(-1.1F, 0.0F, -2.0F, 0.9F, -2.0F, 2.0F)
        verticalLineToRelative(4.0F)
        curveTo(3.0F, 9.0863F, 3.9F, 9.9763F, 5.0F, 9.9763F)
        curveToRelative(4.6855F, 0.0135F, 9.312F, 0.0F, 14.0F, 0.0F)
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