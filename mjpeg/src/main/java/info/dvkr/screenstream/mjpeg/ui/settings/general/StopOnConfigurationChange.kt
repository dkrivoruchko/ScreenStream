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

internal object StopOnConfigurationChange : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.STOP_ON_CONFIGURATION_CHANGE.name
    override val position: Int = 2
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_stop_on_configuration).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_stop_on_configuration_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val stopOnConfigurationChange = remember { derivedStateOf { mjpegSettingsState.value.stopOnConfigurationChange } }

        StopOnConfigurationChangeUI(horizontalPadding, stopOnConfigurationChange.value) {
            if (stopOnConfigurationChange.value != it) {
                coroutineScope.launch {
                    mjpegSettings.updateData { copy(stopOnConfigurationChange = it) }
                }
            }
        }
    }
}

@Composable
private fun StopOnConfigurationChangeUI(
    horizontalPadding: Dp,
    stopOnConfigurationChange: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = stopOnConfigurationChange, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_VideoSettings, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_stop_on_configuration),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_stop_on_configuration_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = stopOnConfigurationChange, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_VideoSettings: ImageVector = materialIcon(name = "Filled.VideoSettings") {
    materialPath {
        moveTo(3.0f, 6.0f)
        horizontalLineToRelative(18.0f)
        verticalLineToRelative(5.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(6.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        horizontalLineTo(3.0f)
        curveTo(1.9f, 4.0f, 1.0f, 4.9f, 1.0f, 6.0f)
        verticalLineToRelative(12.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(9.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineTo(3.0f)
        verticalLineTo(6.0f)
        close()
    }
    materialPath {
        moveTo(15.0f, 12.0f)
        lineToRelative(-6.0f, -4.0f)
        lineToRelative(0.0f, 8.0f)
        close()
    }
    materialPath {
        moveTo(22.71f, 18.43f)
        curveToRelative(0.03f, -0.29f, 0.04f, -0.58f, 0.01f, -0.86f)
        lineToRelative(1.07f, -0.85f)
        curveToRelative(0.1f, -0.08f, 0.12f, -0.21f, 0.06f, -0.32f)
        lineToRelative(-1.03f, -1.79f)
        curveToRelative(-0.06f, -0.11f, -0.19f, -0.15f, -0.31f, -0.11f)
        lineTo(21.23f, 15.0f)
        curveToRelative(-0.23f, -0.17f, -0.48f, -0.31f, -0.75f, -0.42f)
        lineToRelative(-0.2f, -1.36f)
        curveTo(20.26f, 13.09f, 20.16f, 13.0f, 20.03f, 13.0f)
        horizontalLineToRelative(-2.07f)
        curveToRelative(-0.12f, 0.0f, -0.23f, 0.09f, -0.25f, 0.21f)
        lineToRelative(-0.2f, 1.36f)
        curveToRelative(-0.26f, 0.11f, -0.51f, 0.26f, -0.74f, 0.42f)
        lineToRelative(-1.28f, -0.5f)
        curveToRelative(-0.12f, -0.05f, -0.25f, 0.0f, -0.31f, 0.11f)
        lineToRelative(-1.03f, 1.79f)
        curveToRelative(-0.06f, 0.11f, -0.04f, 0.24f, 0.06f, 0.32f)
        lineToRelative(1.07f, 0.86f)
        curveToRelative(-0.03f, 0.29f, -0.04f, 0.58f, -0.01f, 0.86f)
        lineToRelative(-1.07f, 0.85f)
        curveToRelative(-0.1f, 0.08f, -0.12f, 0.21f, -0.06f, 0.32f)
        lineToRelative(1.03f, 1.79f)
        curveToRelative(0.06f, 0.11f, 0.19f, 0.15f, 0.31f, 0.11f)
        lineToRelative(1.27f, -0.5f)
        curveToRelative(0.23f, 0.17f, 0.48f, 0.31f, 0.75f, 0.42f)
        lineToRelative(0.2f, 1.36f)
        curveToRelative(0.02f, 0.12f, 0.12f, 0.21f, 0.25f, 0.21f)
        horizontalLineToRelative(2.07f)
        curveToRelative(0.12f, 0.0f, 0.23f, -0.09f, 0.25f, -0.21f)
        lineToRelative(0.2f, -1.36f)
        curveToRelative(0.26f, -0.11f, 0.51f, -0.26f, 0.74f, -0.42f)
        lineToRelative(1.28f, 0.5f)
        curveToRelative(0.12f, 0.05f, 0.25f, 0.0f, 0.31f, -0.11f)
        lineToRelative(1.03f, -1.79f)
        curveToRelative(0.06f, -0.11f, 0.04f, -0.24f, -0.06f, -0.32f)
        lineTo(22.71f, 18.43f)
        close()
        moveTo(19.0f, 19.5f)
        curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
        reflectiveCurveToRelative(0.67f, -1.5f, 1.5f, -1.5f)
        reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
        reflectiveCurveTo(19.83f, 19.5f, 19.0f, 19.5f)
        close()
    }
}