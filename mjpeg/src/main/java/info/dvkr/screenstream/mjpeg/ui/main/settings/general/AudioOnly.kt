package info.dvkr.screenstream.mjpeg.ui.main.settings.general

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun AudioOnlyRow(
    streamAudioOnly: Boolean,
    enabled: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = streamAudioOnly,
        iconRes = R.drawable.mobile_speaker_24px,
        title = stringResource(R.string.mjpeg_pref_audio_only),
        summary = stringResource(R.string.mjpeg_pref_audio_only_summary),
        onValueChange = onValueChange,
        modifier = modifier
    )
}
