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
import info.dvkr.screenstream.mjpeg.ui.settings.security.AutoChangePin
import info.dvkr.screenstream.mjpeg.ui.settings.security.BlockAddress
import info.dvkr.screenstream.mjpeg.ui.settings.security.EnablePin
import info.dvkr.screenstream.mjpeg.ui.settings.security.HidePinOnStart
import info.dvkr.screenstream.mjpeg.ui.settings.security.NewPinOnAppStart
import info.dvkr.screenstream.mjpeg.ui.settings.security.Pin

public data object SecurityGroup : ModuleSettings.Group {
    override val id: String = "SECURITY"
    override val position: Int = 2
    override val items: List<ModuleSettings.Item> =
        listOf(EnablePin, HidePinOnStart, NewPinOnAppStart, AutoChangePin, Pin, BlockAddress)
            .filter { it.available }.sortedBy { it.position }

    @Composable
    override fun TitleUI(horizontalPadding: Dp, modifier: Modifier) {
        Text(
            text = stringResource(id = R.string.mjpeg_pref_settings_security),
            modifier = modifier
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = horizontalPadding + 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            style = MaterialTheme.typography.titleMedium
        )
    }
}