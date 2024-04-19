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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        CropImageUI(horizontalPadding, coroutineScope, onDetailShow)

    @Composable
    override fun DetailUI(onBackClick: () -> Unit, headerContent: @Composable (String) -> Unit) =
        CropImageDetailUI(headerContent)
}

@Composable
private fun CropImageUI(
    horizontalPadding: Dp,
    scope: CoroutineScope,
    onDetailShow: () -> Unit,
    mjpegSettings: MjpegSettings = koinInject()
) {
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val imageCrop = remember { derivedStateOf { mjpegSettingsState.value.imageCrop } }

    Row(
        modifier = Modifier
            .toggleable(
                value = imageCrop.value,
                onValueChange = { onDetailShow.invoke() }
            )
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Crop,
            contentDescription = stringResource(id = R.string.mjpeg_pref_crop),
            modifier = Modifier.padding(end = 16.dp)
        )

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

        VerticalDivider(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .padding(start = 4.dp, end = 8.dp)
                .fillMaxHeight()
        )

        Switch(
            checked = imageCrop.value,
            onCheckedChange = {
                scope.launch {
                    withContext(NonCancellable) { mjpegSettings.updateData { copy(imageCrop = imageCrop.value.not()) } }
                }
            },
            modifier = Modifier.scale(0.7F),
        )
    }
}

@Composable
private fun CropImageDetailUI(
    headerContent: @Composable (String) -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
    mjpegSettings: MjpegSettings = koinInject()
) {

    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val imageCropTop = remember { derivedStateOf { mjpegSettingsState.value.imageCropTop } }
    val imageCropBottom = remember { derivedStateOf { mjpegSettingsState.value.imageCropBottom } }
    val imageCropLeft = remember { derivedStateOf { mjpegSettingsState.value.imageCropLeft } }
    val imageCropRight = remember { derivedStateOf { mjpegSettingsState.value.imageCropRight } }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                color = if (hasError) MaterialTheme.colorScheme.error else Color.Unspecified,
                style = MaterialTheme.typography.bodyLarge
            )

            CropRow(imageCropTop.value, R.string.mjpeg_pref_crop_top, topError, focusRequester, focusManager) { newTop ->
                if (imageCropTop.value != newTop) {
                    scope.launch { withContext(NonCancellable) { mjpegSettings.updateData { copy(imageCropTop = newTop) } } }
                }
            }

            CropRow(imageCropBottom.value, R.string.mjpeg_pref_crop_bottom, bottomError, focusRequester, focusManager) { newBottom ->
                if (imageCropBottom.value != newBottom) {
                    scope.launch { withContext(NonCancellable) { mjpegSettings.updateData { copy(imageCropBottom = newBottom) } } }
                }
            }

            CropRow(imageCropLeft.value, R.string.mjpeg_pref_crop_left, leftError, focusRequester, focusManager) { newLeft ->
                if (imageCropLeft.value != newLeft) {
                    scope.launch { withContext(NonCancellable) { mjpegSettings.updateData { copy(imageCropLeft = newLeft) } } }
                }
            }

            CropRow(imageCropRight.value, R.string.mjpeg_pref_crop_right, rightError, focusRequester, focusManager) { newRight ->
                if (imageCropRight.value != newRight) {
                    scope.launch { withContext(NonCancellable) { mjpegSettings.updateData { copy(imageCropRight = newRight) } } }
                }
            }
        }

        LaunchedEffect(true) { focusRequester.requestFocus() }
    }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .focusRequester(focusRequester),
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