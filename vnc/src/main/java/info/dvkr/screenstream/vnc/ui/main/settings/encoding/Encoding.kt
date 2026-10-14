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
import androidx.compose.ui.graphics.toComposeIntRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
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
    var lastCommitted by remember { mutableStateOf(maxFPS) }
    var currentMaxFPS by remember {
        val text = maxFPS.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(maxFPS) {
        if (maxFPS != lastCommitted) {
            lastCommitted = maxFPS
            val text = maxFPS.toString()
            currentMaxFPS = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_max_fps_text),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentMaxFPS,
            onValueChange = { textField ->
                val original = textField.text
                val digitsOnly = original.filter(Char::isDigit).take(2)
                val removedBeforeStart = original.take(textField.selection.start).count { !it.isDigit() }
                val removedBeforeEnd = original.take(textField.selection.end).count { !it.isDigit() }
                val newStart = (textField.selection.start - removedBeforeStart).coerceIn(0, digitsOnly.length)
                val newEnd = (textField.selection.end - removedBeforeEnd).coerceIn(newStart, digitsOnly.length)
                val newTextField = textField.copy(text = digitsOnly, selection = TextRange(newStart, newEnd))
                val newMaxFPS = digitsOnly.toIntOrNull()
                if (newMaxFPS == null || newMaxFPS !in 1..60) {
                    currentMaxFPS = newTextField
                    isError = true
                } else {
                    lastCommitted = newMaxFPS
                    currentMaxFPS = newTextField
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

@Composable
internal fun ScaleFactorRow(
    enabled: Boolean,
    scaleFactor: Int,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.resize_24px,
        title = stringResource(id = R.string.vnc_pref_scale),
        summary = stringResource(id = R.string.vnc_pref_scale_summary),
        valueText = stringResource(id = R.string.vnc_pref_scale_value, scaleFactor),
        onClick = onDetailShow
    )
}

@Composable
internal fun ScaleFactorEditor(
    scaleFactor: Int,
    onValueChange: (Int) -> Unit
) {
    val context = LocalContext.current
    val size = remember {
        WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds.toComposeIntRect().size
    }
    var lastCommitted by remember { mutableStateOf(scaleFactor) }
    var currentScaleFactor by remember {
        val text = scaleFactor.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(scaleFactor) {
        if (scaleFactor != lastCommitted) {
            lastCommitted = scaleFactor
            val text = scaleFactor.toString()
            currentScaleFactor = TextFieldValue(text = text, selection = TextRange(text.length))
        }
    }

    SettingEditorLayout {
        Text(
            text = stringResource(id = R.string.vnc_pref_scale_text, size.width, size.height),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = currentScaleFactor,
            onValueChange = { textField ->
                val original = textField.text
                val digitsOnly = original.filter(Char::isDigit).take(3)
                val removedBeforeStart = original.take(textField.selection.start).count { !it.isDigit() }
                val removedBeforeEnd = original.take(textField.selection.end).count { !it.isDigit() }
                val newStart = (textField.selection.start - removedBeforeStart).coerceIn(0, digitsOnly.length)
                val newEnd = (textField.selection.end - removedBeforeEnd).coerceIn(newStart, digitsOnly.length)
                val newTextField = textField.copy(text = digitsOnly, selection = TextRange(newStart, newEnd))
                val newScaleFactor = digitsOnly.toIntOrNull()
                if (newScaleFactor == null || newScaleFactor !in 10..100) {
                    currentScaleFactor = newTextField
                    isError = true
                } else {
                    lastCommitted = newScaleFactor
                    currentScaleFactor = newTextField
                    isError = false
                    onValueChange(newScaleFactor)
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

        Text(
            text = stringResource(
                id = R.string.vnc_pref_scale_result,
                size.width * scaleFactor / 100,
                size.height * scaleFactor / 100
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}
