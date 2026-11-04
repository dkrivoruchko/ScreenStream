package io.screenstream.mjpeg.ui.settings.images

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.layout.WindowMetricsCalculator
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.SourceRegion
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingInfo
import io.screenstream.mjpeg.ui.settings.SettingNumberField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.acceptsSettingNumberInput
import io.screenstream.mjpeg.ui.settings.parseSettingDecimal
import io.screenstream.mjpeg.ui.settings.parseSettingInteger
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import kotlin.math.floor

private const val MAXIMUM_SCALE_PERCENT = 200
private const val MAXIMUM_DIMENSION_PIXELS = 8192

internal object SizeSearch {
    val modeLabelResIds = mapOf(
        ImageSettings.OutputSelection.Scale to R.string.mjpeg_pref_image_size_scale,
        ImageSettings.OutputSelection.TargetSize to R.string.mjpeg_pref_image_size_target,
    )
    val scaleLabelRes = R.string.mjpeg_pref_image_size_scale_label
    val widthLabelRes = R.string.mjpeg_pref_image_size_width_label
    val heightLabelRes = R.string.mjpeg_pref_image_size_height_label
    val stretchLabelRes = R.string.mjpeg_pref_image_size_stretch

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Image,
        titleRes = R.string.mjpeg_pref_image_size,
        summaryRes = R.string.mjpeg_pref_image_size_summary,
        extraTermResIds = modeLabelResIds.values.toList() + listOf(scaleLabelRes, widthLabelRes, heightLabelRes, stretchLabelRes),
    )
}

