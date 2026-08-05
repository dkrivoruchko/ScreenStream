package info.dvkr.screenstream.vnc.ui.main.settings.client

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.vnc.ui.main.settings.common.SettingValueRow

@Composable
internal fun ReverseConnectHostRow(
    enabled: Boolean,
    reverseConnectHost: String,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.ip_network_24px,
        title = stringResource(id = R.string.vnc_pref_reverse_host),
        summary = stringResource(id = R.string.vnc_pref_reverse_host_summary),
        valueText = reverseConnectHost,
        onClick = onDetailShow
    )
}

@Composable
internal fun ReverseConnectHostEditor(
    reverseConnectHost: String,
    onValueChange: (String) -> Unit
) {
    var currentReverseConnectHost by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(text = reverseConnectHost, selection = TextRange(reverseConnectHost.length)))
    }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_reverse_host_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentReverseConnectHost,
            onValueChange = { host ->
                val textChanged = host.text != currentReverseConnectHost.text
                currentReverseConnectHost = host
                if (textChanged) onValueChange(host.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions.Default.copy(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            singleLine = true,
        )
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
internal fun ReverseConnectPortRow(
    enabled: Boolean,
    reverseConnectPort: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.settings_ethernet_24px,
        title = stringResource(id = R.string.vnc_pref_reverse_port),
        summary = stringResource(id = R.string.vnc_pref_reverse_port_summary),
        valueText = reverseConnectPort.toString(),
        onClick = onDetailShow
    )
}

@Composable
internal fun ReverseConnectPortEditor(
    reverseConnectPort: Int,
    onValueChange: (Int) -> Unit
) {
    var currentReverseConnectPort by remember(reverseConnectPort) {
        val text = reverseConnectPort.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_reverse_port_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentReverseConnectPort,
            onValueChange = { textField ->
                val digitsOnly = textField.text.filter(Char::isDigit).take(5)
                val filteredTextField = textField.copy(
                    text = digitsOnly,
                    selection = TextRange(digitsOnly.length)
                )
                val newReverseConnectPort = digitsOnly.toIntOrNull()
                if (newReverseConnectPort == null || newReverseConnectPort !in 1..65535) {
                    currentReverseConnectPort = filteredTextField
                    isError = true
                } else {
                    currentReverseConnectPort = filteredTextField.copy(
                        text = newReverseConnectPort.toString(),
                        selection = TextRange(newReverseConnectPort.toString().length)
                    )
                    isError = false
                    onValueChange(newReverseConnectPort)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .focusRequester(focusRequester),
            isError = isError,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true,
        )
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
internal fun ReconnectDelayRow(
    enabled: Boolean,
    reconnectDelaySeconds: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.lan_24px,
        title = stringResource(id = R.string.vnc_pref_reconnect_delay),
        summary = stringResource(id = R.string.vnc_pref_reconnect_delay_summary),
        valueText = reconnectDelaySeconds.toString(),
        onClick = onDetailShow
    )
}

@Composable
internal fun ReconnectDelayEditor(
    reconnectDelaySeconds: Int,
    onValueChange: (Int) -> Unit
) {
    var currentReconnectDelaySeconds by remember(reconnectDelaySeconds) {
        val text = reconnectDelaySeconds.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_reconnect_delay_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentReconnectDelaySeconds,
            onValueChange = { textField ->
                val digitsOnly = textField.text.filter(Char::isDigit).take(3)
                val filteredTextField = textField.copy(
                    text = digitsOnly,
                    selection = TextRange(digitsOnly.length)
                )
                val newReconnectDelaySeconds = digitsOnly.toIntOrNull()
                if (newReconnectDelaySeconds == null || newReconnectDelaySeconds !in 1..120) {
                    currentReconnectDelaySeconds = filteredTextField
                    isError = true
                } else {
                    currentReconnectDelaySeconds = filteredTextField.copy(
                        text = newReconnectDelaySeconds.toString(),
                        selection = TextRange(newReconnectDelaySeconds.toString().length)
                    )
                    isError = false
                    onValueChange(newReconnectDelaySeconds)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .focusRequester(focusRequester),
            isError = isError,
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            singleLine = true,
        )
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
