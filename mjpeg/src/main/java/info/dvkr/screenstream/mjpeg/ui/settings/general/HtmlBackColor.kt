package info.dvkr.screenstream.mjpeg.ui.settings.general

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object HtmlBackColor : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.HTML_BACK_COLOR.name
    override val position: Int = 6
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_html_back_color).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_html_back_color_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_html_back_color_title).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val htmlBackColor = remember { derivedStateOf { Color(mjpegSettingsState.value.htmlBackColor) } }

        HtmlBackColorUI(horizontalPadding, htmlBackColor.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val htmlBackColor = remember { derivedStateOf { Color(mjpegSettingsState.value.htmlBackColor) } }
        val scope = rememberCoroutineScope()

        HtmlBackColorDetailUI(headerContent, htmlBackColor.value) { color: Color ->
            if (htmlBackColor.value != color) {
                scope.launch { mjpegSettings.updateData { copy(htmlBackColor = color.toArgb()) } }
            }
        }

    }

    internal val colorPalette = listOf(
        Color("#F44336".toColorInt()), Color("#E91E63".toColorInt()), Color("#9C27B0".toColorInt()), Color("#673AB7".toColorInt()),
        Color("#3F51B5".toColorInt()), Color("#2196F3".toColorInt()), Color("#03A9F4".toColorInt()), Color("#00BCD4".toColorInt()),
        Color("#009688".toColorInt()), Color("#4CAF50".toColorInt()), Color("#8BC34A".toColorInt()), Color("#CDDC39".toColorInt()),
        Color("#FFEB3B".toColorInt()), Color("#FFC107".toColorInt()), Color("#FF9800".toColorInt()), Color("#FF5722".toColorInt()),
        Color("#795548".toColorInt()), Color("#9E9E9E".toColorInt()), Color("#607D8B".toColorInt()), Color("#000000".toColorInt()),
    )
}

@Composable
private fun HtmlBackColorUI(
    horizontalPadding: Dp,
    htmlBackColor: Color,
    onDetailShow: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_FormatColorFill, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_back_color),
                modifier = Modifier.padding(top = 8.dp, end = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_html_back_color_summary),
                modifier = Modifier.padding(top = 2.dp, end = 8.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .size(36.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(htmlBackColor)
                .border(BorderStroke(1.dp, SolidColor(LocalContentColor.current)), MaterialTheme.shapes.medium),
        )
    }
}

@Composable
private fun HtmlBackColorDetailUI(
    headerContent: @Composable (String) -> Unit,
    htmlBackColor: Color,
    onColorChange: (Color) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_html_back_color))

        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            ColorEditorPanel(
                htmlBackColor = htmlBackColor,
                onColorChange = onColorChange,
                modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 4.dp).fillMaxWidth()
            )

            ColorSliderPanel(
                htmlBackColor = htmlBackColor,
                onColorChange = onColorChange,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth()
            )

            ColorPalettePanel(
                onColorChange = onColorChange,
                modifier = Modifier.padding(16.dp).fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ColorEditorPanel(
    htmlBackColor: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = LocalContentColor.current,
) {
    val currentColorString = remember(htmlBackColor) { mutableStateOf("%06X".format(0xFFFFFF and htmlBackColor.toArgb())) }
    val textColor = remember(htmlBackColor) { if (htmlBackColor.luminance() <= 0.5F) Color.White else Color.Black }
    val colorRegexp = remember { "[^0-9a-fA-F]".toRegex() }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width = remember { with(density) { textMeasurer.measure("000000").size.width.toDp() + 64.dp } }

    Surface(
        modifier = modifier.requiredWidthIn(max = 296.dp).padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = htmlBackColor,
        border = BorderStroke(1.dp, SolidColor(borderColor))
    ) {
        OutlinedTextField(
            value = currentColorString.value,
            onValueChange = { newColorString ->
                currentColorString.value = newColorString.replace(colorRegexp, "").take(6)
                runCatching { "#${currentColorString.value}".toColorInt() }.getOrNull()?.let { onColorChange.invoke(Color(it)) }
            },
            modifier = Modifier.wrapContentWidth(align = Alignment.CenterHorizontally).width(width),
            prefix = { Text(text = "#") },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                disabledBorderColor = Color.Transparent,
                errorBorderColor = Color.Transparent,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                disabledPrefixColor = textColor,
                errorPrefixColor = textColor,
                focusedPrefixColor = textColor,
                unfocusedPrefixColor = textColor,
            )
        )
    }
}

