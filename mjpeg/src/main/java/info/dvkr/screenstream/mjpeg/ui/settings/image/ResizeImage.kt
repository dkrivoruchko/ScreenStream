package info.dvkr.screenstream.mjpeg.ui.settings.image

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toComposeIntRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.WindowMetricsCalculator
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.min

internal object ResizeImage : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.RESIZE_FACTOR.name
    override val position: Int = 3
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_resize).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_resize_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_resize_text, 0, 0).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_resize_result, 0, 0).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()

        ResizeImageUI(
            horizontalPadding,
            mjpegSettingsState.value.resizeFactor,
            mjpegSettingsState.value.resolutionWidth,
            mjpegSettingsState.value.resolutionHeight,
            onDetailShow
        )
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val size = remember { WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(context).bounds.toComposeIntRect().size }
        val scope = rememberCoroutineScope()

        ResizeImageDetailUI(
            headerContent,
            mjpegSettingsState.value.resizeFactor,
            mjpegSettingsState.value.resolutionWidth,
            mjpegSettingsState.value.resolutionHeight,
            mjpegSettingsState.value.resolutionStretch,
            size,
            onNewResize = {
                if (mjpegSettingsState.value.resizeFactor != it) scope.launch {
                    mjpegSettings.updateData { copy(resizeFactor = it) }
                }
            },
            onNewWidth = {
                if (mjpegSettingsState.value.resolutionWidth != it) scope.launch {
                    mjpegSettings.updateData { copy(resolutionWidth = it) }
                }
            },
            onNewHeight = {
                if (mjpegSettingsState.value.resolutionHeight != it) scope.launch {
                    mjpegSettings.updateData { copy(resolutionHeight = it) }
                }
            },
            onStretchChange = {
                if (mjpegSettingsState.value.resolutionStretch != it) scope.launch {
                    mjpegSettings.updateData { copy(resolutionStretch = it) }
                }
            }
        )
    }
}

