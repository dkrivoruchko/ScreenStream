package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun EnablePinRow(
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = enablePin,
        iconRes = R.drawable.pin_24px,
        title = stringResource(R.string.mjpeg_pref_enable_pin),
        summary = stringResource(R.string.mjpeg_pref_enable_pin_summary),
        onValueChange = onValueChange
    )
}
