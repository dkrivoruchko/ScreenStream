package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingNumberField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSearchOptionText
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.parseSettingDecimal
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import io.screenstream.capture.FrameRate as CaptureFrameRate

internal object FrameRateSearch {
    val automaticOption = SettingSearchOptionText(R.string.mjpeg_pref_value_automatic, R.string.mjpeg_pref_image_rate_auto_description)
    val fpsLimitOption = SettingSearchOptionText(R.string.mjpeg_pref_image_rate_fps, R.string.mjpeg_pref_image_rate_fps_description)
    val intervalOption = SettingSearchOptionText(R.string.mjpeg_pref_image_rate_interval, R.string.mjpeg_pref_image_rate_interval_description)
    val fpsFieldLabelRes: Int = R.string.mjpeg_pref_image_rate_fps_label
    val intervalFieldLabelRes: Int = R.string.mjpeg_pref_image_rate_interval_label
    val fpsHelpRes: Int = R.string.mjpeg_pref_image_rate_fps_help
    val intervalHelpRes: Int = R.string.mjpeg_pref_image_rate_interval_help

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_rate,
        summaryRes = R.string.mjpeg_pref_image_rate_summary,
        extraTermResIds = listOf(automaticOption, fpsLimitOption, intervalOption).flatMap { listOf(it.labelRes, it.descriptionRes) } +
                listOf(fpsFieldLabelRes, intervalFieldLabelRes, fpsHelpRes, intervalHelpRes),
    )
}

