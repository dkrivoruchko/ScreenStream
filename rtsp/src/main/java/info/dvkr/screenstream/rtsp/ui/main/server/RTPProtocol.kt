package info.dvkr.screenstream.rtsp.ui.main.server

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.Protocol
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object RTPProtocol : ModuleSettings.Item {
    override val id: String = RtspSettings.Key.PROTOCOL.name
    override val position: Int = 0
    override val available: Boolean = true

    //TODO
    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.mjpeg_pref_enable_ipv4).contains(text, ignoreCase = true) ||
                getString(R.string.mjpeg_pref_enable_ipv4_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        val protocol = remember(rtspSettingsState.value.protocol) {
            mutableStateOf(Protocol.valueOf(rtspSettingsState.value.protocol))
        }

        ProtocolUI(horizontalPadding, enabled, protocol.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        ProtocolDetailUI(headerContent, Protocol.valueOf(state.value.protocol)) {
            if (state.value.protocol != it.name) {
                scope.launch { rtspSettings.updateData { copy(protocol = it.name) } }
            }
        }
    }
}

@Composable
private fun ProtocolUI(horizontalPadding: Dp, enabled: Boolean, protocol: Protocol, onDetailShow: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onDetailShow)
            .padding(start = horizontalPadding + 12.dp, end = horizontalPadding + 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        //todo
        Icon(imageVector = Icon_IPv4, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.rtsp_pref_protocol),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.rtsp_pref_protocol_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Text(
            text = protocol.name,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun ProtocolDetailUI(
    headerContent: @Composable (String) -> Unit,
    protocol: Protocol,
    onValueChange: (Protocol) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.rtsp_pref_protocol))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text(
                text = stringResource(id = R.string.rtsp_pref_protocol_summary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Protocol.entries.forEach { item ->
                Row(
                    modifier = Modifier
                        .toggleable(
                            value = item == protocol,
                            onValueChange = { onValueChange.invoke(item) },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = item == protocol,
                        onClick = null
                    )
                    Text(
                        text = item.name,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

private val Icon_IPv4: ImageVector = materialIcon(name = "IPv4") {
    materialPath {
        moveTo(1.255f, 16.792f)
        verticalLineTo(7.247f)
        horizontalLineTo(2.83f)
        verticalLineToRelative(9.545f)
        close()
    }
    materialPath {
        moveTo(4.341f, 16.792f)
        verticalLineTo(7.247f)
        horizontalLineTo(6.88f)
        quadToRelative(1.419f, 0.0f, 1.855f, 0.137f)
        quadToRelative(0.697f, 0.221f, 1.146f, 0.944f)
        quadToRelative(0.456f, 0.723f, 0.456f, 1.862f)
        quadToRelative(0.0f, 1.035f, -0.391f, 1.738f)
        quadToRelative(-0.39f, 0.697f, -0.977f, 0.983f)
        quadToRelative(-0.586f, 0.28f, -2.018f, 0.28f)
        horizontalLineTo(5.916f)
        verticalLineToRelative(3.6f)
        close()
        moveTo(5.916f, 8.862f)
        verticalLineToRelative(2.708f)
        horizontalLineToRelative(0.872f)
        quadToRelative(0.88f, 0.0f, 1.192f, -0.123f)
        quadToRelative(0.319f, -0.124f, 0.52f, -0.443f)
        quadToRelative(0.202f, -0.326f, 0.202f, -0.794f)
        quadToRelative(0.0f, -0.476f, -0.208f, -0.801f)
        quadToRelative(-0.208f, -0.326f, -0.514f, -0.436f)
        quadToRelative(-0.306f, -0.111f, -1.296f, -0.111f)
        close()
    }
    materialPath {
        moveTo(13.182f, 16.792f)
        lineToRelative(-2.286f, -6.914f)
        horizontalLineToRelative(1.576f)
        lineToRelative(1.068f, 3.528f)
        lineToRelative(0.306f, 1.179f)
        lineToRelative(0.319f, -1.179f)
        lineToRelative(1.08f, -3.528f)
        horizontalLineToRelative(1.537f)
        lineToRelative(-2.253f, 6.914f)
        close()
    }
    materialPath {
        moveTo(20.324f, 16.792f)
        verticalLineToRelative(-1.92f)
        horizontalLineTo(17.12f)
        verticalLineTo(13.27f)
        lineToRelative(3.392f, -6.062f)
        horizontalLineToRelative(1.263f)
        verticalLineToRelative(6.055f)
        horizontalLineToRelative(0.97f)
        verticalLineToRelative(1.608f)
        horizontalLineToRelative(-0.97f)
        verticalLineToRelative(1.92f)
        close()
        moveTo(20.324f, 13.263f)
        verticalLineToRelative(-3.262f)
        lineToRelative(-1.797f, 3.262f)
        close()
    }
}