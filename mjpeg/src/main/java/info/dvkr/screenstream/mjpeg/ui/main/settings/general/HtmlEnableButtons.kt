package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun HtmlEnableButtonsRow(
    htmlEnableButtons: Boolean,
    enablePin: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = !enablePin,
        checked = htmlEnableButtons,
        iconRes = R.drawable.padding_24px,
        title = stringResource(R.string.mjpeg_pref_html_buttons),
        summary = stringResource(R.string.mjpeg_pref_html_buttons_summary),
        onValueChange = onValueChange
    )
}
