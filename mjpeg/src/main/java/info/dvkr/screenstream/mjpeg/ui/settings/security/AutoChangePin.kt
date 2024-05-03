package info.dvkr.screenstream.mjpeg.ui.settings.security

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
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

internal object AutoChangePin : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.AUTO_CHANGE_PIN.name
    override val position: Int = 3
    override val available: Boolean = true
    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_auto_change_pin).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_auto_change_pin_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        AutoChangePinUI(horizontalPadding, coroutineScope)
}

@Composable
private fun AutoChangePinUI(
    horizontalPadding: Dp,
    scope: CoroutineScope,
    mjpegSettings: MjpegSettings = koinInject()
) {
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val autoChangePin = remember { derivedStateOf { mjpegSettingsState.value.autoChangePin } }
    val enablePin = remember { derivedStateOf { mjpegSettingsState.value.enablePin } }

    Row(
        modifier = Modifier
            .toggleable(
                value = autoChangePin.value,
                enabled = enablePin.value,
                onValueChange = { scope.launch { mjpegSettings.updateData { copy(autoChangePin = it) } } }
            )
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .conditional(enablePin.value.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Autorenew,
            contentDescription = stringResource(id = R.string.mjpeg_pref_auto_change_pin),
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_auto_change_pin),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_auto_change_pin_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(
            checked = autoChangePin.value,
            onCheckedChange = null,
            modifier = Modifier.scale(0.7F),
        )
    }
}