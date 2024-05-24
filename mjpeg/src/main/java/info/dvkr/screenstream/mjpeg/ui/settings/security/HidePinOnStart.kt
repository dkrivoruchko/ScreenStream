package info.dvkr.screenstream.mjpeg.ui.settings.security

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

internal object HidePinOnStart : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.HIDE_PIN_ON_START.name
    override val position: Int = 1
    override val available: Boolean = true
    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_hide_pin).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_hide_pin_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val hidePinOnStart = remember { derivedStateOf { mjpegSettingsState.value.hidePinOnStart } }
        val enablePin = remember { derivedStateOf { mjpegSettingsState.value.enablePin } }

        HidePinOnStartUI(horizontalPadding, hidePinOnStart.value, enablePin.value) {
            coroutineScope.launch { mjpegSettings.updateData { copy(hidePinOnStart = it) } }
        }
    }
}

@Composable
private fun HidePinOnStartUI(
    horizontalPadding: Dp,
    hidePinOnStart: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = hidePinOnStart, enabled = enablePin, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enablePin.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Password, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_hide_pin),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_hide_pin_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = hidePinOnStart, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Password: ImageVector = materialIcon(name = "Filled.Password") {
    materialPath {
        moveTo(2.0f, 17.0f)
        horizontalLineToRelative(20.0f)
        verticalLineToRelative(2.0f)
        horizontalLineTo(2.0f)
        verticalLineTo(17.0f)
        close()
        moveTo(3.15f, 12.95f)
        lineTo(4.0f, 11.47f)
        lineToRelative(0.85f, 1.48f)
        lineToRelative(1.3f, -0.75f)
        lineTo(5.3f, 10.72f)
        horizontalLineTo(7.0f)
        verticalLineToRelative(-1.5f)
        horizontalLineTo(5.3f)
        lineToRelative(0.85f, -1.47f)
        lineTo(4.85f, 7.0f)
        lineTo(4.0f, 8.47f)
        lineTo(3.15f, 7.0f)
        lineToRelative(-1.3f, 0.75f)
        lineTo(2.7f, 9.22f)
        horizontalLineTo(1.0f)
        verticalLineToRelative(1.5f)
        horizontalLineToRelative(1.7f)
        lineTo(1.85f, 12.2f)
        lineTo(3.15f, 12.95f)
        close()
        moveTo(9.85f, 12.2f)
        lineToRelative(1.3f, 0.75f)
        lineTo(12.0f, 11.47f)
        lineToRelative(0.85f, 1.48f)
        lineToRelative(1.3f, -0.75f)
        lineToRelative(-0.85f, -1.48f)
        horizontalLineTo(15.0f)
        verticalLineToRelative(-1.5f)
        horizontalLineToRelative(-1.7f)
        lineToRelative(0.85f, -1.47f)
        lineTo(12.85f, 7.0f)
        lineTo(12.0f, 8.47f)
        lineTo(11.15f, 7.0f)
        lineToRelative(-1.3f, 0.75f)
        lineToRelative(0.85f, 1.47f)
        horizontalLineTo(9.0f)
        verticalLineToRelative(1.5f)
        horizontalLineToRelative(1.7f)
        lineTo(9.85f, 12.2f)
        close()
        moveTo(23.0f, 9.22f)
        horizontalLineToRelative(-1.7f)
        lineToRelative(0.85f, -1.47f)
        lineTo(20.85f, 7.0f)
        lineTo(20.0f, 8.47f)
        lineTo(19.15f, 7.0f)
        lineToRelative(-1.3f, 0.75f)
        lineToRelative(0.85f, 1.47f)
        horizontalLineTo(17.0f)
        verticalLineToRelative(1.5f)
        horizontalLineToRelative(1.7f)
        lineToRelative(-0.85f, 1.48f)
        lineToRelative(1.3f, 0.75f)
        lineTo(20.0f, 11.47f)
        lineToRelative(0.85f, 1.48f)
        lineToRelative(1.3f, -0.75f)
        lineToRelative(-0.85f, -1.48f)
        horizontalLineTo(23.0f)
        verticalLineTo(9.22f)
        close()
    }
}