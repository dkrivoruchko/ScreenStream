package info.dvkr.screenstream.webrtc.ui.main.cards

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.ClientId
import info.dvkr.screenstream.webrtc.ui.WebRtcState

@Composable
internal fun ClientsCard(
    state: WebRtcState,
    onClientDisconnect: (ClientId) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = rememberSaveable { mutableStateOf(false) }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Text(
                text = stringResource(id = R.string.webrtc_stream_connected_clients, state.clients.size)
                    .stylePlaceholder(state.clients.size.toString(), SpanStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            )
        },
        modifier = modifier,
        contentModifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        expandable = state.clients.isNotEmpty(),
    ) {
        state.clients.forEachIndexed { index, client ->
            WebRtcClient(client = client, onClientDisconnect = onClientDisconnect)

            if (index != state.clients.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun WebRtcClient(
    client: WebRtcState.Client,
    onClientDisconnect: (ClientId) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(start = 8.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "${client.publicId.substring(0, 4)}-${client.publicId.substring(4)}")
        Text(
            text = client.address,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1F), textAlign = TextAlign.Center
        )
        TextButton(onClick = { onClientDisconnect.invoke(ClientId(client.id)) }) {
            Text(text = stringResource(id = R.string.webrtc_item_client_disconnect))
        }
    }
}
