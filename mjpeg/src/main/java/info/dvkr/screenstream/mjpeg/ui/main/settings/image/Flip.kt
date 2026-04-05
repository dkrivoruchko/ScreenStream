package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueSwitchRow

@Composable
internal fun FlipRow(
    flipMode: Int,
    onDetailShow: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    SettingValueSwitchRow(
        enabled = true,
        checked = flipMode > MjpegSettings.Values.FLIP_NONE,
        iconRes = R.drawable.flip_24px,
        title = stringResource(R.string.mjpeg_pref_flip),
        summary = stringResource(R.string.mjpeg_pref_flip_summary),
        onRowClick = onDetailShow,
        onCheckedChange = { enabled ->
            if (enabled && flipMode == MjpegSettings.Values.FLIP_NONE) onDetailShow()
            else if (!enabled) onValueChange(MjpegSettings.Values.FLIP_NONE)
        }
    )
}

@Composable
internal fun FlipEditor(
    flipMode: Int,
    onValueChange: (Int) -> Unit
) {
    val options = stringArrayResource(R.array.mjpeg_pref_flip_options).toList()
    val selectedIndex = if (flipMode > 0) flipMode else 0
    SelectionEditor(
        options = options,
        selectedIndex = selectedIndex,
        onValueChange = onValueChange
    )
}
