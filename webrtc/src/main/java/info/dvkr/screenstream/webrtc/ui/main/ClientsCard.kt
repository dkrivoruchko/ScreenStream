package info.dvkr.screenstream.webrtc.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.common.ui.stylePlaceholder
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.ClientId
import info.dvkr.screenstream.webrtc.ui.WebRtcState

@Composable
internal fun ClientsCard(
    webRtcState: State<WebRtcState>,
    onClientDisconnect: (ClientId) -> Unit,
    modifier: Modifier = Modifier
) {
    val clients = remember { derivedStateOf { webRtcState.value.clients } }

    ExpandableCard(
        headerContent = {
            Text(
                text = stringResource(id = R.string.webrtc_stream_connected_clients, clients.value.size)
                    .stylePlaceholder(clients.value.size.toString(), SpanStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.align(Alignment.Center)
            )
        },
        modifier = modifier,
        contentModifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
        expandable = clients.value.isNotEmpty()
    ) {
        webRtcState.value.clients.forEachIndexed { index, client ->
            WebRtcClient(client = client, onClientDisconnect = onClientDisconnect)

            if (index != clients.value.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth())
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
        modifier = modifier.padding(start = 8.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "${client.publicId.substring(0, 4)}-${client.publicId.substring(4)}")
        Text(text = client.address, modifier = Modifier.padding(start = 8.dp).weight(1F))
        TextButton(onClick = { onClientDisconnect.invoke(ClientId(client.id)) }) {
            Text(text = stringResource(id = R.string.webrtc_item_client_disconnect))
        }
    }
}