@Composable
internal fun FrameRateSettingItem(
    rate: CaptureFrameRate,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    val numberFormat = rememberSettingNumberFormat()
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    val valueText = when (rate) {
        CaptureFrameRate.Auto -> stringResource(FrameRateSearch.automaticOption.labelRes)
        is CaptureFrameRate.MaxFps -> stringResource(R.string.mjpeg_pref_image_rate_fps_value, rate.fps)
        is CaptureFrameRate.SamplingInterval -> stringResource(
            R.string.mjpeg_pref_image_rate_interval_value,
            numberFormat.format(rate.interval.toDouble(DurationUnit.SECONDS)),
        )
    }
    SettingsItem(
        title = stringResource(FrameRateSearch.info.titleRes),
        summary = stringResource(FrameRateSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_burst_mode,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Text(
                valueText,
                Modifier.widthIn(max = 128.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
    if (showEditor) {
        FrameRateEditor(rate, onImageChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun FrameRateEditor(
    rate: CaptureFrameRate,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val initialFrameRate = retain { rate }
    // Only the variant matters here; numeric values live in the input drafts.
    var selectedFrameRate by retain { mutableStateOf(initialFrameRate) }
    var fpsInput by retain {
        mutableStateOf(TextFieldValue(numberFormat.format((initialFrameRate as? CaptureFrameRate.MaxFps)?.fps ?: 30)))
    }
    var intervalInput by retain {
        mutableStateOf(
            TextFieldValue(
                (initialFrameRate as? CaptureFrameRate.SamplingInterval)?.let {
                    numberFormat.format(it.interval.toDouble(DurationUnit.SECONDS))
                } ?: numberFormat.format(1)
            )
        )
    }
    var intervalInputEdited by retain { mutableStateOf(false) }

    val parsedFps = parseSettingInteger(fpsInput.text, numberFormat)
    val fpsErrorText = if (selectedFrameRate is CaptureFrameRate.MaxFps && parsedFps !in 1..120) {
        stringResource(R.string.mjpeg_pref_image_rate_fps_error)
    } else null
    val parsedIntervalSeconds = parseSettingDecimal(intervalInput.text, numberFormat)
    val intervalErrorText = if (selectedFrameRate is CaptureFrameRate.SamplingInterval && intervalInputEdited &&
        (parsedIntervalSeconds == null || parsedIntervalSeconds !in 1.0..3600.0)
    ) {
        stringResource(R.string.mjpeg_pref_image_rate_interval_error)
    } else null

    fun saveRateIfChanged() {
        val nextRate = when (selectedFrameRate) {
            CaptureFrameRate.Auto -> CaptureFrameRate.Auto
            is CaptureFrameRate.MaxFps -> parseSettingInteger(fpsInput.text, numberFormat)
                ?.takeIf { it in 1..120 }
                ?.let { CaptureFrameRate.MaxFps(it) }

            is CaptureFrameRate.SamplingInterval -> parseSettingDecimal(intervalInput.text, numberFormat)
                ?.takeIf { it in 1.0..3600.0 }
                ?.let { CaptureFrameRate.SamplingInterval(it.seconds) }
        } ?: return
        if (nextRate != initialFrameRate) onImageChange { copy(frameRate = nextRate) }
    }

    SettingEditor(
        title = stringResource(FrameRateSearch.info.titleRes),
        description = stringResource(FrameRateSearch.info.summaryRes),
        onDismiss = {
            saveRateIfChanged()
            onDismiss()
        },
    ) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            SegmentedButton(
                selected = selectedFrameRate == CaptureFrameRate.Auto,
                onClick = { selectedFrameRate = CaptureFrameRate.Auto },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
                label = { Text(stringResource(R.string.mjpeg_pref_default_option, stringResource(FrameRateSearch.automaticOption.labelRes))) },
            )
            SegmentedButton(
                selected = selectedFrameRate is CaptureFrameRate.MaxFps,
                onClick = { selectedFrameRate = CaptureFrameRate.MaxFps(30) },
                shape = SegmentedButtonDefaults.itemShape(1, 3),
                label = { Text(stringResource(FrameRateSearch.fpsLimitOption.labelRes)) },
            )
            SegmentedButton(
                selected = selectedFrameRate is CaptureFrameRate.SamplingInterval,
                onClick = { selectedFrameRate = CaptureFrameRate.SamplingInterval(1.seconds) },
                shape = SegmentedButtonDefaults.itemShape(2, 3),
                label = { Text(stringResource(FrameRateSearch.intervalOption.labelRes)) },
            )
        }
        val selectedOption = when (selectedFrameRate) {
            CaptureFrameRate.Auto -> FrameRateSearch.automaticOption
            is CaptureFrameRate.MaxFps -> FrameRateSearch.fpsLimitOption
            is CaptureFrameRate.SamplingInterval -> FrameRateSearch.intervalOption
        }
        if (selectedFrameRate == CaptureFrameRate.Auto) {
            SettingInfo(stringResource(selectedOption.descriptionRes))
        } else {
            Text(
                stringResource(selectedOption.descriptionRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (selectedFrameRate) {
            CaptureFrameRate.Auto -> Unit
            is CaptureFrameRate.MaxFps -> SettingNumberField(
                value = fpsInput,
                inputLocale = inputLocale,
                label = stringResource(FrameRateSearch.fpsFieldLabelRes),
                modifier = Modifier.fillMaxWidth(),
                maximumValue = 120,
                errorText = fpsErrorText,
                onValueChange = { fpsInput = it },
            )

            is CaptureFrameRate.SamplingInterval -> SettingNumberField(
                value = intervalInput,
                inputLocale = inputLocale,
                label = stringResource(FrameRateSearch.intervalFieldLabelRes),
                modifier = Modifier.fillMaxWidth(),
                maximumValue = 3600,
                allowDecimal = true,
                errorText = intervalErrorText,
                onValueChange = {
                    if (it.text != intervalInput.text) intervalInputEdited = true
                    intervalInput = it
                },
            )
        }
        val helpRes = when (selectedFrameRate) {
            CaptureFrameRate.Auto -> null
            is CaptureFrameRate.MaxFps -> FrameRateSearch.fpsHelpRes
            is CaptureFrameRate.SamplingInterval -> FrameRateSearch.intervalHelpRes
        }
        if (helpRes != null) {
            SettingInfo(stringResource(helpRes))
        }
    }
}
