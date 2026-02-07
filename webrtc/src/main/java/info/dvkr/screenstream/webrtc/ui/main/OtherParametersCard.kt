package info.dvkr.screenstream.webrtc.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.ui.settings.KeepAwake
import info.dvkr.screenstream.webrtc.ui.settings.StopOnSleep
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun OtherParametersCard(
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(text = stringResource(id = R.string.webrtc_stream_other_parameters), style = MaterialTheme.typography.titleMedium)
            }
        },
        modifier = modifier,
        initiallyExpanded = false
    ) {
        if (KeepAwake.available) {
            KeepAwake.ItemUI(horizontalPadding = 0.dp, coroutineScope = scope)
            HorizontalDivider()
        }

        StopOnSleep.ItemUI(horizontalPadding = 0.dp, coroutineScope = scope)
    }
}
