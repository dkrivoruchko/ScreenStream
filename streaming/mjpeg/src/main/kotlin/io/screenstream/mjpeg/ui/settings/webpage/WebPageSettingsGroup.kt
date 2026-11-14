package io.screenstream.mjpeg.ui.settings.webpage

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import io.screenstream.mjpeg.ui.settings.rememberMjpegSettingsSave

internal val webPageSettingSearchEntries: List<SettingSearchInfo> = listOf(
    BackgroundSearch.info,
    StreamNameSearch.info,
    StreamNamePlacementSearch.info,
    ControlsPlacementSearch.info,
)

@Composable
internal fun WebPageSettingsGroup(
    webPageSettings: WebPageSettings,
    mjpegSettings: MjpegSettings,
    onSaveError: (String) -> Unit,
    visibleSettings: Set<SettingSearchInfo>?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val saveErrorText = stringResource(R.string.mjpeg_pref_web_save_error)
    val save = rememberMjpegSettingsSave(mjpegSettings, "WebPage.save", "Could not save web page settings") { onSaveError(saveErrorText) }

    fun updateWebPage(transform: WebPageSettings.() -> WebPageSettings) = save { copy(web = web.transform()) }

    SettingsGroupCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        searching = visibleSettings != null,
        group = SettingSearchGroup.WebPage,
        iconRes = R.drawable.mjpeg_icon_web,
        modifier = Modifier.fillMaxWidth(),
    ) {
        webPageSettingSearchEntries
            .filter { visibleSettings == null || it in visibleSettings }
            .forEachIndexed { index, searchInfo ->
                key(searchInfo) {
                    if (index > 0) HorizontalDivider()
                    when (searchInfo) {
                        BackgroundSearch.info -> BackgroundSettingItem(webPageSettings.background, ::updateWebPage)
                        StreamNameSearch.info -> StreamNameSettingItem(webPageSettings.title, webPageSettings.titleEnabled, ::updateWebPage)
                        StreamNamePlacementSearch.info -> StreamNamePlacementSettingItem(webPageSettings.titleLayout, ::updateWebPage)
                        ControlsPlacementSearch.info -> ControlsPlacementSettingItem(webPageSettings.controlsLayout, ::updateWebPage)
                        else -> Unit
                    }
                }
            }
    }
}
