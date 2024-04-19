package info.dvkr.screenstream.mjpeg.ui

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
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.MjpegStreamingModule
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.settings.AdvancedGroup
import info.dvkr.screenstream.mjpeg.ui.settings.GeneralGroup
import info.dvkr.screenstream.mjpeg.ui.settings.ImageGroup
import info.dvkr.screenstream.mjpeg.ui.settings.SecurityGroup

internal object MjpegModuleSettings : ModuleSettings {
    override val id: String = MjpegStreamingModule.Id.value
    override val groups: List<ModuleSettings.Group> =
        listOf(GeneralGroup, ImageGroup, SecurityGroup, AdvancedGroup).sortedBy { it.position }

    @Composable
    override fun TitleUI(horizontalPadding: Dp, modifier: Modifier) {
        Text(
            text = stringResource(id = R.string.mjpeg_pref_header),
            modifier = modifier
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = horizontalPadding + 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium
        )
    }
}