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

internal object HtmlEnableButtons : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.HTML_ENABLE_BUTTONS.name
    override val position: Int = 4
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_html_buttons).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_html_buttons_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val htmlEnableButtons = remember { derivedStateOf { mjpegSettingsState.value.htmlEnableButtons } }
        val enablePin = remember { derivedStateOf { mjpegSettingsState.value.enablePin } }

        HtmlEnableButtonsUI(horizontalPadding, htmlEnableButtons.value, enablePin.value) {
            if (htmlEnableButtons.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(htmlEnableButtons = it) } }
            }
        }
    }
}

@Composable
private fun HtmlEnableButtonsUI(
    horizontalPadding: Dp,
    htmlEnableButtons: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = htmlEnableButtons, enabled = enablePin.not(), onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enablePin) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_ControlCamera, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_buttons),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_buttons_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = htmlEnableButtons, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_ControlCamera: ImageVector = materialIcon(name = "Filled.ControlCamera") {
    materialPath {
        moveTo(15.54f, 5.54f)
        lineTo(13.77f, 7.3f)
        lineTo(12.0f, 5.54f)
        lineTo(10.23f, 7.3f)
        lineTo(8.46f, 5.54f)
        lineTo(12.0f, 2.0f)
        close()
        moveTo(18.46f, 15.54f)
        lineToRelative(-1.76f, -1.77f)
        lineTo(18.46f, 12.0f)
        lineToRelative(-1.76f, -1.77f)
        lineToRelative(1.76f, -1.77f)
        lineTo(22.0f, 12.0f)
        close()
        moveTo(8.46f, 18.46f)
        lineToRelative(1.77f, -1.76f)
        lineTo(12.0f, 18.46f)
        lineToRelative(1.77f, -1.76f)
        lineToRelative(1.77f, 1.76f)
        lineTo(12.0f, 22.0f)
        close()
        moveTo(5.54f, 8.46f)
        lineToRelative(1.76f, 1.77f)
        lineTo(5.54f, 12.0f)
        lineToRelative(1.76f, 1.77f)
        lineToRelative(-1.76f, 1.77f)
        lineTo(2.0f, 12.0f)
        close()
    }
    materialPath {
        moveTo(12.0f, 12.0f)
        moveToRelative(-3.0f, 0.0f)
        arcToRelative(3.0f, 3.0f, 0.0f, true, true, 6.0f, 0.0f)
        arcToRelative(3.0f, 3.0f, 0.0f, true, true, -6.0f, 0.0f)
    }
}