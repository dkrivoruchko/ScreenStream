package info.dvkr.screenstream.mjpeg.ui.main.settings.security

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.ui.RobotoMonoBold
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.SettingEditorLayout

@Composable
internal fun PinRow(
    pin: String,
    enabled: Boolean,
    isPinVisible: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 8.dp)
            .conditional(enabled.not()) { alpha(0.5F) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.dialpad_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(R.string.mjpeg_pref_set_pin),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(R.string.mjpeg_pref_set_pin_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = if (isPinVisible) pin else "*",
            modifier = Modifier
                .defaultMinSize(minWidth = 52.dp)
                .padding(end = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontFamily = RobotoMonoBold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
internal fun PinEditor(
    pin: String,
    onValueChange: (String) -> Unit
) {
    var currentPin by remember(pin) { mutableStateOf(TextFieldValue(text = pin, selection = TextRange(pin.length))) }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    SettingEditorLayout(scrollable = false) {
        Text(
            text = stringResource(R.string.mjpeg_pref_set_pin_text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = currentPin,
            onValueChange = { textField ->
                val newPinText = textField.text.take(6)
                currentPin = textField.copy(text = newPinText)
                val newPinInt = newPinText.toIntOrNull()
                if (newPinText.length < 4 || newPinInt == null || newPinInt !in 0..999999) {
                    isError = true
                } else {
                    isError = false
                    onValueChange(newPinText)
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
