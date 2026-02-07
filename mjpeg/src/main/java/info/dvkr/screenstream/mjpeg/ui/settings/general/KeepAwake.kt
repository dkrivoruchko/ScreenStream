package info.dvkr.screenstream.mjpeg.ui.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
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

internal object KeepAwake : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.KEEP_AWAKE.name
    override val position: Int = 0
    override val available: Boolean = Build.MANUFACTURER !in listOf("OnePlus", "OPPO")

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_keep_awake).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_keep_awake_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        KeepAwakeUI(horizontalPadding, mjpegSettingsState.value.keepAwake) {
            if (mjpegSettingsState.value.keepAwake != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(keepAwake = it) } }
            }
        }
    }
}

@Composable
private fun KeepAwakeUI(
    horizontalPadding: Dp,
    keepAwake: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = keepAwake, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.bedtime_off_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_keep_awake),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_keep_awake_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = keepAwake, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}