@Composable
private fun ResizeImageUI(
    horizontalPadding: Dp,
    resizeFactor: Int,
    width: Int,
    height: Int,
    onDetailShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Resize, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_resize),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_resize_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = if (width > 0 && height > 0) {
                stringResource(id = R.string.mjpeg_pref_resize_resolution_value, width, height)
            } else {
                stringResource(id = R.string.mjpeg_pref_resize_value, resizeFactor)
            },
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ResizeImageDetailUI(
    headerContent: @Composable (String) -> Unit,
    resizeFactor: Int,
    resolutionWidth: Int,
    resolutionHeight: Int,
    stretch: Boolean,
    size: IntSize,
    onNewResize: (Int) -> Unit,
    onNewWidth: (Int) -> Unit,
    onNewHeight: (Int) -> Unit,
    onStretchChange: (Boolean) -> Unit,
) {
    val isResolutionMode = remember(resolutionWidth, resolutionHeight) { resolutionWidth > 0 && resolutionHeight > 0 }
    var mode by remember { mutableStateOf(if (isResolutionMode) 1 else 0) }

    val currentResizeFactor = remember(resizeFactor) {
        val text = resizeFactor.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    val currentWidth = remember(resolutionWidth) {
        val text = if (resolutionWidth > 0) resolutionWidth.toString() else ""
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    val currentHeight = remember(resolutionHeight) {
        val text = if (resolutionHeight > 0) resolutionHeight.toString() else ""
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    val isError = remember { mutableStateOf(false) }
    val widthError = remember { mutableStateOf(false) }
    val heightError = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val (resizedWidth, resizedHeight) = if (mode == 0) {
        (size.width * resizeFactor / 100F).toInt() to (size.height * resizeFactor / 100F).toInt()
    } else {
        if (currentWidth.value.text.isNotEmpty() && currentHeight.value.text.isNotEmpty()) {
            val w = currentWidth.value.text.toIntOrNull() ?: 0
            val h = currentHeight.value.text.toIntOrNull() ?: 0
            when {
                w <= 0 || h <= 0 -> 0 to 0
                stretch -> w to h
                size.width <= 0 || size.height <= 0 -> 0 to 0
                else -> {
                    val scale = min(w.toFloat() / size.width, h.toFloat() / size.height)
                    (size.width * scale).toInt() to (size.height * scale).toInt()
                }
            }
        } else 0 to 0
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_resize))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_resize_text, size.width, size.height),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )

            Text(
                text = stringResource(id = R.string.mjpeg_pref_resize_result, resizedWidth, resizedHeight),
                modifier = Modifier.fillMaxWidth()
            )

            Column(modifier = Modifier.selectableGroup()) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .selectable(
                            selected = mode == 0,
                            onClick = {
                                mode = 0
                                onNewWidth.invoke(0)
                                onNewHeight.invoke(0)
                                onStretchChange.invoke(false)
                            },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == 0, onClick = null)
                    Text(
                        text = stringResource(id = R.string.mjpeg_pref_resize_mode_percent),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .selectable(
                            selected = mode == 1,
                            onClick = { mode = 1 },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = mode == 1, onClick = null)
                    Text(
                        text = stringResource(id = R.string.mjpeg_pref_resize_mode_resolution),
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }

            if (mode == 0) {
                OutlinedTextField(
                    value = currentResizeFactor.value,
                    onValueChange = { textField ->
                        val newResize = textField.text.take(3).toIntOrNull()
                        if (newResize == null || newResize !in 10..150) {
                            currentResizeFactor.value = textField.copy(text = textField.text.take(3))
                            isError.value = true
                        } else {
                            currentResizeFactor.value = textField.copy(text = newResize.toString())
                            isError.value = false
                            onNewResize.invoke(newResize)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(focusRequester),
                    isError = isError.value,
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                )
            } else {
                OutlinedTextField(
                    value = currentWidth.value,
                    onValueChange = { textField ->
                        val newWidth = textField.text.take(5).toIntOrNull()
                        if (newWidth == null || newWidth <= 0) {
                            currentWidth.value = textField.copy(text = textField.text.take(5))
                            widthError.value = true
                        } else {
                            currentWidth.value = textField.copy(text = newWidth.toString())
                            widthError.value = false
                            onNewWidth.invoke(newWidth)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .focusRequester(focusRequester),
                    isError = widthError.value,
                    label = { Text(text = stringResource(id = R.string.mjpeg_pref_resize_width)) },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    value = currentHeight.value,
                    onValueChange = { textField ->
                        val newHeight = textField.text.take(5).toIntOrNull()
                        if (newHeight == null || newHeight <= 0) {
                            currentHeight.value = textField.copy(text = textField.text.take(5))
                            heightError.value = true
                        } else {
                            currentHeight.value = textField.copy(text = newHeight.toString())
                            heightError.value = false
                            onNewHeight.invoke(newHeight)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    isError = heightError.value,
                    label = { Text(text = stringResource(id = R.string.mjpeg_pref_resize_height)) },
                    keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .clickable { onStretchChange.invoke(!stretch) }
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = stretch, onCheckedChange = null)
                    Text(
                        text = stringResource(id = R.string.mjpeg_pref_resize_stretch),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private val Icon_Resize: ImageVector = materialIcon(name = "Resize") {
    materialPath {
        verticalLineToRelative(0.0F)
        moveTo(10.59F, 12.0F)
        lineTo(14.59F, 8.0F)
        horizontalLineTo(11.0F)
        verticalLineTo(6.0F)
        horizontalLineTo(18.0F)
        verticalLineTo(13.0F)
        horizontalLineTo(16.0F)
        verticalLineTo(9.41F)
        lineTo(12.0F, 13.41F)
        verticalLineTo(16.0F)
        horizontalLineTo(20.0F)
        verticalLineTo(4.0F)
        horizontalLineTo(8.0F)
        verticalLineTo(12.0F)
        horizontalLineTo(10.59F)
        moveTo(22.0F, 2.0F)
        verticalLineTo(18.0F)
        horizontalLineTo(12.0F)
        verticalLineTo(22.0F)
        horizontalLineTo(2.0F)
        verticalLineTo(12.0F)
        horizontalLineTo(6.0F)
        verticalLineTo(2.0F)
        horizontalLineTo(22.0F)
        moveTo(10.0F, 14.0F)
        horizontalLineTo(4.0F)
        verticalLineTo(20.0F)
        horizontalLineTo(10.0F)
        verticalLineTo(14.0F)
        close()
    }
}