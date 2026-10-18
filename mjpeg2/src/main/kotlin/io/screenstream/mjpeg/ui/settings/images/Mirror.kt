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
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingDropdownMenu
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import io.screenstream.capture.Mirror as CaptureMirror

internal object MirrorSearch {
    val labelResIds: Map<CaptureMirror, Int> = mapOf(
        CaptureMirror.None to R.string.mjpeg_pref_image_mirror_none,
        CaptureMirror.Horizontal to R.string.mjpeg_pref_image_mirror_horizontal,
        CaptureMirror.Vertical to R.string.mjpeg_pref_image_mirror_vertical,
    )

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_mirror,
        summaryRes = R.string.mjpeg_pref_image_mirror_summary,
        extraTermResIds = labelResIds.values.toList(),
    )
}

@Composable
internal fun MirrorSettingItem(
    mirror: CaptureMirror,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    var menuExpanded by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = menuExpanded)
    SettingsItem(
        title = stringResource(MirrorSearch.info.titleRes),
        summary = stringResource(MirrorSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_flip,
        onClick = { menuExpanded = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Box {
                Text(
                    stringResource(MirrorSearch.labelResIds.getValue(mirror)),
                    Modifier.widthIn(max = 128.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingDropdownMenu(
                    expanded = menuExpanded,
                    selectedValue = mirror,
                    options = CaptureMirror.entries,
                    optionLabel = { option ->
                        val label = stringResource(MirrorSearch.labelResIds.getValue(option))
                        if (option == CaptureMirror.None) stringResource(R.string.mjpeg_pref_default_option, label) else label
                    },
                    onValueSelected = { selected -> if (selected != mirror) onImageChange { copy(mirror = selected) } },
                    onDismissRequest = {
                        menuExpanded = false
                        openerFocus.markDismissed()
                    },
                )
            }
        },
    )
}
