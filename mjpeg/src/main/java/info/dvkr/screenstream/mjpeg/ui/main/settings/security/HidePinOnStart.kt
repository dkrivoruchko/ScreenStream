package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun HidePinOnStartRow(
    hidePinOnStart: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enablePin,
        checked = hidePinOnStart,
        iconRes = R.drawable.password_24px,
        title = stringResource(R.string.mjpeg_pref_hide_pin),
        summary = stringResource(R.string.mjpeg_pref_hide_pin_summary),
        onValueChange = onValueChange
    )
}
