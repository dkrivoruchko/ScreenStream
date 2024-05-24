package info.dvkr.screenstream.mjpeg.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.MjpegState

@Composable
internal fun ClientsCard(
    mjpegState: State<MjpegState>,
    modifier: Modifier = Modifier
) {
    val expandable = remember { derivedStateOf { mjpegState.value.clients.isNotEmpty() } }

    ExpandableCard(
        headerContent = {
            val clientsCount = remember {
                derivedStateOf { mjpegState.value.clients.count { it.state != MjpegState.Client.State.DISCONNECTED } }
            }

            Text(
                text = stringResource(id = R.string.mjpeg_stream_connected_clients, clientsCount.value)
                    .stylePlaceholder(clientsCount.value.toString(), SpanStyle(fontWeight = FontWeight.Bold)),
                modifier = Modifier.align(Alignment.Center)
            )
        },
        modifier = modifier,
        contentModifier = Modifier.padding(8.dp),
        expandable = expandable.value
    ) {
        mjpegState.value.clients.forEachIndexed { index, client ->
            MjpegClient(client = client, modifier = Modifier.padding(horizontal = 4.dp))

            if (index != mjpegState.value.clients.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun MjpegClient(
    client: MjpegState.Client,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        val clientState = when (client.state) {
            MjpegState.Client.State.CONNECTED -> stringResource(id = R.string.mjpeg_stream_client_connected)
            MjpegState.Client.State.SLOW_CONNECTION -> stringResource(id = R.string.mjpeg_stream_client_slow_network)
            MjpegState.Client.State.DISCONNECTED -> stringResource(id = R.string.mjpeg_stream_client_disconnected)
            MjpegState.Client.State.BLOCKED -> stringResource(id = R.string.mjpeg_stream_client_blocked)
        }

        Text(text = client.address, modifier = Modifier.weight(1F))
        Text(text = clientState)
    }
}