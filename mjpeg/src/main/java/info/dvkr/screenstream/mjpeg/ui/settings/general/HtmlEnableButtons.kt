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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.PathFillType
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

        HtmlEnableButtonsUI(horizontalPadding, mjpegSettingsState.value.htmlEnableButtons, mjpegSettingsState.value.enablePin) {
            if (mjpegSettingsState.value.htmlEnableButtons != it) {
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
        Icon(imageVector = Icon_Buttons, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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

private var Icon_Buttons: ImageVector = materialIcon(name = "Buttons") {
    materialPath(pathFillType = PathFillType.EvenOdd) {
        moveTo(3.933f, 8.333f)
        horizontalLineToRelative(16.134f)
        curveToRelative(0.81f, 0f, 1.466f, 0.657f, 1.466f, 1.467f)
        verticalLineToRelative(4.4f)
        curveToRelative(0f, 0.81f, -0.656f, 1.467f, -1.466f, 1.467f)
        horizontalLineTo(3.933f)
        curveToRelative(-0.81f, 0f, -1.466f, -0.657f, -1.466f, -1.467f)
        verticalLineTo(9.8f)
        curveToRelative(0f, -0.81f, 0.656f, -1.467f, 1.466f, -1.467f)
        close()
        moveTo(1f, 9.8f)
        arcToRelative(2.933f, 2.933f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.933f, -2.933f)
        horizontalLineToRelative(16.134f)
        arcTo(2.933f, 2.933f, 0f, isMoreThanHalf = false, isPositiveArc = true, 23f, 9.8f)
        verticalLineToRelative(4.4f)
        arcToRelative(2.933f, 2.933f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.933f, 2.933f)
        horizontalLineTo(3.933f)
        arcTo(2.933f, 2.933f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1f, 14.2f)
        close()
        moveToRelative(6.6f, 1.1f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, 2.2f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, -2.2f)
        close()
        moveToRelative(3.3f, 1.1f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.2f, 0f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.2f, 0f)
        close()
        moveToRelative(5.5f, -1.1f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = false, 0f, 2.2f)
        arcToRelative(1.1f, 1.1f, 0f, isMoreThanHalf = false, isPositiveArc = false, 0f, -2.2f)
        close()
    }
}