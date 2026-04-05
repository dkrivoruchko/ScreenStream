package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun StopOnSleepRow(
    stopOnSleep: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = stopOnSleep,
        iconRes = R.drawable.stop_circle_24px,
        title = stringResource(R.string.mjpeg_pref_stop_on_sleep),
        summary = stringResource(R.string.mjpeg_pref_stop_on_sleep_summary),
        onValueChange = onValueChange
    )
}
