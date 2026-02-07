package info.dvkr.screenstream.webrtc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.webrtc.WebRtcStreamingModule

internal object WebRtcModuleSettings : ModuleSettings {
    override val id: String = WebRtcStreamingModule.Id.value
    override val groups: List<ModuleSettings.Group> = emptyList()

    @Composable
    override fun TitleUI(modifier: Modifier) {
    }
}