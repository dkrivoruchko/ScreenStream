package info.dvkr.screenstream.mjpeg.ui.main.settings.image

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component1
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component2
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component3
import androidx.compose.ui.focus.FocusRequester.Companion.FocusRequesterFactory.component4
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingValueSwitchRow

@Composable
internal fun CropImageRow(
    imageCrop: Boolean,
    onDetailShow: () -> Unit,
    onValueChange: (Boolean) -> Unit,
) {
    SettingValueSwitchRow(
        enabled = true,
        checked = imageCrop,
        iconRes = R.drawable.crop_24px,
        title = stringResource(R.string.mjpeg_pref_crop),
        summary = stringResource(R.string.mjpeg_pref_crop_summary),
        onRowClick = onDetailShow,
        onCheckedChange = onValueChange
    )
}

@Composable
internal fun CropImageEditor(
    imageCropTop: Int,
    imageCropBottom: Int,
    imageCropLeft: Int,
    imageCropRight: Int,
    onNewValueTop: (Int) -> Unit,
    onNewValueBottom: (Int) -> Unit,
    onNewValueLeft: (Int) -> Unit,
    onNewValueRight: (Int) -> Unit
) {
    val topError = remember { mutableStateOf(false) }
    val bottomError = remember { mutableStateOf(false) }
    val leftError = remember { mutableStateOf(false) }
    val rightError = remember { mutableStateOf(false) }
    val hasError = topError.value || bottomError.value || leftError.value || rightError.value
    val focusManager = LocalFocusManager.current
    val (topFocusRequester, bottomFocusRequester, leftFocusRequester, rightFocusRequester) = remember { FocusRequester.createRefs() }

    SettingEditorLayout {
        Text(
            text = if (hasError) stringResource(R.string.mjpeg_pref_crop_error) else stringResource(R.string.mjpeg_pref_crop_warning),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            color = if (hasError) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodyLarge
        )

        CropRow(imageCropTop, R.string.mjpeg_pref_crop_top, topError, topFocusRequester, focusManager, onNewValueTop)
        CropRow(imageCropBottom, R.string.mjpeg_pref_crop_bottom, bottomError, bottomFocusRequester, focusManager, onNewValueBottom)
        CropRow(imageCropLeft, R.string.mjpeg_pref_crop_left, leftError, leftFocusRequester, focusManager, onNewValueLeft)
        CropRow(imageCropRight, R.string.mjpeg_pref_crop_right, rightError, rightFocusRequester, focusManager, onNewValueRight)
    }

    LaunchedEffect(topFocusRequester) { topFocusRequester.requestFocus() }
}

@Composable
private fun CropRow(
    crop: Int,
    @StringRes labelRes: Int,
    hasError: MutableState<Boolean>,
    focusRequester: FocusRequester,
    focusManager: FocusManager,
    onNewValue: (Int) -> Unit
) {
    var currentCrop by remember(crop) {
        val value = crop.toString()
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    OutlinedTextField(
        value = currentCrop,
        onValueChange = { textField ->
            val digitsOnly = textField.text.filter { it.isDigit() }.take(6)
            val filteredTextField = textField.copy(text = digitsOnly, selection = TextRange(digitsOnly.length))
            val newCrop = digitsOnly.toIntOrNull()
            if (newCrop == null) {
                currentCrop = filteredTextField
                hasError.value = true
            } else {
                currentCrop = filteredTextField.copy(
                    text = newCrop.toString(),
                    selection = TextRange(newCrop.toString().length)
                )
                hasError.value = false
                onNewValue(newCrop)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .focusRequester(focusRequester),
        label = {
            Text(
                text = stringResource(labelRes) + " (" + stringResource(R.string.mjpeg_pref_crop_pixels) + ")",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        isError = hasError.value,
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) }
        ),
        singleLine = true
    )
}
