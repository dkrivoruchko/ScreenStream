package info.dvkr.screenstream.vnc.ui.main.cards

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
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.main.settings.common.VncSettingModal
import info.dvkr.screenstream.vnc.ui.main.settings.encoding.MaxFpsEditor
import info.dvkr.screenstream.vnc.ui.main.settings.encoding.MaxFpsRow
import info.dvkr.screenstream.vnc.ui.main.settings.encoding.ScaleFactorEditor
import info.dvkr.screenstream.vnc.ui.main.settings.encoding.ScaleFactorRow
import info.dvkr.screenstream.vnc.ui.main.settings.encoding.ZlibEncodingRow

@Composable
internal fun EncodingCard(
    settings: VncSettings.Data,
    updateSettings: (VncSettings.Data.() -> VncSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var selectedSheet by rememberSaveable { mutableStateOf<EncodingSettingSheet?>(null) }
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
                    text = stringResource(R.string.vnc_encoding_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        MaxFpsRow(
            enabled = enabled,
            maxFPS = settings.maxFPS
        ) { selectedSheet = EncodingSettingSheet.MaxFps }

        HorizontalDivider()

        ZlibEncodingRow(
            enabled = enabled,
            zlibEncoding = settings.zlibEncoding
        ) { newValue ->
            updateSettings {
                copy(zlibEncoding = newValue)
            }
        }

        HorizontalDivider()

        ScaleFactorRow(
            enabled = enabled,
            scaleFactor = settings.scaleFactor
        ) { selectedSheet = EncodingSettingSheet.ScaleFactor }

        selectedSheet?.let { sheet ->
            VncSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                sheet.Editor(settings = settings, updateSettings = updateSettings)
            }
        }
    }
}

private enum class EncodingSettingSheet(@get:StringRes val titleRes: Int) {
    MaxFps(R.string.vnc_pref_max_fps),
    ScaleFactor(R.string.vnc_pref_scale)
}

@Composable
private fun EncodingSettingSheet.Editor(
    settings: VncSettings.Data,
    updateSettings: (VncSettings.Data.() -> VncSettings.Data) -> Unit
) {
    when (this) {
        EncodingSettingSheet.MaxFps -> MaxFpsEditor(
            maxFPS = settings.maxFPS,
            onValueChange = { value ->
                if (settings.maxFPS != value) {
                    updateSettings { copy(maxFPS = value) }
                }
            }
        )

        EncodingSettingSheet.ScaleFactor -> ScaleFactorEditor(
            scaleFactor = settings.scaleFactor,
            onValueChange = { value ->
                if (settings.scaleFactor != value) {
                    updateSettings { copy(scaleFactor = value) }
                }
            }
        )
    }
}
