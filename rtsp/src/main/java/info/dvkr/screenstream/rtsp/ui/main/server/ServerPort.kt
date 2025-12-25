package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object ServerPort : ModuleSettings.Item {
    override val id: String = RtspSettings.Key.SERVER_PORT.name
    override val position: Int = 5
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.rtsp_pref_server_port).contains(text, ignoreCase = true) ||
                getString(R.string.rtsp_pref_server_port_summary).contains(text, ignoreCase = true) ||
                getString(R.string.rtsp_pref_server_port_text).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()

        ServerPortUI(horizontalPadding, enabled, rtspSettingsState.value.serverPort, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<RtspSettings>()
        val rtspSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        ServerPortDetailUI(headerContent, rtspSettingsState.value.serverPort.toString()) {
            if (rtspSettingsState.value.serverPort != it) {
                scope.launch { mjpegSettings.updateData { copy(serverPort = it) } }
            }
        }
    }
}

@Composable
private fun ServerPortUI(horizontalPadding: Dp, enabled: Boolean, serverPort: Int, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 12.dp, end = horizontalPadding + 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.settings_ethernet_24px),
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_server_port),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.rtsp_pref_server_port_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = serverPort.toString(),
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ServerPortDetailUI(
    headerContent: @Composable (String) -> Unit,
    serverPort: String,
    onValueChange: (Int) -> Unit
) {
    val currentServerPort = remember(serverPort) {
        mutableStateOf(TextFieldValue(text = serverPort, selection = TextRange(serverPort.length)))
    }
    val isError = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.rtsp_pref_server_port))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_server_port_text),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = currentServerPort.value,
                onValueChange = { textField ->
                    val newServerPort = textField.text.take(5).toIntOrNull()
                    if (newServerPort == null || newServerPort !in 1025..65535) {
                        currentServerPort.value = textField.copy(text = textField.text.take(5))
                        isError.value = true
                    } else {
                        currentServerPort.value = textField.copy(text = newServerPort.toString())
                        isError.value = false
                        onValueChange.invoke(newServerPort)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester),
                isError = isError.value,
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
            )
        }
    }

    LaunchedEffect(Unit) { delay(50); focusRequester.requestFocus() }
}
