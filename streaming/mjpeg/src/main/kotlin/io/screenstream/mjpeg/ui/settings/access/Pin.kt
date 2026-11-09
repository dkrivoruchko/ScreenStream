package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object PinSearch {
    val fieldLabelRes: Int = R.string.mjpeg_pref_access_pin_label
    val regenerateLabelRes: Int = R.string.mjpeg_pref_access_pin_regenerate
    val permanentHelpRes: Int = R.string.mjpeg_pref_access_pin_permanent_help

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Access,
        titleRes = R.string.mjpeg_pref_access_pin,
        summaryRes = R.string.mjpeg_pref_access_pin_summary,
        extraTermResIds = listOf(fieldLabelRes, regenerateLabelRes, permanentHelpRes),
    )
}

@Composable
internal fun PinSettingItem(
    pin: SecretValue?,
    pinPolicy: AccessSettings.PinPolicy,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
) {
    var pinRevealed by remember(pin) { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    val revealActionDescription = stringResource(if (pinRevealed) R.string.mjpeg_pin_hide_description else R.string.mjpeg_pin_show_description)
    SettingsItem(
        title = stringResource(PinSearch.info.titleRes),
        summary = stringResource(PinSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_dialpad,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        valueContent = {
            Text(
                when {
                    pin == null -> stringResource(R.string.mjpeg_pref_access_pin_unavailable)
                    pinRevealed -> pin.value
                    else -> stringResource(R.string.mjpeg_pin_mask)
                },
                color = if (pin == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = if (pin != null) {
            {
                TextButton(
                    onClick = { pinRevealed = !pinRevealed },
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .offset(x = 8.dp)
                        .semantics { contentDescription = revealActionDescription },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(stringResource(if (pinRevealed) R.string.mjpeg_pin_hide_label else R.string.mjpeg_pin_show_label))
                }
            }
        } else null,
    )
    if (showEditor) {
        PinEditor(pin, pinPolicy, canEditStreamSetup, onAccessChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun PinEditor(
    pin: SecretValue?,
    pinPolicy: AccessSettings.PinPolicy,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var pinRevealed by remember { mutableStateOf(false) }
    val revealActionDescription = stringResource(if (pinRevealed) R.string.mjpeg_pin_hide_description else R.string.mjpeg_pin_show_description)
    val isPermanentPin = pinPolicy == AccessSettings.PinPolicy.Permanent
    var pinInput by remember(pinPolicy) { mutableStateOf(TextFieldValue(pin?.value.orEmpty())) }
    var followSavedPin by remember(pinPolicy) { mutableStateOf(true) }
    // Regeneration follows saved changes; manual edits keep their draft and cursor.
    SideEffect(pin, pinPolicy, followSavedPin) {
        if (!isPermanentPin || followSavedPin) {
            val savedPinText = pin?.value.orEmpty()
            if (pinInput.text != savedPinText) pinInput = TextFieldValue(savedPinText)
        }
    }
    val focusManager = LocalFocusManager.current
    val canEditPin = isPermanentPin && canEditStreamSetup
    val errorText = if (canEditPin && !AccessSettings.isValidPin(pinInput.text)) stringResource(R.string.mjpeg_pref_access_pin_error) else null
    val supportingMessage = when {
        errorText != null -> errorText
        !canEditPin && pin == null -> stringResource(R.string.mjpeg_pref_access_pin_unavailable)
        else -> null
    }

    SettingEditor(
        title = stringResource(PinSearch.info.titleRes),
        description = stringResource(PinSearch.info.summaryRes),
        onDismiss = {
            if (canEditPin && !followSavedPin && AccessSettings.isValidPin(pinInput.text) && pinInput.text != pin?.value) {
                val newPin = SecretValue(pinInput.text)
                onAccessChange {
                    if (this.pinPolicy == AccessSettings.PinPolicy.Permanent && AccessSettings.isValidPin(newPin.value)) {
                        copy(pin = newPin)
                    } else {
                        this
                    }
                }
            }
            onDismiss()
        },
    ) {
        OutlinedTextField(
            value = pinInput,
            onValueChange = { next ->
                if (!canEditPin) {
                    if (next.text == pinInput.text) pinInput = next
                    return@OutlinedTextField
                }
                val textChanged = next.text != pinInput.text
                if (textChanged) followSavedPin = false
                pinInput = next
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (errorText != null) error(errorText) },
            label = { Text(stringResource(PinSearch.fieldLabelRes)) },
            trailingIcon = {
                TextButton(
                    onClick = { pinRevealed = !pinRevealed },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = revealActionDescription },
                ) {
                    Text(stringResource(if (pinRevealed) R.string.mjpeg_pin_hide_label else R.string.mjpeg_pin_show_label))
                }
            },
            readOnly = !canEditPin,
            singleLine = true,
            isError = errorText != null,
            supportingText = if (supportingMessage != null) {
                { Text(supportingMessage) }
            } else null,
            visualTransformation = if (pinRevealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        when {
            !canEditStreamSetup -> SettingInfo(stringResource(R.string.mjpeg_pref_setup_locked))
            !isPermanentPin -> SettingInfo(stringResource(PinSearch.permanentHelpRes), iconRes = R.drawable.mjpeg_icon_warning)
        }
        OutlinedButton(
            onClick = {
                if (!canEditStreamSetup) return@OutlinedButton
                focusManager.clearFocus()
                followSavedPin = true
                onAccessChange { withGeneratedPin() }
            },
            enabled = canEditStreamSetup,
        ) {
            Text(stringResource(PinSearch.regenerateLabelRes))
        }
    }
}
