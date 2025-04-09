package info.dvkr.screenstream.rtsp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.rtsp.RtspStreamingModule

internal object RtspModuleSettings : ModuleSettings {
    override val id: String = RtspStreamingModule.Id.value
    override val groups: List<ModuleSettings.Group> = emptyList()

    @Composable
    override fun TitleUI(modifier: Modifier) {
    }
}