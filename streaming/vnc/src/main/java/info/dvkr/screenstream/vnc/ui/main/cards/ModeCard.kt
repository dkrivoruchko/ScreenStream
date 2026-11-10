package info.dvkr.screenstream.vnc.ui.main.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.internal.VncEvent
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.VncError
import info.dvkr.screenstream.vnc.ui.VncReverseStatus
import info.dvkr.screenstream.vnc.ui.VncState

@Composable
internal fun ModeCard(
    sendEvent: (VncEvent) -> Unit,
    selectedMode: VncSettings.Values.Mode,
    onModeSelected: (VncSettings.Values.Mode) -> Unit,
    isStreaming: Boolean,
    serverNetInterfaces: List<VncState.VncNetInterface>,
    clients: List<String>,
    reverseStatus: VncReverseStatus,
    reverseMessage: String?,
    error: VncError?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            text = stringResource(id = R.string.vnc_mode_card_title),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )

                        ModeSelector(
                            selected = selectedMode,
                            onSelect = onModeSelected,
                            enabled = isStreaming.not()
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.fillMaxWidth())

                when (selectedMode) {
                    VncSettings.Values.Mode.SERVER -> ServerMode(
                        serverNetInterfaces = serverNetInterfaces,
                        clients = clients,
                        isStreaming = isStreaming,
                        sendEvent = sendEvent
                    )

                    VncSettings.Values.Mode.CLIENT -> ClientMode(
                        reverseStatus = reverseStatus,
                        reverseMessage = reverseMessage,
                        isStreaming = isStreaming,
                        sendEvent = sendEvent
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(
    selected: VncSettings.Values.Mode,
    onSelect: (VncSettings.Values.Mode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val modes = listOf(VncSettings.Values.Mode.SERVER, VncSettings.Values.Mode.CLIENT)

    Column(modifier = modifier.fillMaxWidth()) {
        modes.forEach { mode ->
            val isSelected = selected == mode
            val labelRes = when (mode) {
                VncSettings.Values.Mode.SERVER -> R.string.vnc_mode_server
                VncSettings.Values.Mode.CLIENT -> R.string.vnc_mode_client
            }
            val helperRes = when (mode) {
                VncSettings.Values.Mode.SERVER -> R.string.vnc_mode_server_hint
                VncSettings.Values.Mode.CLIENT -> R.string.vnc_mode_client_hint
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { if (enabled) onSelect(mode) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = enabled
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp)
                ) {
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(id = helperRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerMode(
    serverNetInterfaces: List<VncState.VncNetInterface>,
    clients: List<String>,
    isStreaming: Boolean,
    sendEvent: (VncEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = stringResource(id = R.string.vnc_mode_server_addresses),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (serverNetInterfaces.isEmpty()) {
            Text(
                text = stringResource(id = R.string.vnc_stream_no_address),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            serverNetInterfaces.forEach { netInterface ->
                Text(
                    text = stringResource(id = R.string.vnc_stream_interface, netInterface.fullAddress),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(id = R.string.vnc_clients_connected, clients.size),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ClientMode(
    reverseStatus: VncReverseStatus,
    reverseMessage: String?,
    isStreaming: Boolean,
    sendEvent: (VncEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        val statusRes = when (reverseStatus) {
            VncReverseStatus.IDLE -> R.string.vnc_reverse_status_idle
            VncReverseStatus.CONNECTING -> R.string.vnc_reverse_status_connecting
            VncReverseStatus.ACTIVE -> R.string.vnc_reverse_status_active
            VncReverseStatus.RECONNECTING -> R.string.vnc_reverse_status_reconnecting
            VncReverseStatus.ERROR -> R.string.vnc_reverse_status_error
        }
        Text(
            text = stringResource(id = statusRes),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyLarge
        )

        if (reverseMessage != null) {
            Text(
                text = reverseMessage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
