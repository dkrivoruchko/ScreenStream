package io.screenstream.mjpeg.ui.settings.connection

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
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingNumberField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus

internal object HttpPortSearch {
    val fieldLabelRes: Int = R.string.mjpeg_pref_connection_http_port_label

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Connection,
        titleRes = R.string.mjpeg_pref_connection_http_port,
        summaryRes = R.string.mjpeg_pref_connection_http_port_summary,
        extraTermResIds = listOf(fieldLabelRes),
    )
}

@Composable
internal fun HttpPortSettingItem(
    httpPort: Int,
    canEditStreamSetup: Boolean,
    onNetworkChange: (NetworkSettings.() -> NetworkSettings) -> Unit,
) {
    val numberFormat = rememberSettingNumberFormat()
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(HttpPortSearch.info.titleRes),
        summary = stringResource(HttpPortSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_settings_ethernet,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Text(
                numberFormat.format(httpPort),
                Modifier.widthIn(max = 128.dp),
                color = if (canEditStreamSetup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
    if (showEditor) {
        HttpPortEditor(httpPort, canEditStreamSetup, onNetworkChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun HttpPortEditor(
    httpPort: Int,
    canEditStreamSetup: Boolean,
    onNetworkChange: (NetworkSettings.() -> NetworkSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val initialPort = retain { httpPort }
    var portInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialPort))) }
    val errorText = if (parseSettingInteger(portInput.text, numberFormat) !in 1024..65535) {
        stringResource(R.string.mjpeg_pref_connection_http_port_error)
    } else null

    SettingEditor(
        title = stringResource(HttpPortSearch.info.titleRes),
        description = stringResource(HttpPortSearch.info.summaryRes),
        onDismiss = {
            val port = parseSettingInteger(portInput.text, numberFormat)?.takeIf { it in 1024..65535 }
            if (canEditStreamSetup && port != null && port != initialPort) {
                onNetworkChange { copy(httpPort = port) }
            }
            onDismiss()
        },
    ) {
        Text(
            stringResource(R.string.mjpeg_pref_default_value, numberFormat.format(NetworkSettings.DEFAULT_HTTP_PORT)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SettingNumberField(
            value = portInput,
            onValueChange = { portInput = it },
            label = stringResource(HttpPortSearch.fieldLabelRes),
            errorText = errorText,
            inputLocale = inputLocale,
            modifier = Modifier.fillMaxWidth(),
            enabled = canEditStreamSetup,
        )
        SettingInfo(stringResource(if (canEditStreamSetup) R.string.mjpeg_pref_connection_http_port_help else R.string.mjpeg_pref_setup_locked))
    }
}
