package info.dvkr.screenstream.webrtc.ui.main.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun StopOnSleepRow(
    enabled: Boolean,
    stopOnSleep: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = stopOnSleep,
        iconRes = R.drawable.stop_circle_24px,
        title = stringResource(id = R.string.webrtc_stream_stop_on_sleep),
        summary = stringResource(id = R.string.webrtc_stream_stop_on_sleep_summary),
        onValueChange = onValueChange
    )
}
