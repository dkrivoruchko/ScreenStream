package io.screenstream.mjpeg.ui.settings.automation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.ui.settings.SettingDropdownMenu
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem

internal object ImageAfterStopSearch {
    val labelResIds: Map<StreamBehaviorSettings.PostStopImage, Int> = mapOf(
        StreamBehaviorSettings.PostStopImage.None to R.string.mjpeg_pref_stream_post_stop_none,
        StreamBehaviorSettings.PostStopImage.Placeholder to R.string.mjpeg_pref_stream_post_stop_placeholder,
        StreamBehaviorSettings.PostStopImage.LastAvailableFrame to R.string.mjpeg_pref_stream_post_stop_last_frame,
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Automation,
        titleRes = R.string.mjpeg_pref_stream_post_stop_image,
        summaryRes = R.string.mjpeg_pref_stream_post_stop_image_summary,
        extraTermResIds = labelResIds.values.toList(),
    )
}

@Composable
internal fun ImageAfterStopSettingItem(
    postStopImage: StreamBehaviorSettings.PostStopImage,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
) {
    var menuExpanded by retain { mutableStateOf(false) }
    val openerFocusRequester = remember { FocusRequester() }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(menuExpanded) {
        if (!menuExpanded && shouldRestoreFocus) {
            openerFocusRequester.requestFocus()
            shouldRestoreFocus = false
        }
    }
    SettingsItem(
        title = stringResource(ImageAfterStopSearch.info.titleRes),
        summary = stringResource(ImageAfterStopSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_pause_presentation,
        onClick = { menuExpanded = true },
        modifier = Modifier.focusRequester(openerFocusRequester),
        trailingContent = {
            Box {
                Text(
                    stringResource(ImageAfterStopSearch.labelResIds.getValue(postStopImage)),
                    Modifier.widthIn(max = 128.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingDropdownMenu(
                    expanded = menuExpanded,
                    selectedValue = postStopImage,
                    options = StreamBehaviorSettings.PostStopImage.entries,
                    optionLabel = { option ->
                        val label = stringResource(ImageAfterStopSearch.labelResIds.getValue(option))
                        if (option == StreamBehaviorSettings.PostStopImage.Placeholder) stringResource(R.string.mjpeg_pref_default_option, label) else label
                    },
                    onValueSelected = { selected -> if (selected != postStopImage) onBehaviorChange { copy(postStopImage = selected) } },
                    onDismissRequest = {
                        menuExpanded = false
                        shouldRestoreFocus = true
                    },
                )
            }
        },
    )
}
