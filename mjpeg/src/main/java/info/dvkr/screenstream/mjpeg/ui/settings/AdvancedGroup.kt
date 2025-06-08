package info.dvkr.screenstream.mjpeg.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.settings.advanced.AddressFilter
import info.dvkr.screenstream.mjpeg.ui.settings.advanced.EnableIPv4
import info.dvkr.screenstream.mjpeg.ui.settings.advanced.EnableIPv6
import info.dvkr.screenstream.mjpeg.ui.settings.advanced.InterfaceFilter
import info.dvkr.screenstream.mjpeg.ui.settings.advanced.ServerPort

public data object AdvancedGroup : ModuleSettings.Group {
    override val id: String = "ADVANCED"
    override val position: Int = 4
    override val items: List<ModuleSettings.Item> =
        listOf(InterfaceFilter, AddressFilter, EnableIPv4, EnableIPv6, ServerPort)
            .filter { it.available }.sortedBy { it.position }

    @Composable
    override fun TitleUI(modifier: Modifier) {
        Text(
            text = stringResource(id = R.string.mjpeg_pref_settings_advanced),
            modifier = modifier,
            style = MaterialTheme.typography.titleMedium
        )
    }
}