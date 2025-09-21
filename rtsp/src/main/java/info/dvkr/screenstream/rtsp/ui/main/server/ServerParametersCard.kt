package info.dvkr.screenstream.rtsp.ui.main.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ui.ExpandableCard
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.RtspState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerParametersCard(
    rtspState: State<RtspState>,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    if (rtspSettingsState.value.mode == RtspSettings.Values.Mode.CLIENT) {
        return
    }

    ExpandableCard(
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.rtsp_server_parameters),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        initiallyExpanded = false
    ) {
        val isStreaming = rtspState.value.isStreaming
        var showBottomSheetForItemId by remember { mutableStateOf<String?>(null) }

        RTPProtocol.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        )  { showBottomSheetForItemId = RTPProtocol.id }

        InterfaceFilter.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        ) { showBottomSheetForItemId = InterfaceFilter.id }

        AddressFilter.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        ) { showBottomSheetForItemId = AddressFilter.id }

        EnableIPv4.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        )

        EnableIPv6.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        )

        ServerPort.ItemUI(
            horizontalPadding = 0.dp,
            coroutineScope = scope,
            enabled = isStreaming.not()
        ) { showBottomSheetForItemId = ServerPort.id }

        if (showBottomSheetForItemId != null) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { showBottomSheetForItemId = null },
                sheetState = sheetState,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
                sheetMaxWidth = 480.dp,
                dragHandle = null
            ) {
                when (showBottomSheetForItemId) {
                    RTPProtocol.id -> RTPProtocol.DetailUI { title ->
                        BottomSheetTitle(title) {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { if (!sheetState.isVisible) showBottomSheetForItemId = null }
                        }
                    }

                    InterfaceFilter.id -> InterfaceFilter.DetailUI { title ->
                        BottomSheetTitle(title) {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { if (!sheetState.isVisible) showBottomSheetForItemId = null }
                        }
                    }

                    AddressFilter.id -> AddressFilter.DetailUI { title ->
                        BottomSheetTitle(title) {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { if (!sheetState.isVisible) showBottomSheetForItemId = null }
                        }
                    }

                    ServerPort.id -> ServerPort.DetailUI { title ->
                        BottomSheetTitle(title) {
                            scope.launch { sheetState.hide() }
                                .invokeOnCompletion { if (!sheetState.isVisible) showBottomSheetForItemId = null }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetTitle(
    title: String,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f),
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium
        )
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Default.Close, contentDescription = "Close") //TODO
        }
    }
}