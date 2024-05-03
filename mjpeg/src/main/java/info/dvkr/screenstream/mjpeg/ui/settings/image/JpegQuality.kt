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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
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

internal object JpegQuality : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.JPEG_QUALITY.name
    override val position: Int = 6
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_jpeg_quality).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_jpeg_quality_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_jpeg_quality_text).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        JpegQualityUI(horizontalPadding, onDetailShow)

    @Composable
    override fun DetailUI(onBackClick: () -> Unit, headerContent: @Composable (String) -> Unit) =
        JpegQualityDetailUI(headerContent)
}

@Composable
private fun JpegQualityUI(
    horizontalPadding: Dp,
    onDetailShow: () -> Unit,
    mjpegSettings: MjpegSettings = koinInject()
) {
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val jpegQuality = remember { derivedStateOf { mjpegSettingsState.value.jpegQuality } }

    Row(
        modifier = Modifier
            .clickable(role = Role.Button) { onDetailShow.invoke() }
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.HighQuality,
            contentDescription = stringResource(id = R.string.mjpeg_pref_jpeg_quality),
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_jpeg_quality),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_jpeg_quality_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = jpegQuality.value.toString(),
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun JpegQualityDetailUI(
    headerContent: @Composable (String) -> Unit,
    scope: CoroutineScope = rememberCoroutineScope(),
    mjpegSettings: MjpegSettings = koinInject()
) {

    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val jpegQuality = remember { derivedStateOf { mjpegSettingsState.value.jpegQuality } }
    val currentJpegQuality = remember {
        val text = jpegQuality.value.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }

    val isError = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_jpeg_quality))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_jpeg_quality_text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            val focusRequester = remember { FocusRequester() }

            OutlinedTextField(
                value = currentJpegQuality.value,
                onValueChange = { textField ->
                    val newJpegQuality = textField.text.take(3).toIntOrNull()
                    if (newJpegQuality == null || newJpegQuality !in 10..100) {
                        currentJpegQuality.value = textField.copy(text = textField.text.take(3))
                        isError.value = true
                    } else {
                        currentJpegQuality.value = textField.copy(text = newJpegQuality.toString())
                        isError.value = false
                        if (jpegQuality.value != newJpegQuality) {
                            scope.launch { mjpegSettings.updateData { copy(jpegQuality = newJpegQuality) } }
                        }
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

            LaunchedEffect(Unit) { focusRequester.requestFocus() }
        }
    }
}