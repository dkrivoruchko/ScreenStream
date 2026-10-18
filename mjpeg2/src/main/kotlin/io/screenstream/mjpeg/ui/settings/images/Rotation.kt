package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.Box
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
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingDropdownMenu
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import io.screenstream.capture.Rotation as CaptureRotation

internal object RotationSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_rotation,
        summaryRes = R.string.mjpeg_pref_image_rotation_summary,
    )
}

@Composable
internal fun RotationSettingItem(
    rotation: CaptureRotation,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    var menuExpanded by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = menuExpanded)
    SettingsItem(
        title = stringResource(RotationSearch.info.titleRes),
        summary = stringResource(RotationSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_rotate_90_degrees_cw,
        onClick = { menuExpanded = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Box {
                Text(
                    stringResource(R.string.mjpeg_pref_image_rotation_option, rotation.degrees),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SettingDropdownMenu(
                    expanded = menuExpanded,
                    selectedValue = rotation,
                    options = CaptureRotation.entries,
                    optionLabel = { option ->
                        val label = stringResource(R.string.mjpeg_pref_image_rotation_option, option.degrees)
                        if (option == CaptureRotation.Degrees0) stringResource(R.string.mjpeg_pref_default_option, label) else label
                    },
                    onValueSelected = { selected -> if (selected != rotation) onImageChange { copy(rotation = selected) } },
                    onDismissRequest = {
                        menuExpanded = false
                        openerFocus.markDismissed()
                    },
                )
            }
        },
    )
}
