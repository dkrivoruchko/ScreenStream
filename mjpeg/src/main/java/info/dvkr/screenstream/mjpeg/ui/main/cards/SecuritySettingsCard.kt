package info.dvkr.screenstream.mjpeg.ui.main.cards

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.MjpegSettingModal
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.AutoChangePinRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.BlockAddressRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.EnablePinRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.HidePinOnStartRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.NewPinOnAppStartRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.PinEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.security.PinRow

@Composable
internal fun SecuritySettingsCard(
    settings: MjpegSettings.Data,
    isStreaming: Boolean,
    updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    var selectedSheet by rememberSaveable { mutableStateOf<SecuritySettingSheet?>(null) }
    val pinEditorEnabled = settings.enablePin && settings.newPinOnAppStart.not() && settings.autoChangePin.not()
    val expanded = rememberSaveable { mutableStateOf(false) }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.mjpeg_pref_settings_security),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        EnablePinRow(settings.enablePin) { value ->
            if (settings.enablePin != value) updateSettings { copy(enablePin = value) }
        }
        HorizontalDivider()

        HidePinOnStartRow(
            hidePinOnStart = settings.hidePinOnStart,
            enablePin = settings.enablePin
        ) { value ->
            if (settings.hidePinOnStart != value) updateSettings { copy(hidePinOnStart = value) }
        }
        HorizontalDivider()

        NewPinOnAppStartRow(
            newPinOnAppStart = settings.newPinOnAppStart,
            enablePin = settings.enablePin
        ) { value ->
            if (settings.newPinOnAppStart != value) updateSettings { copy(newPinOnAppStart = value) }
        }
        HorizontalDivider()

        AutoChangePinRow(
            autoChangePin = settings.autoChangePin,
            enablePin = settings.enablePin
        ) { value ->
            if (settings.autoChangePin != value) updateSettings { copy(autoChangePin = value) }
        }
        HorizontalDivider()

        PinRow(
            pin = settings.pin,
            enabled = pinEditorEnabled,
            isPinVisible = isStreaming.not(),
        ) {
            if (pinEditorEnabled) selectedSheet = SecuritySettingSheet.Pin
        }
        HorizontalDivider()

        BlockAddressRow(
            blockAddress = settings.blockAddress,
            enablePin = settings.enablePin
        ) { value ->
            if (settings.blockAddress != value) updateSettings { copy(blockAddress = value) }
        }

        selectedSheet?.let { sheet ->
            MjpegSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                when (sheet) {
                    SecuritySettingSheet.Pin -> PinEditor(settings.pin) { value ->
                        if (settings.pin != value) updateSettings { copy(pin = value) }
                    }
                }
            }
        }
    }
}

private enum class SecuritySettingSheet(@get:StringRes val titleRes: Int) {
    Pin(R.string.mjpeg_pref_set_pin)
}
