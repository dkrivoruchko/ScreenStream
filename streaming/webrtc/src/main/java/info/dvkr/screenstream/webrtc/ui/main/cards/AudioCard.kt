package info.dvkr.screenstream.webrtc.ui.main.cards

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings

@Composable
internal fun AudioCard(
    enabled: Boolean,
    settings: WebRtcSettings.Data,
    onMicEnabledChange: (Boolean) -> Unit,
    onDeviceAudioEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = rememberSaveable { mutableStateOf(false) }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(text = stringResource(id = R.string.webrtc_stream_audio_parameters), style = MaterialTheme.typography.titleMedium)
            }
        },
        modifier = modifier
    ) {
        AudioSource(
            text = stringResource(R.string.webrtc_stream_audio_mic),
            textSummary = stringResource(R.string.webrtc_stream_audio_mic_summary),
            iconId = R.drawable.mic_24px,
            selected = settings.enableMic,
            enabled = enabled,
            onChange = onMicEnabledChange
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            HorizontalDivider()

            AudioSource(
                text = stringResource(R.string.webrtc_stream_audio_device),
                textSummary = stringResource(R.string.webrtc_stream_audio_device_summary),
                iconId = R.drawable.mobile_speaker_24px,
                selected = settings.enableDeviceAudio,
                enabled = enabled,
                onChange = onDeviceAudioEnabledChange
            )
        }
    }
}

@Composable
private fun AudioSource(
    text: String,
    textSummary: String,
    @DrawableRes iconId: Int,
    enabled: Boolean,
    selected: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .conditional(enabled) { toggleable(value = selected, role = Role.Checkbox, onValueChange = onChange) }
            .padding(start = 16.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(id = iconId), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = text,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = textSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(checked = selected, onCheckedChange = null, modifier = Modifier.scale(0.7F), enabled = enabled)
    }
}

