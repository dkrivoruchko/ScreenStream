package info.dvkr.screenstream.webrtc.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.ui.MediaProjectionPermission
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.ui.main.AudioCard
import info.dvkr.screenstream.webrtc.ui.main.ClientsCard
import info.dvkr.screenstream.webrtc.ui.main.ErrorCard
import info.dvkr.screenstream.webrtc.ui.main.StreamCard
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun WebRtcMainScreenUI(
    webRtcStateFlow: StateFlow<WebRtcState>,
    sendEvent: (event: WebRtcEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val webRtcState = webRtcStateFlow.collectAsStateWithLifecycle()
    val isStreaming = remember { derivedStateOf { webRtcState.value.isStreaming } }
    val isBusy = remember { derivedStateOf { webRtcState.value.isBusy } }
    val waitingCastPermission = remember { derivedStateOf { webRtcState.value.waitingCastPermission } }
    val error = remember { derivedStateOf { webRtcState.value.error } }

    val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()

    BoxWithConstraints(modifier = modifier) {
        MediaProjectionPermission(
            requestCastPermission = waitingCastPermission.value,
            onPermissionGranted = { intent -> sendEvent(WebRtcEvent.StartProjection(intent)) },
            onPermissionDenied = { sendEvent(WebRtcEvent.CastPermissionsDenied) },
            requiredDialogTitle = stringResource(id = R.string.webrtc_stream_cast_permission_required_title),
            requiredDialogText = stringResource(id = R.string.webrtc_stream_cast_permission_required)
        )

        LazyVerticalStaggeredGrid(
            columns = remember(maxWidth) { StaggeredGridCells.Fixed(if (maxWidth >= 800.dp) 2 else 1) },
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
        ) {
            error.value?.let {
                item(key = "ERROR") {
                    ErrorCard(error = it, modifier = Modifier.padding(8.dp), sendEvent = sendEvent)
                }
            }

            item(key = "STREAM") {
                StreamCard(
                    webRtcState = webRtcState,
                    onGetNewStreamId = { sendEvent(WebRtcEvent.GetNewStreamId) }, //TODO notify user that this will disconnect all clients
                    onCreateNewPassword = { sendEvent(WebRtcEvent.CreateNewPassword) }, //TODO notify user that this will disconnect all clients
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "AUDIO") {
                AudioCard(
                    webRtcState = webRtcState,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "CLIENTS") {
                ClientsCard(
                    webRtcState = webRtcState,
                    onClientDisconnect = { clientId -> sendEvent(WebRtcEvent.RemoveClient(clientId, true, "User request")) },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LaunchedEffect(error.value) {
            if (error.value != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        Button(
            onClick = {
                if (isStreaming.value) sendEvent(WebRtcEvent.Intentable.StopStream("User action: Button"))
                else sendEvent(WebRtcStreamingService.InternalEvent.StartStream)
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .align(alignment = Alignment.BottomCenter),
            enabled = isBusy.value.not(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.0.dp,
                pressedElevation = 3.0.dp,
                focusedElevation = 3.0.dp,
                hoveredElevation = 6.0.dp,
            )
        ) {
            Crossfade(targetState = isStreaming.value, label = "StreamingButtonCrossfade") { isStreaming ->
                Icon(imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            }
            Text(
                text = stringResource(id = if (isStreaming.value) R.string.webrtc_stream_stop else R.string.webrtc_stream_start),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}