package info.dvkr.screenstream.mjpeg.ui.main.cards

import android.os.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.settings.common.MjpegSettingModal
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlBackColorEditor
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlBackColorRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlEnableButtonsRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlFitWindowRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlKeepImageOnReconnectRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.HtmlShowPressStartRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.KeepAwakeRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.NotifySlowConnectionsRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.StopOnConfigurationChangeRow
import info.dvkr.screenstream.mjpeg.ui.main.settings.general.StopOnSleepRow

@Composable
internal fun GeneralSettingsCard(
    settings: MjpegSettings.Data,
    updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
) {
    var selectedSheet by rememberSaveable { mutableStateOf<GeneralSettingSheet?>(null) }
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
                    text = stringResource(R.string.mjpeg_pref_settings_general),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier
    ) {
        if (Build.MANUFACTURER !in listOf("OnePlus", "OPPO")) {
            KeepAwakeRow(settings.keepAwake) { newValue ->
                updateSettings { copy(keepAwake = newValue) }
            }
            HorizontalDivider()
        }

        StopOnSleepRow(settings.stopOnSleep) { newValue ->
            updateSettings { copy(stopOnSleep = newValue) }
        }
        HorizontalDivider()

        StopOnConfigurationChangeRow(settings.stopOnConfigurationChange) { newValue ->
            updateSettings { copy(stopOnConfigurationChange = newValue) }
        }
        HorizontalDivider()

        NotifySlowConnectionsRow(settings.notifySlowConnections) { newValue ->
            updateSettings { copy(notifySlowConnections = newValue) }
        }
        HorizontalDivider()

        HtmlEnableButtonsRow(
            htmlEnableButtons = settings.htmlEnableButtons,
            enablePin = settings.enablePin
        ) { newValue ->
            updateSettings { copy(htmlEnableButtons = newValue) }
        }
        HorizontalDivider()

        HtmlShowPressStartRow(settings.htmlShowPressStart) { newValue ->
            updateSettings { copy(htmlShowPressStart = newValue) }
        }
        HorizontalDivider()

        HtmlBackColorRow(
            htmlBackColor = Color(settings.htmlBackColor)
        ) { selectedSheet = GeneralSettingSheet.HtmlBackColor }
        HorizontalDivider()

        HtmlFitWindowRow(settings.htmlFitWindow) { newValue ->
            updateSettings { copy(htmlFitWindow = newValue) }
        }
        HorizontalDivider()

        HtmlKeepImageOnReconnectRow(settings.htmlKeepImageOnReconnect) { newValue ->
            updateSettings { copy(htmlKeepImageOnReconnect = newValue) }
        }

        selectedSheet?.let { sheet ->
            MjpegSettingModal(
                windowWidthSizeClass = windowWidthSizeClass,
                title = stringResource(sheet.titleRes),
                onDismissRequest = { selectedSheet = null }
            ) {
                when (sheet) {
                    GeneralSettingSheet.HtmlBackColor -> HtmlBackColorEditor(
                        htmlBackColor = Color(settings.htmlBackColor),
                        onColorChange = { value ->
                            if (settings.htmlBackColor != value.toArgb()) {
                                updateSettings { copy(htmlBackColor = value.toArgb()) }
                            }
                        }
                    )
                }
            }
        }
    }
}

private enum class GeneralSettingSheet(@get:StringRes val titleRes: Int) {
    HtmlBackColor(R.string.mjpeg_pref_html_back_color)
}
