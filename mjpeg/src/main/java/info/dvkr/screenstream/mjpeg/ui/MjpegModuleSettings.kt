package info.dvkr.screenstream.mjpeg.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.MjpegStreamingModule

internal object MjpegModuleSettings : ModuleSettings {
    override val id: String = MjpegStreamingModule.Id.value
    override val groups: List<ModuleSettings.Group> = emptyList()

    @Composable
    override fun TitleUI(modifier: Modifier) {
    }
}
