package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.FillableData
import androidx.compose.ui.autofill.createFromText
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.fillableData
import androidx.compose.ui.semantics.onFillData
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
    val isPermanentPin = pinPolicy == AccessSettings.PinPolicy.Permanent
    val pinInput = remember(pinPolicy) { TextFieldState(pin?.value.orEmpty()) }
    var lastAppliedPin by remember(pinPolicy) { mutableStateOf(pin) }

    fun hasManualEdit(): Boolean =
        !pinInput.text.contentEquals(lastAppliedPin?.value.orEmpty()) || pinInput.undoState.canUndo || pinInput.undoState.canRedo

    fun followSavedPin() {
        val savedPin = pin?.value.orEmpty()
        if (!pinInput.text.contentEquals(savedPin)) {
            pinInput.setTextAndPlaceCursorAtEnd(savedPin)
        }
        pinInput.undoState.clearHistory()
        lastAppliedPin = pin
    }

    // An edit followed by Undo still owns the draft. Selection changes alone leave the history empty.
    SideEffect(pin, pinInput) {
        if (!isPermanentPin || !hasManualEdit()) followSavedPin()
    }
    val canEditPin = isPermanentPin && canEditStreamSetup
    val errorText = if (canEditPin && !AccessSettings.isValidPin(pinInput.text.toString())) {
        stringResource(R.string.mjpeg_pref_access_pin_error)
    } else null
    val supportingMessage = when {
        errorText != null -> errorText
        !canEditPin && pin == null -> stringResource(R.string.mjpeg_pref_access_pin_unavailable)
        else -> null
    }

    SettingEditor(
        title = stringResource(PinSearch.info.titleRes),
        description = stringResource(PinSearch.info.summaryRes),
        onDismiss = {
            val draft = pinInput.text.toString()
            if (canEditPin && hasManualEdit() && AccessSettings.isValidPin(draft) && draft != pin?.value) {
                val newPin = SecretValue(draft)
                onAccessChange {
                    if (this.pinPolicy == AccessSettings.PinPolicy.Permanent) {
                        copy(pin = newPin)
                    } else {
                        this
                    }
                }
            }
            onDismiss()
        },
    ) {
        val focusManager = LocalFocusManager.current
        val obfuscationCharacter = '•'
        val interactionSource = remember { MutableInteractionSource() }
        val focused by interactionSource.collectIsFocusedAsState()
        val colors = OutlinedTextFieldDefaults.colors()
        val isError = errorText != null
        val labelPadding = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2 }
        val revealActionDescription = stringResource(if (pinRevealed) R.string.mjpeg_pin_hide_description else R.string.mjpeg_pin_show_description)
        val inputTransformation = remember(canEditPin) {
            InputTransformation {
                if (changes.changeCount == 0) return@InputTransformation
                val sameText = asCharSequence().contentEquals(originalText)
                if (canEditPin && !sameText) return@InputTransformation
                // Foundation 1.13 can send a drop to a read-only field.
                val nextSelection = selection
                revertAllChanges()
                // A same-text replacement may move the cursor without claiming the draft.
                if (sameText) selection = nextSelection
            }
        }
        val emptyAutofillValue = remember { FillableData.createFromText("") }

        CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
            BasicSecureTextField(
                state = pinInput,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        if (errorText != null) error(errorText)
                        // Foundation 1.13 obfuscates drawing separately from the accessibility text.
                        if (!pinRevealed) {
                            val text = pinInput.text
                            val maskLength = Character.codePointCount(text, 0, text.length)
                            editableText = AnnotatedString(obfuscationCharacter.toString().repeat(maskLength))
                            if (maskLength != text.length) {
                                // Only supplementary characters need offsets translated for the mask.
                                val selection = pinInput.selection
                                textSelectionRange = TextRange(
                                    Character.codePointCount(text, 0, selection.start),
                                    Character.codePointCount(text, 0, selection.end),
                                )
                                setSelection { start, end, _ ->
                                    val currentText = pinInput.text
                                    val length = Character.codePointCount(currentText, 0, currentText.length)
                                    if (start !in 0..length || end !in 0..length) return@setSelection false
                                    pinInput.edit {
                                        this.selection = TextRange(
                                            Character.offsetByCodePoints(currentText, 0, start),
                                            Character.offsetByCodePoints(currentText, 0, end),
                                        )
                                    }
                                    true
                                }
                            }
                        }
                        // The local stream PIN does not participate in Android Autofill.
                        contentDataType = ContentDataType.None
                        emptyAutofillValue?.let { fillableData = it }
                        onFillData { false }
                    }
                    .padding(top = labelPadding)
                    .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight),
                readOnly = !canEditPin,
                inputTransformation = inputTransformation,
                textStyle = LocalTextStyle.current.copy(color = colors.textColor(enabled = true, isError = isError, focused = focused)),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = { focusManager.clearFocus() },
                interactionSource = interactionSource,
                cursorBrush = SolidColor(colors.cursorColor(isError)),
                decorator = OutlinedTextFieldDefaults.decorator(
                    state = pinInput,
                    enabled = true,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    outputTransformation = null,
                    interactionSource = interactionSource,
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
                    supportingText = if (supportingMessage != null) {
                        { Text(supportingMessage) }
                    } else null,
                    isError = isError,
                    colors = colors,
                ),
                textObfuscationMode = if (pinRevealed) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                textObfuscationCharacter = obfuscationCharacter,
            )
        }
        when {
            !canEditStreamSetup -> SettingInfo(stringResource(R.string.mjpeg_pref_setup_locked))
            !isPermanentPin -> SettingInfo(stringResource(PinSearch.permanentHelpRes), iconRes = R.drawable.mjpeg_icon_warning)
        }
        OutlinedButton(
            onClick = {
                if (!canEditStreamSetup) return@OutlinedButton
                focusManager.clearFocus()
                followSavedPin()
                onAccessChange { withGeneratedPin() }
            },
            enabled = canEditStreamSetup,
        ) {
            Text(stringResource(PinSearch.regenerateLabelRes))
        }
    }
}
