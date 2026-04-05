package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun NewPinOnAppStartRow(
    newPinOnAppStart: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enablePin,
        checked = newPinOnAppStart,
        iconRes = R.drawable.key_24px,
        title = stringResource(R.string.mjpeg_pref_new_pin_on_start),
        summary = stringResource(R.string.mjpeg_pref_new_pin_on_start_summary),
        onValueChange = onValueChange
    )
}
