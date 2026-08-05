package info.dvkr.screenstream.vnc.ui.main.settings.encoding

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
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.vnc.ui.main.settings.common.SettingSwitchRow
import info.dvkr.screenstream.vnc.ui.main.settings.common.SettingValueRow

@Composable
internal fun MaxFpsRow(
    enabled: Boolean,
    maxFPS: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.lan_24px,
        title = stringResource(id = R.string.vnc_pref_max_fps),
        summary = stringResource(id = R.string.vnc_pref_max_fps_summary),
        valueText = maxFPS.toString(),
        onClick = onDetailShow
    )
}

@Composable
internal fun MaxFpsEditor(
    maxFPS: Int,
    onValueChange: (Int) -> Unit
) {
    var currentMaxFPS by remember(maxFPS) {
        val text = maxFPS.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_max_fps_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentMaxFPS,
            onValueChange = { textField ->
                val digitsOnly = textField.text.filter(Char::isDigit).take(2)
                val filteredTextField = textField.copy(
                    text = digitsOnly,
                    selection = TextRange(digitsOnly.length)
                )
                val newMaxFPS = digitsOnly.toIntOrNull()
                if (newMaxFPS == null || newMaxFPS !in 1..60) {
                    currentMaxFPS = filteredTextField
                    isError = true
                } else {
                    currentMaxFPS = filteredTextField.copy(
                        text = newMaxFPS.toString(),
                        selection = TextRange(newMaxFPS.toString().length)
                    )
                    isError = false
                    onValueChange(newMaxFPS)
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
internal fun ZlibEncodingRow(
    enabled: Boolean,
    zlibEncoding: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = zlibEncoding,
        iconRes = R.drawable.settings_ethernet_24px,
        title = stringResource(id = R.string.vnc_pref_zlib),
        summary = stringResource(id = R.string.vnc_pref_zlib_summary),
        onValueChange = onValueChange
    )
}
