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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
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
import androidx.compose.ui.graphics.vector.ImageVector
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

internal object MaxFPS : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.MAX_FPS.name
    override val position: Int = 5
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_fps).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_fps_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_fps_text).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val maxFPS = remember { derivedStateOf { mjpegSettingsState.value.maxFPS } }

        MaxFpsUI(horizontalPadding, maxFPS.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val maxFPS = remember { derivedStateOf { mjpegSettingsState.value.maxFPS } }

        val scope = rememberCoroutineScope()

        MaxFpsDetailUI(headerContent, maxFPS.value) {
            if (maxFPS.value != it) {
                scope.launch { mjpegSettings.updateData { copy(maxFPS = it) } }
            }
        }
    }
}

@Composable
private fun MaxFpsUI(
    horizontalPadding: Dp,
    maxFPS: Int,
    onDetailShow: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Speed, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_fps),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_fps_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = maxFPS.toString(),
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun MaxFpsDetailUI(
    headerContent: @Composable (String) -> Unit,
    maxFPS: Int,
    onValueChange: (Int) -> Unit
) {
    val currentMaxFPS = remember(maxFPS) {
        val text = maxFPS.toString()
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }
    val isError = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_fps))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_fps_text),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )

            OutlinedTextField(
                value = currentMaxFPS.value,
                onValueChange = { textField ->
                    val newMaxFPS = textField.text.take(3).toIntOrNull()
                    if (newMaxFPS == null || newMaxFPS !in 1..120) {
                        currentMaxFPS.value = textField.copy(text = textField.text.take(3))
                        isError.value = true
                    } else {
                        currentMaxFPS.value = textField.copy(text = newMaxFPS.toString())
                        isError.value = false
                        onValueChange.invoke(newMaxFPS)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focusRequester),
                isError = isError.value,
                keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                singleLine = true,
            )
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private val Icon_Speed: ImageVector = materialIcon(name = "Filled.Speed") {
    materialPath {
        moveTo(20.38f, 8.57f)
        lineToRelative(-1.23f, 1.85f)
        arcToRelative(8.0f, 8.0f, 0.0f, false, true, -0.22f, 7.58f)
        lineTo(5.07f, 18.0f)
        arcTo(8.0f, 8.0f, 0.0f, false, true, 15.58f, 6.85f)
        lineToRelative(1.85f, -1.23f)
        arcTo(10.0f, 10.0f, 0.0f, false, false, 3.35f, 19.0f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.72f, 1.0f)
        horizontalLineToRelative(13.85f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 1.74f, -1.0f)
        arcToRelative(10.0f, 10.0f, 0.0f, false, false, -0.27f, -10.44f)
        close()
        moveTo(10.59f, 15.41f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 2.83f, 0.0f)
        lineToRelative(5.66f, -8.49f)
        lineToRelative(-8.49f, 5.66f)
        arcToRelative(2.0f, 2.0f, 0.0f, false, false, 0.0f, 2.83f)
        close()
    }
}