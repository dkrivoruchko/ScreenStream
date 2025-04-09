package info.dvkr.screenstream.rtsp.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.ConnectionError
import info.dvkr.screenstream.rtsp.ui.ConnectionState
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun MediaServerCard(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
    rtspSettings: RtspSettings = koinInject()
) {
    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_media_server),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        initiallyExpanded = true
    ) {
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        val serverAddress = rtspSettingsState.value.serverAddress

        var currentAddress by remember { mutableStateOf(serverAddress) }
        var isError by remember(currentAddress) { mutableStateOf(runCatching { RtspUrl.parse(currentAddress) }.isFailure) }

        OutlinedTextField(
            value = currentAddress,
            onValueChange = { text ->
                currentAddress = text
                scope.launch { rtspSettings.updateData { copy(serverAddress = text) } }
            },
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                .fillMaxWidth(),
            label = { Text(text = stringResource(R.string.rtsp_server_address)) },
            isError = isError,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            singleLine = true,
        )

        Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            val selectedProtocol by remember { mutableStateOf(Protocol.valueOf(rtspSettingsState.value.protocol)) }

            OutlinedCard(
                modifier = Modifier.selectableGroup(),
                shape = OutlinedTextFieldDefaults.shape,
                colors = CardDefaults.outlinedCardColors().copy(containerColor = Color.Transparent),
                border = BorderStroke(
                    OutlinedTextFieldDefaults.UnfocusedBorderThickness,
                    OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor
                )
            ) {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    Protocol.entries.forEachIndexed { i, protocol ->
                        if (i > 0) {
                            VerticalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor
                            )
                        }
                        Row(
                            Modifier
                                .weight(1F)
                                .padding(vertical = 8.dp, horizontal = 6.dp)
                                .defaultMinSize(minHeight = 32.dp)
                                .selectable(
                                    selected = (protocol == selectedProtocol),
                                    onClick = { scope.launch { rtspSettings.updateData { copy(protocol = protocol.name) } } },
                                    role = Role.RadioButton
                                ),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = (protocol == selectedProtocol), onClick = null)
                            Text(
                                text = protocol.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.rtsp_protocol),
                style = MaterialTheme.typography.bodySmall,
                color = OutlinedTextFieldDefaults.colors().unfocusedLabelColor,
                modifier = Modifier
                    .offset(x = 12.dp, y = (-8).dp)
                    .background(CardDefaults.elevatedCardColors().containerColor)
                    .padding(horizontal = 4.dp)
            )
        }

        val connectionState = rtspState.value.connectionState
        Text(
            text = when (connectionState) {
                ConnectionState.Connected -> stringResource(R.string.rtsp_connection_connected)
                ConnectionState.Connecting -> stringResource(R.string.rtsp_connection_connecting)
                ConnectionState.Disconnected -> stringResource(R.string.rtsp_connection_disconnected)
                is ConnectionState.Error -> when (val error = connectionState.connectionError) {
                    is ConnectionError.Failed -> stringResource(error.id) + " [${error.message}]"
                    else -> stringResource(error.id)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (connectionState) {
                is ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                is ConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> Color.Unspecified
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
        )
    }
}