package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingDropdownMenu
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object CaptureAreaSearch {
    val labelResIds: Map<SourceRegion, Int> = mapOf(
        SourceRegion.Full to R.string.mjpeg_pref_image_region_full,
        SourceRegion.LeftHalf to R.string.mjpeg_pref_image_region_left,
        SourceRegion.RightHalf to R.string.mjpeg_pref_image_region_right,
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_region,
        summaryRes = R.string.mjpeg_pref_image_region_summary,
        extraTermResIds = labelResIds.values.toList(),
    )
}

@Composable
internal fun CaptureAreaSettingItem(
    region: SourceRegion,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    var menuExpanded by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = menuExpanded)
    SettingsItem(
        title = stringResource(CaptureAreaSearch.info.titleRes),
        summary = stringResource(CaptureAreaSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_select_all,
        onClick = { menuExpanded = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Box {
                Text(
                    stringResource(CaptureAreaSearch.labelResIds.getValue(region)),
                    Modifier.widthIn(max = 128.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingDropdownMenu(
                    expanded = menuExpanded,
                    selectedValue = region,
                    options = SourceRegion.entries,
                    optionLabel = { option ->
                        val label = stringResource(CaptureAreaSearch.labelResIds.getValue(option))
                        if (option == SourceRegion.Full) stringResource(R.string.mjpeg_pref_default_option, label) else label
                    },
                    onValueSelected = { selected -> if (selected != region) onImageChange { copy(sourceRegion = selected) } },
                    onDismissRequest = {
                        menuExpanded = false
                        openerFocus.markDismissed()
                    },
                )
            }
        },
    )
}
