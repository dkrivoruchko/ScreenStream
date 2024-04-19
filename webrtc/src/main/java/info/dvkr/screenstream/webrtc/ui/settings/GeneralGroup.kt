package info.dvkr.screenstream.webrtc.ui.settings

import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.webrtc.ui.settings.general.KeepAwake
import info.dvkr.screenstream.webrtc.ui.settings.general.StopOnSleep

public object GeneralGroup : ModuleSettings.Group {
    override val id: String = "GENERAL"
    override val position: Int = 0
    override val items: List<ModuleSettings.Item> = listOf(KeepAwake, StopOnSleep).filter { it.available }
}