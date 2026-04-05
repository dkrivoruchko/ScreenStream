package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun EnableIPv6Row(
    enabled: Boolean,
    enableIPv6: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = enableIPv6,
        iconRes = R.drawable.ip_v6,
        title = stringResource(id = R.string.rtsp_pref_enable_ipv6),
        summary = stringResource(id = R.string.rtsp_pref_enable_ipv6_summary),
        onValueChange = onValueChange
    )
}
