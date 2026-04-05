package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow

@Composable
internal fun RotationRow(
    rotation: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.rotate_90_degrees_cw_24px,
        title = stringResource(R.string.mjpeg_pref_rotate),
        summary = stringResource(R.string.mjpeg_pref_rotate_summary),
        valueText = stringResource(R.string.mjpeg_pref_rotate_value, rotation),
        onClick = onDetailShow
    )
}

@Composable
internal fun RotationEditor(
    rotation: Int,
    onValueChange: (Int) -> Unit
) {
    val options = stringArrayResource(R.array.mjpeg_pref_rotate_options).toList()
    val selectedIndex = rotationOptions.indexOf(rotation).takeIf { it >= 0 } ?: 0
    SelectionEditor(
        options = options,
        selectedIndex = selectedIndex,
        onValueChange = { index -> onValueChange(rotationOptions[index]) }
    )
}

private val rotationOptions = listOf(
    MjpegSettings.Values.ROTATION_0,
    MjpegSettings.Values.ROTATION_90,
    MjpegSettings.Values.ROTATION_180,
    MjpegSettings.Values.ROTATION_270
)