@Composable
internal fun SizeSettingItem(
    imageSettings: ImageSettings,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
) {
    val numberFormat = rememberSettingNumberFormat()
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    val valueText = when (imageSettings.outputSelection) {
        ImageSettings.OutputSelection.Scale -> stringResource(
            R.string.mjpeg_pref_image_size_scale_value,
            numberFormat.format(imageSettings.scalePercent),
        )

        ImageSettings.OutputSelection.TargetSize -> imageSettings.targetSize?.let {
            stringResource(R.string.mjpeg_pref_image_size_value, it.widthPx, it.heightPx)
        } ?: stringResource(R.string.mjpeg_pref_value_not_set)
    }
    SettingsItem(
        title = stringResource(SizeSearch.info.titleRes),
        summary = stringResource(SizeSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_resize,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Column(Modifier.widthIn(max = 128.dp), horizontalAlignment = Alignment.End) {
                Text(
                    valueText,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (imageSettings.outputSelection == ImageSettings.OutputSelection.TargetSize &&
                    imageSettings.targetSize?.contentMode == OutputSize.ContentMode.Stretch
                ) {
                    Text(
                        stringResource(R.string.mjpeg_pref_image_size_stretch_value),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
    if (showEditor) {
        SizeEditor(imageSettings, onImageChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
private fun SizeEditor(
    imageSettings: ImageSettings,
    onImageChange: (ImageSettings.() -> ImageSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentLocale = LocalConfiguration.current.locales[0]
    val inputLocale = retain { currentLocale }
    val numberFormat = rememberSettingNumberFormat(inputLocale)
    val labelAbove = LocalDensity.current.fontScale > 1f
    val context = LocalContext.current
    val initialImageSettings = retain { imageSettings }
    val configuration = LocalConfiguration.current
    val screenSize = remember(context, configuration) {
        val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds
        IntSize(bounds.width(), bounds.height())
    }
    val initialWidthPixels = initialImageSettings.targetSize?.widthPx
        ?: screenSize.width.takeIf { it > 0 && screenSize.height > 0 }?.let(ImageSettings::uiDefaultTargetDimension)
    val initialHeightPixels = initialImageSettings.targetSize?.heightPx
        ?: screenSize.height.takeIf { it > 0 && screenSize.width > 0 }?.let(ImageSettings::uiDefaultTargetDimension)
    var outputSelection by retain { mutableStateOf(initialImageSettings.outputSelection) }
    var scaleInput by retain { mutableStateOf(TextFieldValue(numberFormat.format(initialImageSettings.scalePercent))) }
    var widthInput by retain { mutableStateOf(TextFieldValue(initialWidthPixels?.let { numberFormat.format(it) }.orEmpty())) }
    var heightInput by retain { mutableStateOf(TextFieldValue(initialHeightPixels?.let { numberFormat.format(it) }.orEmpty())) }
    var widthInputEdited by retain { mutableStateOf(false) }
    var heightInputEdited by retain { mutableStateOf(false) }
    var contentMode by retain { mutableStateOf(initialImageSettings.targetSize?.contentMode ?: ImageSettings.UI_DEFAULT_TARGET_CONTENT_MODE) }

    fun parseScalePercent(text: String): Double? = parseSettingDecimal(text, numberFormat)?.takeIf {
        it / 100.0 > 0.0 && acceptsSettingNumberInput(text, numberFormat, allowDecimal = true, maximumValue = MAXIMUM_SCALE_PERCENT)
    }

    fun parseDimensionPixels(text: String): Int? =
        parseSettingInteger(text, numberFormat)?.takeIf { it in 1..MAXIMUM_DIMENSION_PIXELS }

    val parsedScalePercent = parseScalePercent(scaleInput.text)
    val parsedWidthPixels = parseDimensionPixels(widthInput.text)
    val parsedHeightPixels = parseDimensionPixels(heightInput.text)
    val outputSizePolicy = when (outputSelection) {
        ImageSettings.OutputSelection.Scale -> parsedScalePercent?.let { OutputSize.ScaleFactor(it / 100.0) }
        ImageSettings.OutputSelection.TargetSize -> if (parsedWidthPixels != null && parsedHeightPixels != null) {
            OutputSize.TargetSize(parsedWidthPixels, parsedHeightPixels, contentMode)
        } else null
    }
    val estimatedImageSize = estimateImageSize(screenSize, imageSettings, outputSizePolicy)
    val showEnlargementWarning = when (outputSelection) {
        ImageSettings.OutputSelection.Scale -> parsedScalePercent != null && parsedScalePercent > 100.0
        ImageSettings.OutputSelection.TargetSize -> {
            val sourceSize = estimateImageSize(screenSize, imageSettings, OutputSize.ScaleFactor(1.0))
            estimatedImageSize != null && sourceSize != null &&
                    estimatedImageSize.width.toLong() * estimatedImageSize.height > sourceSize.width.toLong() * sourceSize.height
        }
    }

    fun saveSizeIfChanged() {
        when (outputSelection) {
            ImageSettings.OutputSelection.Scale -> {
                val percent = parseScalePercent(scaleInput.text) ?: return
                if (initialImageSettings.outputSelection != outputSelection || initialImageSettings.scalePercent != percent) {
                    onImageChange { copy(outputSelection = ImageSettings.OutputSelection.Scale, scalePercent = percent) }
                }
            }

            ImageSettings.OutputSelection.TargetSize -> {
                val widthPixels = parseDimensionPixels(widthInput.text) ?: return
                val heightPixels = parseDimensionPixels(heightInput.text) ?: return
                val target = OutputSize.TargetSize(widthPixels, heightPixels, contentMode)
                if (initialImageSettings.outputSelection != outputSelection || initialImageSettings.targetSize != target) {
                    onImageChange { copy(outputSelection = ImageSettings.OutputSelection.TargetSize, targetSize = target) }
                }
            }
        }
    }

    SettingEditor(
        title = stringResource(SizeSearch.info.titleRes),
        description = stringResource(SizeSearch.info.summaryRes),
        onDismiss = {
            saveSizeIfChanged()
            onDismiss()
        },
    ) {
        Text(
            stringResource(
                R.string.mjpeg_pref_image_size_default,
                stringResource(SizeSearch.modeLabelResIds.getValue(ImageSettings.DEFAULT_OUTPUT_SELECTION)),
                when (ImageSettings.DEFAULT_OUTPUT_SELECTION) {
                    ImageSettings.OutputSelection.Scale -> stringResource(
                        R.string.mjpeg_pref_image_size_scale_value,
                        numberFormat.format(ImageSettings.DEFAULT_SCALE_PERCENT),
                    )

                    ImageSettings.OutputSelection.TargetSize -> ImageSettings.DEFAULT_TARGET_SIZE?.let {
                        stringResource(R.string.mjpeg_pref_image_size_value, it.widthPx, it.heightPx)
                    } ?: stringResource(R.string.mjpeg_pref_value_not_set)
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            SizeSearch.modeLabelResIds.entries.forEachIndexed { index, (mode, labelRes) ->
                SegmentedButton(
                    selected = outputSelection == mode,
                    onClick = { outputSelection = mode },
                    shape = SegmentedButtonDefaults.itemShape(index, SizeSearch.modeLabelResIds.size),
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        if (outputSelection == ImageSettings.OutputSelection.Scale) {
            val scaleErrorText = stringResource(R.string.mjpeg_pref_image_size_scale_error)
            SettingNumberField(
                value = scaleInput,
                onValueChange = { scaleInput = it },
                inputLocale = inputLocale,
                label = stringResource(SizeSearch.scaleLabelRes),
                labelAbove = labelAbove,
                modifier = Modifier.fillMaxWidth(),
                allowDecimal = true,
                errorText = scaleErrorText.takeIf { parsedScalePercent == null },
                showSupportingError = false,
            )
            if (parsedScalePercent == null) {
                Text(
                    scaleErrorText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            val widthErrorText = if ((widthInputEdited || widthInput.text.isNotEmpty()) && parsedWidthPixels == null) {
                stringResource(R.string.mjpeg_pref_image_size_dimension_error)
            } else null
            val heightErrorText = if ((heightInputEdited || heightInput.text.isNotEmpty()) && parsedHeightPixels == null) {
                stringResource(R.string.mjpeg_pref_image_size_dimension_error)
            } else null
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingNumberField(
                    value = widthInput,
                    inputLocale = inputLocale,
                    label = stringResource(SizeSearch.widthLabelRes),
                    labelAbove = labelAbove,
                    modifier = Modifier.weight(1f),
                    imeAction = ImeAction.Next,
                    errorText = widthErrorText,
                    showSupportingError = false,
                    onValueChange = {
                        if (it.text != widthInput.text) widthInputEdited = true
                        widthInput = it
                    },
                )
                SettingNumberField(
                    value = heightInput,
                    inputLocale = inputLocale,
                    label = stringResource(SizeSearch.heightLabelRes),
                    labelAbove = labelAbove,
                    modifier = Modifier.weight(1f),
                    errorText = heightErrorText,
                    showSupportingError = false,
                    onValueChange = {
                        if (it.text != heightInput.text) heightInputEdited = true
                        heightInput = it
                    },
                )
            }
            if (widthErrorText != null || heightErrorText != null) {
                Text(
                    stringResource(R.string.mjpeg_pref_image_size_dimension_error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            val isStretch = contentMode == OutputSize.ContentMode.Stretch
            ListItem(
                verticalAlignment = Alignment.CenterVertically,
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
                onClick = {
                    contentMode = if (contentMode == OutputSize.ContentMode.Stretch) OutputSize.ContentMode.AspectFit else OutputSize.ContentMode.Stretch
                },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = if (isStretch) ToggleableState.On else ToggleableState.Off
                },
                supportingContent = {
                    Text(
                        stringResource(
                            if (isStretch) {
                                R.string.mjpeg_pref_image_size_stretch_description
                            } else {
                                R.string.mjpeg_pref_image_size_fit_description
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = { SettingSwitch(checked = isStretch) },
                content = { Text(stringResource(SizeSearch.stretchLabelRes), style = MaterialTheme.typography.titleMedium) },
            )
        }
        if (showEnlargementWarning) {
            SettingInfo(stringResource(R.string.mjpeg_pref_image_size_enlargement_warning), iconRes = R.drawable.mjpeg_icon_warning)
        }
        val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
        val stackSizeValues = windowWidthDp < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SizeSummary(
                label = stringResource(R.string.mjpeg_pref_image_size_source, "").trimEnd(),
                value = if (screenSize.width > 0 && screenSize.height > 0) {
                    stringResource(R.string.mjpeg_pref_image_size_value, screenSize.width, screenSize.height)
                } else stringResource(R.string.mjpeg_pref_image_size_estimate_unavailable),
                stacked = stackSizeValues,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            SizeSummary(
                label = stringResource(R.string.mjpeg_pref_image_size_result, "").trimEnd(),
                value = estimatedImageSize?.let {
                    stringResource(R.string.mjpeg_pref_image_size_value, it.width, it.height)
                } ?: stringResource(R.string.mjpeg_pref_image_size_estimate_unavailable),
                stacked = stackSizeValues,
                modifier = Modifier.weight(1f),
            )
        }
        SettingInfo(stringResource(R.string.mjpeg_pref_image_size_estimate_note), centered = true)
    }
}

@Composable
private fun SizeSummary(label: String, value: String, stacked: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        maxItemsInEachRow = if (stacked) 1 else 2,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            value,
            modifier = Modifier.width(IntrinsicSize.Max),
            style = MaterialTheme.typography.bodyMedium,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

private fun estimateImageSize(screenSize: IntSize, imageSettings: ImageSettings, outputSizePolicy: OutputSize?): IntSize? {
    if (screenSize.width <= 0 || screenSize.height <= 0 || outputSizePolicy == null) return null
    if (imageSettings.sourceRegion != SourceRegion.Full && screenSize.width < 2) return null
    val regionWidth = when (imageSettings.sourceRegion) {
        SourceRegion.Full -> screenSize.width
        SourceRegion.LeftHalf -> screenSize.width / 2
        SourceRegion.RightHalf -> screenSize.width - screenSize.width / 2
    }
    val cropInsets = if (imageSettings.cropEnabled) imageSettings.cropInsets else CropInsetsPx.ZERO
    val croppedWidth = regionWidth.toLong() - cropInsets.left - cropInsets.right
    val croppedHeight = screenSize.height.toLong() - cropInsets.top - cropInsets.bottom
    if (croppedWidth <= 0 || croppedHeight <= 0) return null
    val swapsDimensions = imageSettings.rotation == Rotation.Degrees90 || imageSettings.rotation == Rotation.Degrees270
    val orientedWidth = if (swapsDimensions) croppedHeight else croppedWidth
    val orientedHeight = if (swapsDimensions) croppedWidth else croppedHeight
    return when (outputSizePolicy) {
        is OutputSize.ScaleFactor -> {
            val roundedWidth = floor(orientedWidth.toDouble() * outputSizePolicy.factor + 0.5)
            val roundedHeight = floor(orientedHeight.toDouble() * outputSizePolicy.factor + 0.5)
            if (roundedWidth !in 0.0..Int.MAX_VALUE.toDouble() || roundedHeight !in 0.0..Int.MAX_VALUE.toDouble()) return null
            IntSize(roundedWidth.toInt().coerceAtLeast(1), roundedHeight.toInt().coerceAtLeast(1))
        }

        is OutputSize.TargetSize -> when (outputSizePolicy.contentMode) {
            OutputSize.ContentMode.Stretch -> IntSize(outputSizePolicy.widthPx, outputSizePolicy.heightPx)
            OutputSize.ContentMode.AspectFit -> {
                val targetWidth = outputSizePolicy.widthPx
                val targetHeight = outputSizePolicy.heightPx
                val widthProduct = targetWidth.toLong() * orientedHeight
                val heightProduct = targetHeight.toLong() * orientedWidth
                if (widthProduct <= heightProduct) {
                    val fittedHeight = ((widthProduct + orientedWidth / 2) / orientedWidth).coerceIn(1L, targetHeight.toLong())
                    IntSize(targetWidth, fittedHeight.toInt())
                } else {
                    val fittedWidth = ((heightProduct + orientedHeight / 2) / orientedHeight).coerceIn(1L, targetWidth.toLong())
                    IntSize(fittedWidth.toInt(), targetHeight)
                }
            }
        }
    }
}
