package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun AudioOnlyRow(
    enabled: Boolean,
    streamAudioOnly: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = streamAudioOnly,
        iconRes = R.drawable.mobile_speaker_24px,
        title = stringResource(id = R.string.rtsp_pref_audio_only),
        summary = stringResource(id = R.string.rtsp_pref_audio_only_summary),
        onValueChange = onValueChange
    )
}
