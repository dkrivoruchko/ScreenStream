package info.dvkr.screenstream.mjpeg.ui.main.settings.advanced

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun EnableIPv6Row(
    enableIPv6: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = enableIPv6,
        iconRes = R.drawable.ip_v6,
        title = stringResource(R.string.mjpeg_pref_enable_ipv6),
        summary = stringResource(R.string.mjpeg_pref_enable_ipv6_summary),
        onValueChange = onValueChange
    )
}
