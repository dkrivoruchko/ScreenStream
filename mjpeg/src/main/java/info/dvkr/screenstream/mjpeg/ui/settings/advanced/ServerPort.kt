package info.dvkr.screenstream.mjpeg.ui.settings.advanced

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

internal object ServerPort : ModuleSettings.Item {
    override val id: String = MjpegSettings.Key.SERVER_PORT.name
    override val position: Int = 4
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_server_port).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_server_port_summary).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_server_port_text).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val serverPort = remember { derivedStateOf { mjpegSettingsState.value.serverPort } }

        ServerPortUI(horizontalPadding, serverPort.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val mjpegSettings = koinInject<MjpegSettings>()
        val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
        val serverPort = remember { derivedStateOf { mjpegSettingsState.value.serverPort } }

        val scope = rememberCoroutineScope()

        ServerPortDetailUI(headerContent, serverPort.value.toString()) {
            if (serverPort.value != it) {
                scope.launch { mjpegSettings.updateData { copy(serverPort = it) } }
            }
        }
    }
}

@Composable
private fun ServerPortUI(
    horizontalPadding: Dp,
    serverPort: Int,
    onDetailShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icon_Http, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_server_port),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.mjpeg_pref_server_port_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = serverPort.toString(),
            modifier = Modifier.defaultMinSize(minWidth = 52.dp),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ServerPortDetailUI(
    headerContent: @Composable (String) -> Unit,
    serverPort: String,
    onValueChange: (Int) -> Unit
) {
    val currentServerPort = remember(serverPort) {
        mutableStateOf(TextFieldValue(text = serverPort, selection = TextRange(serverPort.length)))
    }
    val isError = remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.mjpeg_pref_server_port))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.mjpeg_pref_server_port_text),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = currentServerPort.value,
                onValueChange = { textField ->
                    val newServerPort = textField.text.take(5).toIntOrNull()
                    if (newServerPort == null || newServerPort !in 1025..65535) {
                        currentServerPort.value = textField.copy(text = textField.text.take(5))
                        isError.value = true
                    } else {
                        currentServerPort.value = textField.copy(text = newServerPort.toString())
                        isError.value = false
                        onValueChange.invoke(newServerPort)
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

private val Icon_Http: ImageVector = materialIcon(name = "Http") {
    materialPath {
        moveTo(1.1F, 16.0F)
        verticalLineTo(8.0F)
        horizontalLineToRelative(1.271F)
        verticalLineToRelative(3.149F)
        horizontalLineToRelative(2.5F)
        verticalLineTo(8.0F)
        horizontalLineToRelative(1.277F)
        verticalLineToRelative(8.0F)
        horizontalLineTo(4.872F)
        verticalLineToRelative(-3.498F)
        horizontalLineToRelative(-2.5F)
        verticalLineTo(16.0F)
        close()
    }
    materialPath {
        moveTo(8.885F, 16.0F)
        verticalLineTo(9.353F)
        horizontalLineTo(7.01F)
        verticalLineTo(8.0F)
        horizontalLineToRelative(5.016F)
        verticalLineToRelative(1.353F)
        horizontalLineToRelative(-1.87F)
        verticalLineTo(16.0F)
        close()
    }
    materialPath {
        moveTo(14.285F, 16.0F)
        verticalLineTo(9.353F)
        horizontalLineTo(12.41F)
        verticalLineTo(8.0F)
        horizontalLineToRelative(5.017F)
        verticalLineToRelative(1.353F)
        horizontalLineToRelative(-1.87F)
        verticalLineTo(16.0F)
        close()
    }
    materialPath {
        moveTo(18.262F, 16.0F)
        lineTo(18.262F, 8.0F)
        horizontalLineToRelative(2.049F)
        quadToRelative(1.145F, 0.0F, 1.497F, 0.115F)
        quadToRelative(0.562F, 0.185F, 0.924F, 0.79F)
        quadToRelative(0.368F, 0.607F, 0.368F, 1.562F)
        quadToRelative(0.0F, 0.867F, -0.315F, 1.457F)
        quadToRelative(-0.315F, 0.584F, -0.788F, 0.824F)
        quadToRelative(-0.473F, 0.234F, -1.629F, 0.234F)
        horizontalLineToRelative(-0.835F)
        lineTo(19.533F, 16.0F)
        moveTo(19.533F, 9.353F)
        verticalLineToRelative(2.27F)
        horizontalLineToRelative(0.704F)
        quadToRelative(0.71F, 0.0F, 0.961F, -0.103F)
        quadToRelative(0.258F, -0.104F, 0.42F, -0.371F)
        quadToRelative(0.163F, -0.273F, 0.163F, -0.666F)
        quadToRelative(0.0F, -0.398F, -0.168F, -0.671F)
        reflectiveQuadToRelative(-0.415F, -0.366F)
        quadToRelative(-0.247F, -0.093F, -1.045F, -0.093F)
        close()
    }
}