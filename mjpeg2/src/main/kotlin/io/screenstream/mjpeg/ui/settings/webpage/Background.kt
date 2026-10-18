package io.screenstream.mjpeg.ui.settings.webpage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalGridApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Grid
import androidx.compose.foundation.layout.GridTrackSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.WebPageSettings
import io.screenstream.mjpeg.ui.settings.SettingEditor
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSliderThumb
import io.screenstream.mjpeg.ui.settings.SettingsItem
import io.screenstream.mjpeg.ui.settings.rememberSettingNumberFormat
import io.screenstream.mjpeg.ui.settings.rememberSettingOpenerFocus
import kotlin.math.roundToInt

internal object BackgroundSearch {
    val hexLabelRes: Int = R.string.mjpeg_pref_web_background_hex_label
    val redLabelRes: Int = R.string.mjpeg_pref_web_background_red
    val greenLabelRes: Int = R.string.mjpeg_pref_web_background_green
    val blueLabelRes: Int = R.string.mjpeg_pref_web_background_blue
    val paletteLabelRes: Int = R.string.mjpeg_pref_web_background_palette

    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.WebPage,
        titleRes = R.string.mjpeg_pref_web_background,
        summaryRes = R.string.mjpeg_pref_web_background_summary,
        extraTermResIds = listOf(hexLabelRes, redLabelRes, greenLabelRes, blueLabelRes, paletteLabelRes),
    )
}

private val backgroundPalette = listOf(
    0xF44336, 0xE91E63, 0x9C27B0, 0x673AB7,
    0x3F51B5, 0x2196F3, 0x03A9F4, 0x00BCD4,
    0x009688, 0x4CAF50, 0x8BC34A, 0xCDDC39,
    0xFFEB3B, 0xFFC107, 0xFF9800, 0xFF5722,
    0x795548, 0x9E9E9E, 0x607D8B, 0x000000,
    0xFFFFFF, 0x101418,
)

@Composable
internal fun BackgroundSettingItem(
    background: String,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
) {
    var showEditor by retain { mutableStateOf(false) }
    val openerFocus = rememberSettingOpenerFocus(editorVisible = showEditor)
    SettingsItem(
        title = stringResource(BackgroundSearch.info.titleRes),
        summary = stringResource(BackgroundSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_format_color_fill,
        onClick = { showEditor = true },
        modifier = Modifier.focusRequester(openerFocus.requester),
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    color = Color(0xFF000000.toInt() or background.substring(1).toInt(16)),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    content = {},
                )
                Text(background.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
        },
    )
    if (showEditor) {
        BackgroundEditor(background, onWebPageChange) {
            showEditor = false
            openerFocus.markDismissed()
        }
    }
}

