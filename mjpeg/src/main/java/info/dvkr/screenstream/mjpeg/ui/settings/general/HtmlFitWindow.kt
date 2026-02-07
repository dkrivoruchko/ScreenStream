package info.dvkr.screenstream.mjpeg.ui.settings.general

import android.content.res.Resources
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

internal object HtmlFitWindow : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.HTML_FIT_WINDOW.name
    override val position: Int = 7
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_html_fit_window).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_html_fit_window_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        HtmlFitWindowUI(horizontalPadding, mjpegSettingsState.value.htmlFitWindow) {
            if (mjpegSettingsState.value.htmlFitWindow != it) {
                coroutineScope.launch { mjpegSettings.updateData { copy(htmlFitWindow = it) } }
            }
        }
    }
}

@Composable
private fun HtmlFitWindowUI(
    horizontalPadding: Dp,
    htmlFitWindow: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = htmlFitWindow, onValueChange = onValueChange)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.fit_screen_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_fit_window),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_fit_window_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = htmlFitWindow, onCheckedChange = null, modifier = Modifier.scale(0.7F))
    }
}