package info.dvkr.screenstream.rtmp.ui.main.cards

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.internal.RtmpEvent
import info.dvkr.screenstream.rtmp.settings.RtmpSettings
import info.dvkr.screenstream.rtmp.ui.RtmpBinding
import info.dvkr.screenstream.rtmp.ui.RtmpClientStatus
import info.dvkr.screenstream.rtmp.ui.RtmpError
import info.dvkr.screenstream.rtmp.ui.main.mode.ClientMode
import info.dvkr.screenstream.rtmp.ui.main.mode.ServerMode

@Composable
internal fun ModeCard(
    sendEvent: (RtmpEvent) -> Unit,
    selectedMode: RtmpSettings.Values.Mode,
    onModeSelected: (RtmpSettings.Values.Mode) -> Unit,
    isStreaming: Boolean,
    serverBindings: List<RtmpBinding>,
    clientStatus: RtmpClientStatus,
    error: RtmpError?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(
                            text = stringResource(id = R.string.rtmp_mode_card_title),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium
                        )

                        ModeSelector(
                            selected = selectedMode,
                            onSelect = onModeSelected,
                            enabled = isStreaming.not()
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.fillMaxWidth())

                when (selectedMode) {
                    RtmpSettings.Values.Mode.SERVER -> ServerMode(
                        serverBindings = serverBindings,
                        error = error,
                        isStreaming = isStreaming,
                        sendEvent = sendEvent
                    )

                    RtmpSettings.Values.Mode.CLIENT -> ClientMode(
                        clientStatus = clientStatus,
                        error = error,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

//            BetaCornerBadge(
//                modifier = Modifier
//                    .align(Alignment.TopEnd)
//                    .zIndex(1f)
//            )
        }
    }
}

private val BetaBadgeContainerColor = Color(0xFFF6C343)
private val BetaBadgeTextColor = Color(0xFF2E2300)
private val BetaCornerShape = GenericShape { size, _ ->
    moveTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(0f, 0f)
    close()
}

@Composable
private fun BetaCornerBadge(
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(64.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = BetaCornerShape,
            color = BetaBadgeContainerColor,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp
        ) {}

        Text(
            text = "BETA",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = 28.dp)
                .graphicsLayer {
                    rotationZ = 45f
                    transformOrigin = TransformOrigin(1f, 0f)
                },
            style = MaterialTheme.typography.labelLarge,
            color = BetaBadgeTextColor
        )
    }
}

@Composable
private fun ModeSelector(
    selected: RtmpSettings.Values.Mode,
    onSelect: (RtmpSettings.Values.Mode) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val modes = listOf(RtmpSettings.Values.Mode.SERVER, RtmpSettings.Values.Mode.CLIENT)

    Column(modifier = modifier.fillMaxWidth()) {
        modes.forEach { mode ->
            val isSelected = selected == mode
            val labelRes = when (mode) {
                RtmpSettings.Values.Mode.SERVER -> R.string.rtmp_mode_server
                RtmpSettings.Values.Mode.CLIENT -> R.string.rtmp_mode_client
            }
            val helperRes = when (mode) {
                RtmpSettings.Values.Mode.SERVER -> R.string.rtmp_mode_server_hint
                RtmpSettings.Values.Mode.CLIENT -> R.string.rtmp_mode_client_hint
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { if (enabled) onSelect(mode) },
                        role = Role.RadioButton
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.padding(start = 8.dp),
                    enabled = enabled
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp)
                ) {
                    Text(
                        text = stringResource(id = labelRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(id = helperRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
