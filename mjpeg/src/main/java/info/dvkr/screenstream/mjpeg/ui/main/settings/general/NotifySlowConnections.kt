package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun NotifySlowConnectionsRow(
    notifySlowConnections: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = notifySlowConnections,
        iconRes = R.drawable.wifi_notification_24px,
        title = stringResource(R.string.mjpeg_pref_detect_slow_connections),
        summary = stringResource(R.string.mjpeg_pref_detect_slow_connections_summary),
        onValueChange = onValueChange
    )
}
