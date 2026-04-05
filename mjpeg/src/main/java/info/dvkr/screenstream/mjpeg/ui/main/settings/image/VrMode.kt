package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueSwitchRow

@Composable
internal fun VrModeRow(
    vrMode: Int,
    onDetailShow: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    SettingValueSwitchRow(
        enabled = true,
        checked = vrMode > MjpegSettings.Default.VR_MODE_DISABLE,
        iconRes = R.drawable.virtual_reality,
        title = stringResource(R.string.mjpeg_pref_vr_mode),
        summary = stringResource(R.string.mjpeg_pref_vr_mode_summary),
        onRowClick = onDetailShow,
        onCheckedChange = { enabled ->
            if (enabled && vrMode == MjpegSettings.Default.VR_MODE_DISABLE) onDetailShow()
            else if (!enabled) onValueChange(MjpegSettings.Default.VR_MODE_DISABLE)
        }
    )
}

@Composable
internal fun VrModeEditor(
    vrMode: Int,
    onValueChange: (Int) -> Unit
) {
    val options = stringArrayResource(R.array.mjpeg_pref_vr_mode_options).toList()
    SelectionEditor(
        options = options,
        selectedIndex = if (vrMode > 0) vrMode else 0,
        onValueChange = onValueChange
    )
}
