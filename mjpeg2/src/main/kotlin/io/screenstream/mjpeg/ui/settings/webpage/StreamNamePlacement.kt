package io.screenstream.mjpeg.ui.settings.webpage

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object StreamNamePlacementSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.WebPage,
        titleRes = R.string.mjpeg_pref_web_stream_name_placement,
        summaryRes = R.string.mjpeg_pref_web_stream_name_placement_summary,
        extraTermResIds = WebPagePlacementSearch.termResIds,
    )
}

@Composable
internal fun StreamNamePlacementSettingItem(
    layout: WebPageSettings.BlockLayout,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(StreamNamePlacementSearch.info.titleRes),
        summary = stringResource(StreamNamePlacementSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_subtitles,
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
            title = stringResource(StreamNamePlacementSearch.info.titleRes),
            description = stringResource(StreamNamePlacementSearch.info.summaryRes),
            layout = layout,
            defaultLayout = WebPageSettings.BlockLayout(
                position = WebPageSettings.Edge.Top,
                presentation = WebPageSettings.Presentation.Overlay,
                autoHide = true,
            ),
            onLayoutChange = { transform -> onWebPageChange { copy(titleLayout = titleLayout.transform()) } },
            onDismiss = {
                showEditor = false
                openerFocus.markDismissed()
            },
        )
    }
}

internal object WebPagePlacementSearch {
    val positionLabelRes: Int = R.string.mjpeg_pref_web_position
    val presentationLabelRes: Int = R.string.mjpeg_pref_web_presentation
    val autoHideLabelRes: Int = R.string.mjpeg_pref_web_auto_hide_on
    val alwaysVisibleLabelRes: Int = R.string.mjpeg_pref_web_auto_hide_off
    val autoHideHelpRes: Int = R.string.mjpeg_pref_web_auto_hide_help
    val positionLabelResIds: Map<WebPageSettings.Edge, Int> = mapOf(
        WebPageSettings.Edge.Top to R.string.mjpeg_pref_web_position_top,
        WebPageSettings.Edge.Bottom to R.string.mjpeg_pref_web_position_bottom,
    )
    val presentationLabelResIds: Map<WebPageSettings.Presentation, Int> = mapOf(
        WebPageSettings.Presentation.Overlay to R.string.mjpeg_pref_web_presentation_overlay,
        WebPageSettings.Presentation.Bar to R.string.mjpeg_pref_web_presentation_bar,
    )
    val termResIds: List<Int> = listOf(positionLabelRes, presentationLabelRes, autoHideLabelRes, autoHideHelpRes) +
            positionLabelResIds.values + presentationLabelResIds.values + alwaysVisibleLabelRes
}

@Composable
internal fun webPagePlacementSummary(layout: WebPageSettings.BlockLayout): String = stringResource(
    R.string.mjpeg_pref_web_placement_value,
    stringResource(WebPagePlacementSearch.positionLabelResIds.getValue(layout.position)),
    stringResource(WebPagePlacementSearch.presentationLabelResIds.getValue(layout.presentation)),
    stringResource(
        if (layout.presentation == WebPageSettings.Presentation.Overlay && layout.autoHide) {
            WebPagePlacementSearch.autoHideLabelRes
        } else {
            WebPagePlacementSearch.alwaysVisibleLabelRes
        },
    ),
)

@Composable
internal fun WebPagePlacementEditor(
    title: String,
    description: String,
    layout: WebPageSettings.BlockLayout,
    defaultLayout: WebPageSettings.BlockLayout,
    onLayoutChange: (WebPageSettings.BlockLayout.() -> WebPageSettings.BlockLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialLayout = retain { layout }
    var draftLayout by retain { mutableStateOf(initialLayout) }

    fun closeEditor() {
        val nextLayout = draftLayout
        if (nextLayout != initialLayout) {
            onLayoutChange {
                copy(
                    position = if (nextLayout.position != initialLayout.position) nextLayout.position else position,
                    presentation = if (nextLayout.presentation != initialLayout.presentation) nextLayout.presentation else presentation,
                    autoHide = if (nextLayout.autoHide != initialLayout.autoHide) nextLayout.autoHide else autoHide,
                )
            }
        }
        onDismiss()
    }

    SettingEditor(title, description, onDismiss = ::closeEditor) {
        Text(
            stringResource(R.string.mjpeg_pref_default_value, webPagePlacementSummary(defaultLayout)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(WebPagePlacementSearch.positionLabelRes), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WebPagePlacementSearch.positionLabelResIds.entries.forEachIndexed { index, (position, labelRes) ->
                SegmentedButton(
                    selected = draftLayout.position == position,
                    onClick = { draftLayout = draftLayout.copy(position = position) },
                    shape = SegmentedButtonDefaults.itemShape(index, WebPagePlacementSearch.positionLabelResIds.size),
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Text(stringResource(WebPagePlacementSearch.presentationLabelRes), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WebPagePlacementSearch.presentationLabelResIds.entries.forEachIndexed { index, (presentation, labelRes) ->
                SegmentedButton(
                    selected = draftLayout.presentation == presentation,
                    onClick = { draftLayout = draftLayout.copy(presentation = presentation) },
                    shape = SegmentedButtonDefaults.itemShape(index, WebPagePlacementSearch.presentationLabelResIds.size),
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        if (draftLayout.presentation == WebPageSettings.Presentation.Overlay) {
            ListItem(
                verticalAlignment = Alignment.CenterVertically,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
                onClick = { draftLayout = draftLayout.copy(autoHide = !draftLayout.autoHide) },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = if (draftLayout.autoHide) ToggleableState.On else ToggleableState.Off
                },
                supportingContent = { Text(stringResource(WebPagePlacementSearch.autoHideHelpRes), style = MaterialTheme.typography.bodySmall) },
                trailingContent = { SettingSwitch(checked = draftLayout.autoHide) },
                content = { Text(stringResource(WebPagePlacementSearch.autoHideLabelRes), style = MaterialTheme.typography.titleMedium) },
            )
        }
    }
}
