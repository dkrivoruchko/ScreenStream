package info.dvkr.screenstream.rtsp.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessStarted
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.MediaProjectionPermission
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.internal.RtspEvent
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.AudioCard
import info.dvkr.screenstream.rtsp.ui.main.ErrorCard
import info.dvkr.screenstream.rtsp.ui.main.ModeCard
import info.dvkr.screenstream.rtsp.ui.main.VideoCard
import info.dvkr.screenstream.rtsp.ui.main.client.ClientParametersCard
import info.dvkr.screenstream.rtsp.ui.main.server.ServerParametersCard
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.koinInject

@Composable
internal fun RtspMainScreenUI(
    rtspStateFlow: StateFlow<RtspState>,
    sendEvent: (event: RtspEvent) -> Unit,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject()
) {
    val rtspState = rtspStateFlow.collectAsStateWithLifecycle()
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = modifier) {
        MediaProjectionPermission(
            shouldRequestPermission = rtspState.value.waitingCastPermission,
            onPermissionGranted = { intent -> if (rtspState.value.waitingCastPermission) sendEvent(RtspEvent.StartProjection(intent)) },
            onPermissionDenied = { if (rtspState.value.waitingCastPermission) sendEvent(RtspEvent.CastPermissionsDenied) },
            requiredDialogTitle = stringResource(id = R.string.rtsp_cast_permission_required_title),
            requiredDialogText = stringResource(id = R.string.rtsp_cast_permission_required)
        )

        val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (this.maxWidth >= 800.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
        ) {
            if (rtspState.value.error is RtspError.UnknownError || rtspState.value.error is RtspError.NotificationPermissionRequired) {
                item(key = "ERROR") {
                    val error = rtspState.value.error as RtspError
                    ErrorCard(error = error, sendEvent = sendEvent, modifier = Modifier.padding(8.dp))
                }
            }

            item(key = "MODE") {
                ModeCard(rtspState = rtspState, sendEvent = sendEvent, modifier = Modifier.padding(8.dp))
            }
            if (rtspSettingsState.value.mode == RtspSettings.Values.Mode.SERVER) {
                item(key = "SERVER_PARAMETERS") {
                    ServerParametersCard(rtspState = rtspState, modifier = Modifier.padding(8.dp))
                }
            }
            if (rtspSettingsState.value.mode == RtspSettings.Values.Mode.CLIENT) {
                item(key = "CLIENT_PARAMETERS") {
                    ClientParametersCard(rtspState = rtspState, modifier = Modifier.padding(8.dp))
                }
            }
            item(key = "VIDEO") {
                VideoCard(rtspState = rtspState, modifier = Modifier.padding(8.dp))
            }
            item(key = "AUDIO") {
                AudioCard(rtspState = rtspState, modifier = Modifier.padding(8.dp))
            }
        }

        LaunchedEffect(rtspState.value.error) {
            if (rtspState.value.error != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        val doubleClickProtection = remember { DoubleClickProtection.get() }

        val mediaServerUrlError = rtspState.value.mode == RtspSettings.Values.Mode.CLIENT &&
                runCatching { RtspUrl.parse(rtspSettingsState.value.serverAddress) }.isFailure

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (rtspState.value.isStreaming) sendEvent(RtspEvent.Intentable.StopStream("User action: Button"))
                    else sendEvent(RtspStreamingService.InternalEvent.StartStream)
                }
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .align(alignment = Alignment.BottomCenter),
            enabled = rtspState.value.isBusy.not() && mediaServerUrlError.not(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.0.dp,
                pressedElevation = 3.0.dp,
                focusedElevation = 3.0.dp,
                hoveredElevation = 6.0.dp
            )
        ) {
            Crossfade(targetState = rtspState.value.isStreaming, label = "StreamingButtonCrossfade") { isStreaming ->
                Icon(
                    painter = painterResource(if (isStreaming) R.drawable.stop_24px else R.drawable.play_arrow_24px),
                    contentDescription = null
                )
            }
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(id = if (rtspState.value.isStreaming) R.string.rtsp_stream_stop else R.string.rtsp_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
