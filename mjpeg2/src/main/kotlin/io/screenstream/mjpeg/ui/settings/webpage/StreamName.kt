package io.screenstream.mjpeg.ui.settings.webpage

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.maxTextLength
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

private const val MAXIMUM_STREAM_NAME_LENGTH = 64

internal object StreamNameSearch {
    val fieldLabelRes: Int = R.string.mjpeg_pref_web_stream_name_label
    val enabledLabelRes: Int = R.string.mjpeg_pref_web_stream_name_enabled
    val helpRes: Int = R.string.mjpeg_pref_web_stream_name_help

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.WebPage,
        titleRes = R.string.mjpeg_pref_web_stream_name,
        summaryRes = R.string.mjpeg_pref_web_stream_name_summary,
        extraTermResIds = listOf(fieldLabelRes, enabledLabelRes, helpRes),
    )
}

@Composable
internal fun StreamNameSettingItem(
    streamName: String,
    nameEnabled: Boolean,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(StreamNameSearch.info.titleRes),
        summary = stringResource(StreamNameSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_badge,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        valueContent = {
            Text(
                if (nameEnabled) {
                    streamName.ifEmpty { stringResource(R.string.mjpeg_pref_web_stream_name_empty) }
                } else {
                    stringResource(R.string.mjpeg_pref_value_off)
                },
                color = if (nameEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            val switchInteractions = remember { MutableInteractionSource() }
            val enabledLabel = stringResource(StreamNameSearch.enabledLabelRes)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                            value = nameEnabled,
                            role = Role.Switch,
                            interactionSource = switchInteractions,
                            indication = null,
                            onValueChange = { onWebPageChange { copy(titleEnabled = !this.titleEnabled) } },
                        )
                        .semantics { contentDescription = enabledLabel },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    SettingSwitch(
                        checked = nameEnabled,
                        interactionSource = switchInteractions,
                        compact = true,
                    )
                }
            }
        },
    )
    if (showEditor) {
        StreamNameEditor(streamName, onWebPageChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun StreamNameEditor(
    streamName: String,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialName = retain { streamName }
    var nameInput by retain { mutableStateOf(TextFieldValue(initialName)) }
    val focusManager = LocalFocusManager.current
    val errorText = if (nameInput.text.length > MAXIMUM_STREAM_NAME_LENGTH) {
        stringResource(R.string.mjpeg_pref_web_stream_name_error, MAXIMUM_STREAM_NAME_LENGTH)
    } else null

    fun closeEditor() {
        val name = nameInput.text
        if (name != initialName && name.length <= MAXIMUM_STREAM_NAME_LENGTH) onWebPageChange { copy(title = name) }
        onDismiss()
    }

    SettingEditor(
        title = stringResource(StreamNameSearch.info.titleRes),
        description = stringResource(StreamNameSearch.info.summaryRes),
        onDismiss = ::closeEditor,
    ) {
        OutlinedTextField(
            value = nameInput,
            onValueChange = { next ->
                val textChanged = next.text != nameInput.text
                if (textChanged && next.text.length > MAXIMUM_STREAM_NAME_LENGTH && next.text.length >= nameInput.text.length) {
                    return@OutlinedTextField
                }
                nameInput = next
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    maxTextLength = MAXIMUM_STREAM_NAME_LENGTH
                    if (errorText != null) error(errorText)
                },
            label = { Text(stringResource(StreamNameSearch.fieldLabelRes)) },
            singleLine = true,
            isError = errorText != null,
            supportingText = if (errorText != null) {
                { Text(errorText) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        SettingInfo(stringResource(StreamNameSearch.helpRes))
    }
}
