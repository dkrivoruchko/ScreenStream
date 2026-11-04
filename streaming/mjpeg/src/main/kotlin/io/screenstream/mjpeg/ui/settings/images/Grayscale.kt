package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.screenstream.capture.ColorMode
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem

internal object GrayscaleSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_grayscale,
        summaryRes = R.string.mjpeg_pref_image_grayscale_summary,
    )
}

@Composable
internal fun GrayscaleSettingItem(colorMode: ColorMode, onImageChange: (ImageSettings.() -> ImageSettings) -> Unit) {
    SettingsItem(
        title = stringResource(GrayscaleSearch.info.titleRes),
        summary = stringResource(GrayscaleSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_filter_b_and_w,
        onClick = { onImageChange { copy(colorMode = if (this.colorMode == ColorMode.Grayscale) ColorMode.Color else ColorMode.Grayscale) } },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (colorMode == ColorMode.Grayscale) ToggleableState.On else ToggleableState.Off
        },
        trailingContent = {
            SettingSwitch(
                checked = colorMode == ColorMode.Grayscale,
                compact = true,
            )
        },
    )
}
