package io.screenstream.mjpeg.ui.settings.webpage

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object ControlsPlacementSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.WebPage,
        titleRes = R.string.mjpeg_pref_web_controls_placement,
        summaryRes = R.string.mjpeg_pref_web_controls_placement_summary,
        extraTermResIds = WebPagePlacementSearch.termResIds,
    )
}

@Composable
internal fun ControlsPlacementSettingItem(
    layout: WebPageSettings.BlockLayout,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(ControlsPlacementSearch.info.titleRes),
        summary = stringResource(ControlsPlacementSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_web_asset,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        valueContent = {
            Text(
                webPagePlacementSummary(layout),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = { Icon(painterResource(R.drawable.mjpeg_icon_chevron), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    )
    if (showEditor) {
        WebPagePlacementEditor(
            title = stringResource(ControlsPlacementSearch.info.titleRes),
            description = stringResource(ControlsPlacementSearch.info.summaryRes),
            layout = layout,
            defaultLayout = WebPageSettings.DEFAULT_CONTROLS_LAYOUT,
            onLayoutChange = { transform -> onWebPageChange { copy(controlsLayout = controlsLayout.transform()) } },
            onDismiss = {
                showEditor = false
                openerFocus.markDismissed()
            },
        )
    }
}
