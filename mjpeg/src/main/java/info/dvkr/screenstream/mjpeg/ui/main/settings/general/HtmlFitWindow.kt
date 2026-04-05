package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun HtmlFitWindowRow(
    htmlFitWindow: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = htmlFitWindow,
        iconRes = R.drawable.fit_screen_24px,
        title = stringResource(R.string.mjpeg_pref_html_fit_window),
        summary = stringResource(R.string.mjpeg_pref_html_fit_window_summary),
        onValueChange = onValueChange
    )
}
