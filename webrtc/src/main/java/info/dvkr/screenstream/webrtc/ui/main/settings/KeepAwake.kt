package info.dvkr.screenstream.webrtc.ui.main.settings

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.main.settings.common.SettingSwitchRow

internal val keepAwakeAvailable: Boolean = Build.MANUFACTURER !in listOf("OnePlus", "OPPO")

@Composable
internal fun KeepAwakeRow(
    enabled: Boolean,
    keepAwake: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = keepAwake,
        iconRes = R.drawable.bedtime_off_24px,
        title = stringResource(id = R.string.webrtc_stream_keep_awake),
        summary = stringResource(id = R.string.webrtc_stream_keep_awake_summary),
        onValueChange = onValueChange
    )
}
