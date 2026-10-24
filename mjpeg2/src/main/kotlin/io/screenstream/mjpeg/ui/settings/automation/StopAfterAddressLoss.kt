package io.screenstream.mjpeg.ui.settings.automation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingNumberField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object StopAfterAddressLossSearch {
    val fieldLabelRes: Int = R.string.mjpeg_pref_stream_network_loss_timeout_label
    val helpRes: Int = R.string.mjpeg_pref_stream_network_loss_timeout_help

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Automation,
        titleRes = R.string.mjpeg_pref_stream_network_loss_timeout,
        summaryRes = R.string.mjpeg_pref_stream_network_loss_timeout_summary,
        extraTermResIds = listOf(fieldLabelRes, helpRes),
    )
}

@Composable
internal fun StopAfterAddressLossSettingItem(
    timeoutSeconds: Int,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(StopAfterAddressLossSearch.info.titleRes),
        summary = stringResource(StopAfterAddressLossSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_timer,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Text(
                stringResource(R.string.mjpeg_pref_stream_network_loss_timeout_value, timeoutSeconds),
                Modifier.widthIn(max = 128.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
    if (showEditor) {
        StopAfterAddressLossEditor(timeoutSeconds, onBehaviorChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun StopAfterAddressLossEditor(
    timeoutSeconds: Int,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val initialTimeoutSeconds = retain { timeoutSeconds }
    var timeoutInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialTimeoutSeconds))) }
    val errorText = if (parseSettingInteger(timeoutInput.text, numberFormat) !in 10..1800) {
        stringResource(R.string.mjpeg_pref_stream_network_loss_timeout_error)
    } else null

    SettingEditor(
        title = stringResource(StopAfterAddressLossSearch.info.titleRes),
        description = stringResource(StopAfterAddressLossSearch.info.summaryRes),
        onDismiss = {
            val seconds = parseSettingInteger(timeoutInput.text, numberFormat)?.takeIf { it in 10..1800 }
            if (seconds != null && seconds != initialTimeoutSeconds) {
                onBehaviorChange { copy(stopAfterNetworkLossSeconds = seconds) }
            }
            onDismiss()
        },
    ) {
        Text(
            stringResource(
                R.string.mjpeg_pref_stream_network_loss_timeout_default,
                numberFormat.format(StreamBehaviorSettings.DEFAULT_STOP_AFTER_NETWORK_LOSS_SECONDS),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SettingNumberField(
            value = timeoutInput,
            onValueChange = { timeoutInput = it },
            inputLocale = inputLocale,
            label = stringResource(StopAfterAddressLossSearch.fieldLabelRes),
            modifier = Modifier.fillMaxWidth(),
            errorText = errorText,
        )
        SettingInfo(stringResource(StopAfterAddressLossSearch.helpRes))
    }
}
