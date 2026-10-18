package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat

internal object LimitPinAttemptsSearch {
    val durationLabelRes: Int = R.string.mjpeg_pref_access_wrong_pins_label
    val helpRes: Int = R.string.mjpeg_pref_access_wrong_pins_help

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Access,
        titleRes = R.string.mjpeg_pref_access_wrong_pins,
        summaryRes = R.string.mjpeg_pref_access_wrong_pins_summary,
        extraTermResIds = listOf(durationLabelRes, helpRes),
    )
}

@Composable
internal fun LimitPinAttemptsSettingItem(
    limitWrongPins: Boolean,
    blockMinutes: Int,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocusRequester = remember { FocusRequester() }
    var shouldRestoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(showEditor) {
        if (!showEditor && shouldRestoreFocus) {
            openerFocusRequester.requestFocus()
            shouldRestoreFocus = false
        }
    }
    SettingsItem(
        title = stringResource(LimitPinAttemptsSearch.info.titleRes),
        summary = stringResource(LimitPinAttemptsSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_block,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocusRequester),
        valueContent = {
            Text(
                stringResource(R.string.mjpeg_pref_access_wrong_pins_value, blockMinutes),
                color = if (canEditStreamSetup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            val switchInteractions = remember { MutableInteractionSource() }
            val enabledLabel = stringResource(LimitPinAttemptsSearch.info.titleRes)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.mjpeg_icon_chevron),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                VerticalDivider(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .toggleable(
                            value = limitWrongPins,
                            enabled = canEditStreamSetup,
                            role = Role.Switch,
                            interactionSource = switchInteractions,
                            indication = null,
                            onValueChange = { if (canEditStreamSetup) onAccessChange { copy(limitWrongPins = !this.limitWrongPins) } },
                        )
                        .semantics { contentDescription = enabledLabel },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SettingSwitch(
                        checked = limitWrongPins,
                        enabled = canEditStreamSetup,
                        interactionSource = switchInteractions,
                        compact = true,
                    )
                }
            }
        },
    )
    if (showEditor) {
        LimitPinAttemptsEditor(blockMinutes, canEditStreamSetup, onAccessChange) {
            showEditor = false
            shouldRestoreFocus = true
        }
    }
}

@Composable
private fun LimitPinAttemptsEditor(
    blockMinutes: Int,
    canEditStreamSetup: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val focusManager = LocalFocusManager.current
    val initialBlockMinutes = retain { blockMinutes }
    var durationInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialBlockMinutes))) }
    val errorText = if (parseSettingInteger(durationInput.text, numberFormat) !in 1..60) {
        stringResource(R.string.mjpeg_pref_access_wrong_pins_error)
    } else null

    SettingEditor(
        title = stringResource(LimitPinAttemptsSearch.info.titleRes),
        description = stringResource(LimitPinAttemptsSearch.info.summaryRes),
        onDismiss = {
            val minutes = parseSettingInteger(durationInput.text, numberFormat)?.takeIf { it in 1..60 }
            if (canEditStreamSetup && minutes != null && minutes != initialBlockMinutes && minutes != blockMinutes) {
                onAccessChange { copy(blockMinutes = minutes) }
            }
            onDismiss()
        },
    ) {
        Text(
            stringResource(R.string.mjpeg_pref_access_wrong_pins_default, numberFormat.format(1)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = durationInput,
            onValueChange = { next ->
                if (!canEditStreamSetup) return@OutlinedTextField
                if (next.text != durationInput.text && next.text.any { !it.isDigit() }) return@OutlinedTextField

                durationInput = next
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (errorText != null) error(errorText) },
            enabled = canEditStreamSetup,
            label = { Text(stringResource(LimitPinAttemptsSearch.durationLabelRes)) },
            singleLine = true,
            isError = errorText != null,
            supportingText = if (errorText != null) {
                { Text(errorText) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        SettingInfo(stringResource(LimitPinAttemptsSearch.helpRes))
        if (!canEditStreamSetup) {
            Text(
                stringResource(R.string.mjpeg_pref_setup_locked),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
