package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun HtmlKeepImageOnReconnectRow(
    htmlKeepImageOnReconnect: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = htmlKeepImageOnReconnect,
        iconRes = R.drawable.wallpaper_24px,
        title = stringResource(R.string.mjpeg_pref_html_keep_image_on_reconnect),
        summary = stringResource(R.string.mjpeg_pref_html_keep_image_on_reconnect_summary),
        onValueChange = onValueChange
    )
}
