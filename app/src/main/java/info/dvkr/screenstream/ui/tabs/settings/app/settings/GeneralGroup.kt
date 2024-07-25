package info.dvkr.screenstream.ui.tabs.settings.app.settings

import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.AppLocale
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.DynamicTheme
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.Logging
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.NightMode
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.Notifications
import info.dvkr.screenstream.ui.tabs.settings.app.settings.general.Tile

public data object GeneralGroup : ModuleSettings.Group {
    override val id: String = "GENERAL"
    override val position: Int = -1
    override val items: List<ModuleSettings.Item> =
        listOf(AppLocale, NightMode, DynamicTheme, Notifications, Tile, Logging)
            .filter { it.available }.sortedBy { it.position }
}