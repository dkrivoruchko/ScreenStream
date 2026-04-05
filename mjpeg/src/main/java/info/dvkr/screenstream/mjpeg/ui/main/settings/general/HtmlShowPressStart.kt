package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun HtmlShowPressStartRow(
    htmlShowPressStart: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = htmlShowPressStart,
        iconRes = R.drawable.slideshow_24px,
        title = stringResource(R.string.mjpeg_pref_html_show_press_start),
        summary = stringResource(R.string.mjpeg_pref_html_show_press_start_summary),
        onValueChange = onValueChange
    )
}
