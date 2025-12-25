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
import androidx.compose.ui.res.painterResource
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

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.rtsp_pref_protocol).contains(text, ignoreCase = true) ||
                getString(R.string.rtsp_pref_protocol_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        val protocol = remember(rtspSettingsState.value.protocol) {
            val parsed = runCatching { Protocol.valueOf(rtspSettingsState.value.protocol) }.getOrDefault(Protocol.TCP)
            mutableStateOf(parsed)
        }

        ProtocolUI(horizontalPadding, enabled, protocol.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val rtspSettings = koinInject<RtspSettings>()
        val state = rtspSettings.data.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        val selected = runCatching { Protocol.valueOf(state.value.protocol) }.getOrDefault(Protocol.TCP)
        ProtocolDetailUI(headerContent, selected) {
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
        Icon(painter = painterResource(R.drawable.protocol_24px), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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
