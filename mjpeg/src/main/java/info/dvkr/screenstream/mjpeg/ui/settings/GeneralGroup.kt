package info.dvkr.screenstream.mjpeg.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.settings.general.HtmlBackColor
import info.dvkr.screenstream.mjpeg.ui.settings.general.HtmlEnableButtons
import info.dvkr.screenstream.mjpeg.ui.settings.general.HtmlShowPressStart
import info.dvkr.screenstream.mjpeg.ui.settings.general.KeepAwake
import info.dvkr.screenstream.mjpeg.ui.settings.general.NotifySlowConnections
import info.dvkr.screenstream.mjpeg.ui.settings.general.StopOnSleep

public object GeneralGroup : ModuleSettings.Group {
    override val id: String = "GENERAL"
    override val position: Int = 0
    override val items: List<ModuleSettings.Item> =
        listOf(KeepAwake, StopOnSleep, NotifySlowConnections, HtmlEnableButtons, HtmlShowPressStart, HtmlBackColor)
            .filter { it.available }.sortedBy { it.position }

    @Composable
    override fun TitleUI(horizontalPadding: Dp, modifier: Modifier) {
        Text(
            text = stringResource(id = R.string.mjpeg_pref_settings_general),
            modifier = modifier
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = horizontalPadding + 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium
        )
    }
}