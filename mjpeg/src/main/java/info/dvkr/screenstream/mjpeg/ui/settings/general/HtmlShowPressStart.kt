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

internal object HtmlShowPressStart : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.HTML_SHOW_PRESS_START.name
    override val position: Int = 5
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_html_show_press_start).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_html_show_press_start_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val htmlShowPressStart = remember { derivedStateOf { mjpegSettingsState.value.htmlShowPressStart } }

        HtmlShowPressStartUI(horizontalPadding, htmlShowPressStart.value) {
            if (htmlShowPressStart.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(htmlShowPressStart = it) } }
            }
        }
    }
}

@Composable
private fun HtmlShowPressStartUI(
    horizontalPadding: Dp,
    htmlShowPressStart: Boolean,
    onValueChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .toggleable(value = htmlShowPressStart, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_NotStarted, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_show_press_start),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_show_press_start_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = htmlShowPressStart, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_NotStarted: ImageVector = materialIcon(name = "Outlined.NotStarted") {
    materialPath {
        moveTo(12.0f, 4.0f)
        curveToRelative(4.41f, 0.0f, 8.0f, 3.59f, 8.0f, 8.0f)
        reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
        reflectiveCurveToRelative(-8.0f, -3.59f, -8.0f, -8.0f)
        reflectiveCurveTo(7.59f, 4.0f, 12.0f, 4.0f)
        moveTo(12.0f, 2.0f)
        curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
        reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
        curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
        lineTo(12.0f, 2.0f)
        close()
        moveTo(11.0f, 8.0f)
        horizontalLineTo(9.0f)
        verticalLineToRelative(8.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(8.0f)
        close()
        moveTo(17.0f, 12.0f)
        lineToRelative(-5.0f, -4.0f)
        verticalLineToRelative(8.0f)
        lineTo(17.0f, 12.0f)
        close()
    }
}