@Composable
@OptIn(ExperimentalGridApi::class)
private fun BackgroundEditor(
    background: String,
    onWebPageChange: (WebPageSettings.() -> WebPageSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val labelColumn = if (isRtl) 2 else 1
    val sliderColumn = if (isRtl) 1 else 2
    val initialRgb = retain { background.substring(1).toInt(16) }
    var previewRgb by retain { mutableIntStateOf(initialRgb) }
    var hexInput by retain { mutableStateOf(TextFieldValue(background.substring(1).uppercase())) }
    val errorText = if (parseRgbColor(hexInput.text) == null) stringResource(R.string.mjpeg_pref_web_background_hex_error) else null
    val previewHex = formatRgbColor(previewRgb)
    val previewDescription = stringResource(R.string.mjpeg_pref_web_background_color_preview, previewHex)

    fun updatePreview(rgb: Int) {
        previewRgb = rgb
        val text = formatRgbColor(rgb).substring(1)
        hexInput = TextFieldValue(text, selection = TextRange(text.length))
    }

    fun closeEditor() {
        val rgb = parseRgbColor(hexInput.text)
        if (rgb != null && rgb != initialRgb) {
            val hex = formatRgbColor(rgb)
            onWebPageChange { copy(background = hex) }
        }
        onDismiss()
    }

    SettingEditor(
        title = stringResource(BackgroundSearch.info.titleRes),
        description = stringResource(BackgroundSearch.info.summaryRes),
        onDismiss = ::closeEditor,
    ) {
        Text(
            stringResource(R.string.mjpeg_pref_default_value, "#101418"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .semantics { contentDescription = previewDescription },
                color = Color(0xFF000000.toInt() or previewRgb),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                content = {},
            )
            OutlinedTextField(
                value = hexInput,
                onValueChange = { next ->
                    val textChanged = next.text != hexInput.text
                    hexInput = next
                    if (textChanged) {
                        parseRgbColor(next.text)?.let { previewRgb = it }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { if (errorText != null) error(errorText) },
                label = { Text(stringResource(BackgroundSearch.hexLabelRes)) },
                prefix = { Text("#") },
                singleLine = true,
                isError = errorText != null,
                supportingText = if (errorText != null) {
                    { Text(errorText) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
        }
        Grid(
            config = {
                // Grid 1.12 uses physical column indices, so mirror the tracks for RTL.
                if (isRtl) {
                    column(GridTrackSize.MinMax(0.dp, 1.fr))
                    column(GridTrackSize.MaxContent)
                } else {
                    column(GridTrackSize.MaxContent)
                    column(GridTrackSize.MinMax(0.dp, 1.fr))
                }
                repeat(3) { row(GridTrackSize.Auto) }
                gap(8.dp)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(BackgroundSearch.redLabelRes),
                Modifier.gridItem(row = 1, column = labelColumn, alignment = Alignment.CenterStart),
                style = MaterialTheme.typography.bodyMedium,
            )
            BackgroundColorSlider(
                label = stringResource(BackgroundSearch.redLabelRes),
                value = previewRgb shr 16 and 0xFF,
                channelColor = Color(0xFFF44336),
                modifier = Modifier.gridItem(row = 1, column = sliderColumn, alignment = Alignment.CenterStart),
                onValueChange = { updatePreview((previewRgb and 0x00FFFF) or (it shl 16)) },
            )
            Text(
                stringResource(BackgroundSearch.greenLabelRes),
                Modifier.gridItem(row = 2, column = labelColumn, alignment = Alignment.CenterStart),
                style = MaterialTheme.typography.bodyMedium,
            )
            BackgroundColorSlider(
                label = stringResource(BackgroundSearch.greenLabelRes),
                value = previewRgb shr 8 and 0xFF,
                channelColor = Color(0xFF4CAF50),
                modifier = Modifier.gridItem(row = 2, column = sliderColumn, alignment = Alignment.CenterStart),
                onValueChange = { updatePreview((previewRgb and 0xFF00FF) or (it shl 8)) },
            )
            Text(
                stringResource(BackgroundSearch.blueLabelRes),
                Modifier.gridItem(row = 3, column = labelColumn, alignment = Alignment.CenterStart),
                style = MaterialTheme.typography.bodyMedium,
            )
            BackgroundColorSlider(
                label = stringResource(BackgroundSearch.blueLabelRes),
                value = previewRgb and 0xFF,
                channelColor = Color(0xFF2196F3),
                modifier = Modifier.gridItem(row = 3, column = sliderColumn, alignment = Alignment.CenterStart),
                onValueChange = { updatePreview((previewRgb and 0xFFFF00) or it) },
            )
        }
        Text(
            stringResource(BackgroundSearch.paletteLabelRes),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (rgb in backgroundPalette) {
                val selected = previewRgb == rgb
                val hex = formatRgbColor(rgb)
                val description = stringResource(R.string.mjpeg_pref_web_background_color_option, hex)
                Surface(
                    selected = selected,
                    onClick = { updatePreview(rgb) },
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = description
                            role = Role.RadioButton
                        },
                    color = Color(0xFF000000.toInt() or rgb),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        if (selected) 3.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    if (selected) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.mjpeg_icon_check),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                                    .padding(3.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundColorSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    channelColor: Color,
    modifier: Modifier = Modifier,
) {
    val numberFormat = rememberSettingNumberFormat()
    val sliderState = remember { SliderState(value.toFloat(), steps = 254, trackRange = 0f..255f) }
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val sliderColors = SliderDefaults.colors(activeTrackColor = channelColor)
    // Synchronize HEX/palette edits before Slider reads this controlled state.
    sliderState.value = value.toFloat()
    Slider(
        state = sliderState,
        interactionSource = interactions,
        onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 255)) },
        modifier = modifier
            .fillMaxWidth()
            .systemGestureExclusion()
            .semantics { contentDescription = label },
        colors = sliderColors,
        thumb = {
            SettingSliderThumb(
                valueText = numberFormat.format(sliderState.value.roundToInt()),
                interactionSource = interactions,
                isPersistent = focused,
                color = channelColor,
            )
        },
        track = { SliderDefaults.Track(sliderState = it, colors = sliderColors, drawTick = { _, _ -> }) },
    )
}

private fun parseRgbColor(text: String): Int? {
    if (text.length != 6 || text.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return text.toIntOrNull(16)
}

private fun formatRgbColor(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0').uppercase()
