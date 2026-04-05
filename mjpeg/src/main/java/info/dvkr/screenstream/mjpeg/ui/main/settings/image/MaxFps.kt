package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow
import kotlin.math.abs

@Composable
internal fun MaxFpsRow(
    maxFPS: Int,
    onDetailShow: () -> Unit
) {
    val valueText = if (maxFPS > 0 || maxFPS == -1) abs(maxFPS).toString() else "1/${abs(maxFPS)}"
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.burst_mode_24px,
        title = stringResource(R.string.mjpeg_pref_fps),
        summary = stringResource(R.string.mjpeg_pref_fps_summary),
        valueText = valueText,
        onClick = onDetailShow
    )
}

@Composable
internal fun MaxFpsEditor(
    maxFPS: Int,
    onValueChange: (Int) -> Unit
) {
    var currentFPS by remember(maxFPS) {
        val text = abs(maxFPS).toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout {
        Text(
            text = stringResource(R.string.mjpeg_pref_fps_text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = currentFPS,
            onValueChange = { textField ->
                val maxLength = if (maxFPS >= 0) 3 else 2
                val digitsOnly = textField.text.filter(Char::isDigit).take(maxLength)
                val filteredTextField = textField.copy(text = digitsOnly, selection = TextRange(digitsOnly.length))
                val newValue = digitsOnly.toIntOrNull()
                if (maxFPS >= 0) {
                    if (newValue == null || newValue !in 1..120) {
                        currentFPS = filteredTextField
                        isError = true
                    } else {
                        currentFPS = filteredTextField.copy(
                            text = newValue.toString(),
                            selection = TextRange(newValue.toString().length)
                        )
                        isError = false
                        onValueChange(newValue)
                    }
                } else {
                    if (newValue == null || newValue !in 1..10) {
                        currentFPS = filteredTextField
                        isError = true
                    } else {
                        currentFPS = filteredTextField.copy(
                            text = newValue.toString(),
                            selection = TextRange(newValue.toString().length)
                        )
                        isError = false
                        onValueChange(-newValue)
                    }
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

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = maxFPS < 0,
                onCheckedChange = {
                    onValueChange(if (maxFPS >= 0) -5 else MjpegSettings.Default.MAX_FPS)
                    isError = false
                },
                modifier = Modifier.align(Alignment.CenterVertically)
            )

            Text(
                text = stringResource(R.string.mjpeg_pref_fps_low_mode_text, abs(maxFPS)),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
