package info.dvkr.screenstream.mjpeg.ui.main.settings.advanced

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun EnableIPv4Row(
    enableIPv4: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = enableIPv4,
        iconRes = R.drawable.ip_v4,
        title = stringResource(R.string.mjpeg_pref_enable_ipv4),
        summary = stringResource(R.string.mjpeg_pref_enable_ipv4_summary),
        onValueChange = onValueChange
    )
}
