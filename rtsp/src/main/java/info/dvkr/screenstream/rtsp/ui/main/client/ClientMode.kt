package info.dvkr.screenstream.rtsp.ui.main.client

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.ConnectionError
import info.dvkr.screenstream.rtsp.ui.RtspState
import info.dvkr.screenstream.rtsp.ui.RtspTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun ClientMode(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    var currentAddress by remember(rtspSettingsState.value.serverAddress) {
        mutableStateOf(rtspSettingsState.value.serverAddress)
    }

    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = currentAddress,
            onValueChange = { text ->
                currentAddress = text
                scope.launch { rtspSettings.updateData { copy(serverAddress = text) } }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = rtspState.value.isStreaming.not(),
            label = { Text(text = stringResource(R.string.rtsp_server_address)) },
            isError = runCatching { RtspUrl.parse(currentAddress) }.isFailure,
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            singleLine = true
        )

        val statusMessage = when (val status = rtspState.value.transport.status) {
            is RtspTransportState.Status.Active -> stringResource(R.string.rtsp_connection_connected)
            RtspTransportState.Status.Starting -> stringResource(R.string.rtsp_connection_connecting)
            RtspTransportState.Status.Idle, is RtspTransportState.Status.Ready -> stringResource(R.string.rtsp_connection_disconnected)
            is RtspTransportState.Status.ClientError -> when (val error = status.error) {
                is ConnectionError.Failed -> stringResource(error.id) + (error.message?.let { " [$it]" } ?: "")
                else -> stringResource(error.id)
            }

            is RtspTransportState.Status.GenericError -> status.error.toString(LocalContext.current)
        }

        val statusColor = when (rtspState.value.transport.status) {
            is RtspTransportState.Status.ClientError,
            is RtspTransportState.Status.GenericError -> MaterialTheme.colorScheme.error

            is RtspTransportState.Status.Active -> MaterialTheme.colorScheme.primary

            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor,
            modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}