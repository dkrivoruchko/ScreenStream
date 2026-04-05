package info.dvkr.screenstream.webrtc.ui.main.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.main.settings.KeepAwakeRow
import info.dvkr.screenstream.webrtc.ui.main.settings.StopOnSleepRow
import info.dvkr.screenstream.webrtc.ui.main.settings.keepAwakeAvailable

@Composable
internal fun OtherSettingsCard(
    settings: WebRtcSettings.Data,
    updateSettings: (WebRtcSettings.Data.() -> WebRtcSettings.Data) -> Unit,
    enabled: Boolean,
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
                Text(text = stringResource(id = R.string.webrtc_stream_other_parameters), style = MaterialTheme.typography.titleMedium)
            }
        },
        modifier = modifier
    ) {
        if (keepAwakeAvailable) {
            KeepAwakeRow(
                enabled = enabled,
                keepAwake = settings.keepAwake
            ) { newValue ->
                updateSettings {
                    copy(keepAwake = newValue)
                }
            }
            HorizontalDivider()
        }

        StopOnSleepRow(
            enabled = enabled,
            stopOnSleep = settings.stopOnSleep
        ) { newValue ->
            updateSettings {
                copy(stopOnSleep = newValue)
            }
        }
    }
}
