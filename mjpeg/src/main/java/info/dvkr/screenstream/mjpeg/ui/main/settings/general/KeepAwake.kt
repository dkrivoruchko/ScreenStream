package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun KeepAwakeRow(
    keepAwake: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = keepAwake,
        iconRes = R.drawable.bedtime_off_24px,
        title = stringResource(R.string.mjpeg_pref_keep_awake),
        summary = stringResource(R.string.mjpeg_pref_keep_awake_summary),
        onValueChange = onValueChange
    )
}
