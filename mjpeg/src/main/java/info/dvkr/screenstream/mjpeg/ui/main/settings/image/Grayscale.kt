package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun GrayscaleRow(
    imageGrayscale: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = imageGrayscale,
        iconRes = R.drawable.filter_b_and_w_24px,
        title = stringResource(R.string.mjpeg_pref_grayscale),
        summary = stringResource(R.string.mjpeg_pref_grayscale_summary),
        onValueChange = onValueChange
    )
}
