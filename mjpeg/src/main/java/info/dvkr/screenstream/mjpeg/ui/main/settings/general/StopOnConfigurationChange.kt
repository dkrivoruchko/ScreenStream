package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun StopOnConfigurationChangeRow(
    stopOnConfigurationChange: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = true,
        checked = stopOnConfigurationChange,
        iconRes = R.drawable.video_settings_24px,
        title = stringResource(R.string.mjpeg_pref_stop_on_configuration),
        summary = stringResource(R.string.mjpeg_pref_stop_on_configuration_summary),
        onValueChange = onValueChange
    )
}
