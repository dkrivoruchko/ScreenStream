package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun EnableIPv4Row(
    enabled: Boolean,
    enableIPv4: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = enableIPv4,
        iconRes = R.drawable.ip_v4,
        title = stringResource(id = R.string.rtsp_pref_enable_ipv4),
        summary = stringResource(id = R.string.rtsp_pref_enable_ipv4_summary),
        onValueChange = onValueChange
    )
}
