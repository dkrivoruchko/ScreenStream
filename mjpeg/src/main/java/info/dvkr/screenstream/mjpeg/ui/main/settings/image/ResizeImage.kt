package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toComposeIntRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueRow
import kotlin.math.min

@Composable
internal fun ResizeImageRow(
    resizeFactor: Int,
    width: Int,
    height: Int,
    onDetailShow: () -> Unit,
) {
    val valueText = if (width > 0 && height > 0) {
        stringResource(R.string.mjpeg_pref_resize_resolution_value, width, height)
    } else {
        stringResource(R.string.mjpeg_pref_resize_value, resizeFactor)
    }
    SettingValueRow(
        enabled = true,
        iconRes = R.drawable.resize_24px,
        title = stringResource(R.string.mjpeg_pref_resize),
        summary = stringResource(R.string.mjpeg_pref_resize_summary),
        valueText = valueText,
        onClick = onDetailShow
    )
}

@Composable
internal fun ResizeImageEditor(
    resizeFactor: Int,
    resolutionWidth: Int,
    resolutionHeight: Int,
    stretch: Boolean,
    onNewResize: (Int) -> Unit,
    onNewWidth: (Int) -> Unit,
    onNewHeight: (Int) -> Unit,
    onStretchChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val size = remember {
        WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds.toComposeIntRect().size
    }
    val isResolutionMode = remember(resolutionWidth, resolutionHeight) { resolutionWidth > 0 && resolutionHeight > 0 }
    var mode by rememberSaveable { mutableIntStateOf(if (isResolutionMode) 1 else 0) }
    var currentResizeFactor by remember(resizeFactor) {
        val text = resizeFactor.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var currentWidth by remember(resolutionWidth) {
        val text = if (resolutionWidth > 0) resolutionWidth.toString() else ""
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var currentHeight by remember(resolutionHeight) {
        val text = if (resolutionHeight > 0) resolutionHeight.toString() else ""
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    var isError by remember { mutableStateOf(false) }
    var widthError by remember { mutableStateOf(false) }
    var heightError by remember { mutableStateOf(false) }
    val (resizeFactorFocusRequester, widthFocusRequester) = remember { FocusRequester.createRefs() }

    val (resizedWidth, resizedHeight) = if (mode == 0) {
        (size.width * resizeFactor / 100F).toInt() to (size.height * resizeFactor / 100F).toInt()
    } else {
        if (currentWidth.text.isNotEmpty() && currentHeight.text.isNotEmpty()) {
            val w = currentWidth.text.toIntOrNull() ?: 0
            val h = currentHeight.text.toIntOrNull() ?: 0
            when {
                w <= 0 || h <= 0 -> 0 to 0
                stretch -> w to h
                size.width <= 0 || size.height <= 0 -> 0 to 0
                else -> {
                    val scale = min(w.toFloat() / size.width, h.toFloat() / size.height)
                    (size.width * scale).toInt() to (size.height * scale).toInt()
                }
            }
        } else 0 to 0
    }

    SettingEditorLayout {
        Text(
            text = stringResource(R.string.mjpeg_pref_resize_text, size.width, size.height),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        Text(
            text = stringResource(R.string.mjpeg_pref_resize_result, resizedWidth, resizedHeight),
            modifier = Modifier.fillMaxWidth()
        )

        Column(modifier = Modifier.selectableGroup()) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .selectable(
                        selected = mode == 0,
                        onClick = {
                            mode = 0
                            onNewWidth(0)
                            onNewHeight(0)
                            onStretchChange(false)
                        },
                        role = Role.RadioButton
                    )
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == 0, onClick = null)
                Text(
                    text = stringResource(R.string.mjpeg_pref_resize_mode_percent),
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .selectable(
                        selected = mode == 1,
                        onClick = { mode = 1 },
                        role = Role.RadioButton
                    )
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == 1, onClick = null)
                Text(
                    text = stringResource(R.string.mjpeg_pref_resize_mode_resolution),
                    modifier = Modifier.padding(start = 16.dp)
                )
            }
        }

        if (mode == 0) {
            OutlinedTextField(
                value = currentResizeFactor,
                onValueChange = { textField ->
                    val digitsOnly = textField.text.filter(Char::isDigit).take(3)
                    val filteredTextField = textField.copy(text = digitsOnly, selection = TextRange(digitsOnly.length))
                    val newResize = digitsOnly.toIntOrNull()
                    if (newResize == null || newResize !in 10..150) {
                        currentResizeFactor = filteredTextField
                        isError = true
                    } else {
                        currentResizeFactor = filteredTextField.copy(
                            text = newResize.toString(),
                            selection = TextRange(newResize.toString().length)
                        )
                        isError = false
                        onNewResize(newResize)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusRequester(resizeFactorFocusRequester),
                isError = isError,
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
            )
        } else {
            OutlinedTextField(
                value = currentWidth,
                onValueChange = { textField ->
                    val digitsOnly = textField.text.filter(Char::isDigit).take(5)
                    val filteredTextField = textField.copy(text = digitsOnly, selection = TextRange(digitsOnly.length))
                    val newWidth = digitsOnly.toIntOrNull()
                    if (newWidth == null || newWidth <= 0) {
                        currentWidth = filteredTextField
                        widthError = true
                    } else {
                        currentWidth = filteredTextField.copy(
                            text = newWidth.toString(),
                            selection = TextRange(newWidth.toString().length)
                        )
                        widthError = false
                        onNewWidth(newWidth)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .focusRequester(widthFocusRequester),
                isError = widthError,
                label = { Text(text = stringResource(R.string.mjpeg_pref_resize_width)) },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                singleLine = true
            )

            OutlinedTextField(
                value = currentHeight,
                onValueChange = { textField ->
                    val digitsOnly = textField.text.filter(Char::isDigit).take(5)
                    val filteredTextField = textField.copy(text = digitsOnly, selection = TextRange(digitsOnly.length))
                    val newHeight = digitsOnly.toIntOrNull()
                    if (newHeight == null || newHeight <= 0) {
                        currentHeight = filteredTextField
                        heightError = true
                    } else {
                        currentHeight = filteredTextField.copy(
                            text = newHeight.toString(),
                            selection = TextRange(newHeight.toString().length)
                        )
                        heightError = false
                        onNewHeight(newHeight)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                isError = heightError,
                label = { Text(text = stringResource(R.string.mjpeg_pref_resize_height)) },
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true
            )

            Row(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable { onStretchChange(!stretch) }
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = stretch, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.mjpeg_pref_resize_stretch),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }

    LaunchedEffect(mode, resizeFactorFocusRequester, widthFocusRequester) {
        if (mode == 0) resizeFactorFocusRequester.requestFocus() else widthFocusRequester.requestFocus()
    }
}
