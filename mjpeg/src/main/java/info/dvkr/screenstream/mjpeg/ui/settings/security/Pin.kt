package info.dvkr.screenstream.mjpeg.ui.settings.security

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

internal object Pin : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.PIN.name
    override val position: Int = 4
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_set_pin).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_set_pin_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_set_pin_text).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        PinUI(horizontalPadding, onDetailShow)

    @Composable
    override fun DetailUI(onBackClick: () -> Unit, headerContent: @Composable (String) -> Unit) =
        PinDetailUI(headerContent)
}

@Composable
private fun PinUI(
    horizontalPadding: Dp,
    onDetailShow: () -> Unit,
    mjpegSettings: MjpegSettings = koinInject()
) {
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val pin = remember { derivedStateOf { mjpegSettingsState.value.pin } }
    val enabled = remember {
        derivedStateOf {
            val value = mjpegSettingsState.value
            value.enablePin && value.newPinOnAppStart.not() && value.autoChangePin.not()
        }
    }

    Row(
        modifier = Modifier
            .clickable(enabled = enabled.value, role = Role.Button) { onDetailShow.invoke() }
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .then(if (enabled.value) Modifier else Modifier.alpha(0.5F)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Dialpad,
            contentDescription = stringResource(id = R.string.mjpeg_pref_set_pin),
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_set_pin),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_set_pin_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = pin.value, //TODO Hide while streaming
            modifier = Modifier
                .defaultMinSize(minWidth = 52.dp)
                .padding(end = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun PinDetailUI(
    headerContent: @Composable (String) -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
    mjpegSettings: MjpegSettings = koinInject()
) {

    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val pin = remember { derivedStateOf { mjpegSettingsState.value.pin } }
    val currentPin = remember { mutableStateOf(TextFieldValue(text = pin.value, selection = TextRange(pin.value.length))) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(true) { focusRequester.requestFocus() }

    val isError = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_set_pin))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_set_pin_text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            OutlinedTextField(
                value = currentPin.value,
                onValueChange = { textField ->
                    val newPinText = textField.text.take(6)
                    currentPin.value = textField.copy(text = newPinText)
                    val newPinInt = newPinText.toIntOrNull()
                    if (newPinText.length < 4 || newPinInt == null || newPinInt !in 0..999999) {
                        isError.value = true
                    } else {
                        isError.value = false
                        if (pin.value != newPinText) {
                            scope.launch { withContext(NonCancellable) { mjpegSettings.updateData { copy(pin = newPinText) } } }
                        }
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
}