package info.dvkr.screenstream.mjpeg.ui.main.settings.advanced

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
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow

@Composable
internal fun ServerPortRow(
    serverPort: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.settings_ethernet_24px,
        title = stringResource(R.string.mjpeg_pref_server_port),
        summary = stringResource(R.string.mjpeg_pref_server_port_summary),
        valueText = serverPort.toString(),
        onClick = onDetailShow
    )
}

@Composable
internal fun ServerPortEditor(
    serverPort: Int,
    onValueChange: (Int) -> Unit
) {
    var currentServerPort by remember(serverPort) {
        val text = serverPort.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout(scrollable = false) {
        Text(
            text = stringResource(R.string.mjpeg_pref_server_port_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentServerPort,
            onValueChange = { textField ->
                val digitsOnly = textField.text.filter(Char::isDigit).take(5)
                val filteredTextField = textField.copy(
                    text = digitsOnly,
                    selection = TextRange(digitsOnly.length)
                )
                val newServerPort = digitsOnly.toIntOrNull()
                if (newServerPort == null || newServerPort !in 1025..65535) {
                    currentServerPort = filteredTextField
                    isError = true
                } else {
                    currentServerPort = filteredTextField.copy(
                        text = newServerPort.toString(),
                        selection = TextRange(newServerPort.toString().length)
                    )
                    isError = false
                    onValueChange(newServerPort)
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
