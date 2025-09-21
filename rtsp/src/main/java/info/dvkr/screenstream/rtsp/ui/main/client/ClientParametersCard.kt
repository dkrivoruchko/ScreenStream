package info.dvkr.screenstream.rtsp.ui.main.client

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspState
import info.dvkr.screenstream.rtsp.ui.main.components.ExpressiveButtonGroup
import info.dvkr.screenstream.rtsp.ui.main.components.ExpressiveButtonOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun ClientParametersCard(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    if (rtspSettingsState.value.mode == RtspSettings.Values.Mode.SERVER) {
        return
    }

    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_client_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        initiallyExpanded = true
    ) {
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        val isStreaming = rtspState.value.isStreaming
        val selectedProtocol = remember(rtspSettingsState.value.protocol) {
            mutableStateOf(Protocol.valueOf(rtspSettingsState.value.protocol))
        }
        val protocolOptions = remember {
            Protocol.entries.map { protocol -> ExpressiveButtonOption(value = protocol, label = protocol.name) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        ExpressiveButtonGroup(
            options = protocolOptions,
            selected = selectedProtocol.value,
            onOptionSelected = { protocol ->
                selectedProtocol.value = protocol
                scope.launch { rtspSettings.updateData { copy(protocol = protocol.name) } }
            },
            enabled = isStreaming.not()
        )
    }
}