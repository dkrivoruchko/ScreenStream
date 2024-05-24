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

internal object NewPinOnAppStart : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.NEW_PIN_ON_APP_START.name
    override val position: Int = 2
    override val available: Boolean = true
    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_new_pin_on_start).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_new_pin_on_start_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val newPinOnAppStart = remember { derivedStateOf { mjpegSettingsState.value.newPinOnAppStart } }
        val enablePin = remember { derivedStateOf { mjpegSettingsState.value.enablePin } }

        NewPinOnAppStartUI(horizontalPadding, newPinOnAppStart.value, enablePin.value) {
            if (newPinOnAppStart.value != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(newPinOnAppStart = it) } }
            }
        }
    }
}

@Composable
private fun NewPinOnAppStartUI(
    horizontalPadding: Dp,
    newPinOnAppStart: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = newPinOnAppStart, enabled = enablePin, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enablePin.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Key, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_new_pin_on_start),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_new_pin_on_start_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = newPinOnAppStart, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}

private val Icon_Key: ImageVector = materialIcon(name = "Outlined.Key") {
    materialPath {
        moveTo(21.0f, 10.0f)
        horizontalLineToRelative(-8.35f)
        curveTo(11.83f, 7.67f, 9.61f, 6.0f, 7.0f, 6.0f)
        curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
        reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
        curveToRelative(2.61f, 0.0f, 4.83f, -1.67f, 5.65f, -4.0f)
        horizontalLineTo(13.0f)
        lineToRelative(2.0f, 2.0f)
        lineToRelative(2.0f, -2.0f)
        lineToRelative(2.0f, 2.0f)
        lineToRelative(4.0f, -4.04f)
        lineTo(21.0f, 10.0f)
        close()
        moveTo(7.0f, 15.0f)
        curveToRelative(-1.65f, 0.0f, -3.0f, -1.35f, -3.0f, -3.0f)
        curveToRelative(0.0f, -1.65f, 1.35f, -3.0f, 3.0f, -3.0f)
        reflectiveCurveToRelative(3.0f, 1.35f, 3.0f, 3.0f)
        curveTo(10.0f, 13.65f, 8.65f, 15.0f, 7.0f, 15.0f)
        close()
    }
}