@Composable
private fun ColorSliderPanel(
    htmlBackColor: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val sliderRed = remember(htmlBackColor) { mutableFloatStateOf(htmlBackColor.red) }
    val sliderGreen = remember(htmlBackColor) { mutableFloatStateOf(htmlBackColor.green) }
    val sliderBlue = remember(htmlBackColor) { mutableFloatStateOf(htmlBackColor.blue) }

    Column(modifier = modifier) {
        ColorSlider(
            name = "R",
            color = Color(255, 0, 0),
            value = sliderRed.floatValue,
            onValueChange = {
                sliderRed.floatValue = it
                onColorChange.invoke(Color(sliderRed.floatValue, sliderGreen.floatValue, sliderBlue.floatValue))
            },
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
        )
        ColorSlider(
            name = "G",
            color = Color(0, 255, 0),
            value = sliderGreen.floatValue,
            onValueChange = {
                sliderGreen.floatValue = it
                onColorChange.invoke(Color(sliderRed.floatValue, sliderGreen.floatValue, sliderBlue.floatValue))
            },
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
        )
        ColorSlider(
            name = "B",
            color = Color(0, 0, 255),
            value = sliderBlue.floatValue,
            onValueChange = {
                sliderBlue.floatValue = it
                onColorChange.invoke(Color(sliderRed.floatValue, sliderGreen.floatValue, sliderBlue.floatValue))
            },
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()
        )
    }
}

@Composable
private fun ColorSlider(
    name: String,
    color: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textWidth = remember { with(density) { textMeasurer.measure("00000").size.width.toDp() } }
    val sliderValue = remember(value) { mutableFloatStateOf(value) }

    val max = 296.dp + textWidth + textWidth
    Row(
        modifier = modifier.requiredWidthIn(max = max).widthIn(max = max).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            modifier = Modifier.requiredWidthIn(min = textWidth, max = textWidth),
            textAlign = TextAlign.End
        )
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = color)) {
            Slider(
                value = sliderValue.floatValue * 255,
                onValueChange = { sliderValue.floatValue = it / 255 },
                modifier = Modifier.weight(1F).padding(horizontal = 8.dp),
                valueRange = 0F..255F,
                steps = 256,
                onValueChangeFinished = { onValueChange.invoke(sliderValue.floatValue) },
            )
        }
        Text(
            text = (sliderValue.floatValue * 255).toInt().toString(),
            modifier = Modifier.requiredWidthIn(min = textWidth, max = textWidth)
        )
    }
}

@Composable
private fun ColorPalettePanel(
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = LocalContentColor.current,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HtmlBackColor.colorPalette.chunked(4).forEach { colorsRow ->
            Row(
                modifier = Modifier.requiredWidthIn(max = 296.dp).widthIn(max = 296.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                colorsRow.forEach { color ->
                    Spacer(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(color)
                            .border(BorderStroke(1.dp, SolidColor(borderColor)), MaterialTheme.shapes.large)
                            .clickable(role = Role.Button, onClick = { onColorChange.invoke(color) })
                    )
                }
            }
        }
    }
}

private val Icon_FormatColorFill: ImageVector = materialIcon(name = "Outlined.FormatColorFill") {
    materialPath {
        moveTo(16.56f, 8.94f)
        lineTo(7.62f, 0.0f)
        lineTo(6.21f, 1.41f)
        lineToRelative(2.38f, 2.38f)
        lineTo(3.44f, 8.94f)
        curveToRelative(-0.59f, 0.59f, -0.59f, 1.54f, 0.0f, 2.12f)
        lineToRelative(5.5f, 5.5f)
        curveTo(9.23f, 16.85f, 9.62f, 17.0f, 10.0f, 17.0f)
        reflectiveCurveToRelative(0.77f, -0.15f, 1.06f, -0.44f)
        lineToRelative(5.5f, -5.5f)
        curveTo(17.15f, 10.48f, 17.15f, 9.53f, 16.56f, 8.94f)
        close()
        moveTo(5.21f, 10.0f)
        lineTo(10.0f, 5.21f)
        lineTo(14.79f, 10.0f)
        horizontalLineTo(5.21f)
        close()
        moveTo(19.0f, 11.5f)
        curveToRelative(0.0f, 0.0f, -2.0f, 2.17f, -2.0f, 3.5f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
        curveTo(21.0f, 13.67f, 19.0f, 11.5f, 19.0f, 11.5f)
        close()
        moveTo(2.0f, 20.0f)
        horizontalLineToRelative(20.0f)
        verticalLineToRelative(4.0f)
        horizontalLineTo(2.0f)
        verticalLineTo(20.0f)
        close()
    }
}