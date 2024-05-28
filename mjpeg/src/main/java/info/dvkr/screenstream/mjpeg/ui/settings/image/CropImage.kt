package info.dvkr.screenstream.mjpeg.ui.settings.image

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object CropImage : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.IMAGE_CROP.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_crop).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_warning).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_top).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_bottom).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_left).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_right).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_pixels).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_crop_error).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val imageCrop = remember { derivedStateOf { mjpegSettingsState.value.imageCrop } }

        CropImageUI(horizontalPadding, imageCrop.value, onDetailShow) {
            coroutineScope.launch { mjpegSettings.updateData { copy(imageCrop = imageCrop.value.not()) } }
        }
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val imageCropTop = remember { derivedStateOf { mjpegSettingsState.value.imageCropTop } }
        val imageCropBottom = remember { derivedStateOf { mjpegSettingsState.value.imageCropBottom } }
        val imageCropLeft = remember { derivedStateOf { mjpegSettingsState.value.imageCropLeft } }
        val imageCropRight = remember { derivedStateOf { mjpegSettingsState.value.imageCropRight } }

        val scope = rememberCoroutineScope()

        CropImageDetailUI(
            headerContent,
            imageCropTop.value,
            imageCropBottom.value,
            imageCropLeft.value,
            imageCropRight.value,
            onNewValueTop = { if (imageCropTop.value != it) scope.launch { mjpegSettings.updateData { copy(imageCropTop = it) } } },
            onNewValueBottom = { if (imageCropBottom.value != it) scope.launch { mjpegSettings.updateData { copy(imageCropBottom = it) } } },
            onNewValueLeft = { if (imageCropLeft.value != it) scope.launch { mjpegSettings.updateData { copy(imageCropLeft = it) } } },
            onNewValueRight = { if (imageCropRight.value != it) scope.launch { mjpegSettings.updateData { copy(imageCropRight = it) } } }
        )
    }
}

@Composable
private fun CropImageUI(
    horizontalPadding: Dp,
    imageCrop: Boolean,
    onDetailShow: () -> Unit,
    onValueChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .toggleable(value = imageCrop, onValueChange = { onDetailShow.invoke() })
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Crop, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_crop),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_crop_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        VerticalDivider(modifier = Modifier.padding(vertical = 12.dp).padding(start = 4.dp, end = 8.dp).fillMaxHeight())

        Switch(checked = imageCrop, onCheckedChange = onValueChange, modifier = Modifier.scale(0.7F))
    }
}

@Composable
private fun CropImageDetailUI(
    headerContent: @Composable (String) -> Unit,
    imageCropTop: Int,
    imageCropBottom: Int,
    imageCropLeft: Int,
    imageCropRight: Int,
    onNewValueTop: (Int) -> Unit,
    onNewValueBottom: (Int) -> Unit,
    onNewValueLeft: (Int) -> Unit,
    onNewValueRight: (Int) -> Unit
) {
    val topError = remember { mutableStateOf(false) }
    val bottomError = remember { mutableStateOf(false) }
    val leftError = remember { mutableStateOf(false) }
    val rightError = remember { mutableStateOf(false) }

    val hasError = topError.value || bottomError.value || leftError.value || rightError.value

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_crop))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = if (hasError) stringResource(id = R.string.mjpeg_pref_crop_error) else stringResource(id = R.string.mjpeg_pref_crop_warning),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                color = if (hasError) MaterialTheme.colorScheme.error else Color.Unspecified,
                style = MaterialTheme.typography.bodyLarge
            )

            CropRow(imageCropTop, R.string.mjpeg_pref_crop_top, topError, focusRequester, focusManager, onNewValueTop)
            CropRow(imageCropBottom, R.string.mjpeg_pref_crop_bottom, bottomError, focusRequester, focusManager, onNewValueBottom)
            CropRow(imageCropLeft, R.string.mjpeg_pref_crop_left, leftError, focusRequester, focusManager, onNewValueLeft)
            CropRow(imageCropRight, R.string.mjpeg_pref_crop_right, rightError, focusRequester, focusManager, onNewValueRight)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun CropRow(
    crop: Int,
    @StringRes labelRes: Int,
    hasError: MutableState<Boolean>,
    focusRequester: FocusRequester,
    focusManager: FocusManager,
    onNewValue: (Int) -> Unit
) {
    val currentCrop = remember(crop) {
        val value = crop.toString()
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }

    OutlinedTextField(
        value = currentCrop.value,
        onValueChange = { textField ->
            val newCrop = textField.text.take(6).toIntOrNull()
            if (newCrop == null || newCrop < 0) {
                currentCrop.value = textField.copy(text = textField.text.take(6))
                hasError.value = true
            } else {
                currentCrop.value = textField.copy(text = newCrop.toString())
                hasError.value = false
                onNewValue.invoke(newCrop)
            }
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focusRequester),
        label = {
            Text(
                text = stringResource(id = labelRes) + " (" + stringResource(id = R.string.mjpeg_pref_crop_pixels) + ")",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        isError = hasError.value,
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) }
        ),
        singleLine = true
    )
}

private val Icon_Crop: ImageVector = materialIcon(name = "Filled.Crop") {
    materialPath {
        moveTo(17.0f, 15.0f)
        horizontalLineToRelative(2.0f)
        verticalLineTo(7.0f)
        curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
        horizontalLineTo(9.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(8.0f)
        verticalLineToRelative(8.0f)
        close()
        moveTo(7.0f, 17.0f)
        verticalLineTo(1.0f)
        horizontalLineTo(5.0f)
        verticalLineToRelative(4.0f)
        horizontalLineTo(1.0f)
        verticalLineToRelative(2.0f)
        horizontalLineToRelative(4.0f)
        verticalLineToRelative(10.0f)
        curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
        horizontalLineToRelative(10.0f)
        verticalLineToRelative(4.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-4.0f)
        horizontalLineToRelative(4.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineTo(7.0f)
        close()
    }
}