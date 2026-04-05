package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun AutoChangePinRow(
    autoChangePin: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enablePin,
        checked = autoChangePin,
        iconRes = R.drawable.autorenew_24px,
        title = stringResource(R.string.mjpeg_pref_auto_change_pin),
        summary = stringResource(R.string.mjpeg_pref_auto_change_pin_summary),
        onValueChange = onValueChange
    )
}
