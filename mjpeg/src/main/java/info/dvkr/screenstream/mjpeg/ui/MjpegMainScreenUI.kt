package info.dvkr.screenstream.mjpeg.ui

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
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.ui.main.ClientsCard
import info.dvkr.screenstream.mjpeg.ui.main.ErrorCard
import info.dvkr.screenstream.mjpeg.ui.main.InterfacesCard
import info.dvkr.screenstream.mjpeg.ui.main.PinCard
import info.dvkr.screenstream.mjpeg.ui.main.TrafficCard
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun MjpegMainScreenUI(
    mjpegStateFlow: StateFlow<MjpegState>,
    sendEvent: (event: MjpegEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val mjpegState = mjpegStateFlow.collectAsStateWithLifecycle()
    val isStreaming = remember { derivedStateOf { mjpegState.value.isStreaming } }
    val isBusy = remember { derivedStateOf { mjpegState.value.isBusy } }
    val waitingCastPermission = remember { derivedStateOf { mjpegState.value.waitingCastPermission } }
    val error = remember { derivedStateOf { mjpegState.value.error } }

    val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()

    BoxWithConstraints(modifier = modifier) {
        MediaProjectionPermission(
            requestCastPermission = waitingCastPermission.value,
            onPermissionGranted = { intent -> sendEvent(MjpegEvent.StartProjection(intent)) },
            onPermissionDenied = { sendEvent(MjpegEvent.CastPermissionsDenied) },
            requiredDialogTitle = stringResource(id = R.string.mjpeg_stream_cast_permission_required_title),
            requiredDialogText = stringResource(id = R.string.mjpeg_stream_cast_permission_required)
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

            item(key = "INTERFACES") {
                InterfacesCard(mjpegState = mjpegState, modifier = Modifier.padding(8.dp))
            }

            item(key = "PIN") { //TODO notify user that this will disconnect all clients
                PinCard(mjpegState = mjpegState, onCreateNewPin = { sendEvent(MjpegEvent.CreateNewPin) }, modifier = Modifier.padding(8.dp))
            }

            item(key = "TRAFFIC") {
                TrafficCard(mjpegState = mjpegState, modifier = Modifier.padding(8.dp))
            }

            item(key = "CLIENTS") {
                ClientsCard(mjpegState = mjpegState, modifier = Modifier.padding(8.dp))
            }
        }

        LaunchedEffect(error.value) {
            if (error.value != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        Button(
            onClick = {
                if (isStreaming.value) sendEvent(MjpegEvent.Intentable.StopStream("User action: Button"))
                else sendEvent(MjpegStreamingService.InternalEvent.StartStream)
            },
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp
                )
                .align(alignment = Alignment.BottomCenter),
            enabled = isBusy.value.not(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.0.dp,
                pressedElevation = 3.0.dp,
                focusedElevation = 3.0.dp,
                hoveredElevation = 6.0.dp
            )
        ) {
            Crossfade(targetState = isStreaming.value, label = "StreamingButtonCrossfade") { isStreaming ->
                Icon(imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            }
            Text(
                text = stringResource(id = if (isStreaming.value) R.string.mjpeg_stream_stop else R.string.mjpeg_stream_start),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
