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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
import info.dvkr.screenstream.rtsp.ui.main.MediaServerCard
import info.dvkr.screenstream.rtsp.ui.main.VideoCard
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
            if (rtspState.value.error is RtspError.UnknownError) {
                item(key = "ERROR") {
                    val error = rtspState.value.error as RtspError.UnknownError
                    ErrorCard(error = error, sendEvent = sendEvent, modifier = Modifier.padding(8.dp))
                }
            }

            item(key = "MediaServer") {
                MediaServerCard(rtspState = rtspState, modifier = Modifier.padding(8.dp))
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

        val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
        var mediaServerUrlError by remember(rtspSettingsState.value.serverAddress) {
            mutableStateOf(runCatching { RtspUrl.parse(rtspSettingsState.value.serverAddress) }.isFailure)
        }

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
                Icon(imageVector = if (isStreaming) Icon_Stop else Icon_PlayArrow, contentDescription = null)
            }
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(id = if (rtspState.value.isStreaming) R.string.rtsp_stream_stop else R.string.rtsp_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private val Icon_Stop: ImageVector = materialIcon(name = "Filled.Stop") {
    materialPath {
        moveTo(6.0f, 6.0f)
        horizontalLineToRelative(12.0f)
        verticalLineToRelative(12.0f)
        horizontalLineTo(6.0f)
        close()
    }
}

private val Icon_PlayArrow: ImageVector = materialIcon(name = "Filled.PlayArrow") {
    materialPath {
        moveTo(8.0f, 5.0f)
        verticalLineToRelative(14.0f)
        lineToRelative(11.0f, -7.0f)
        close()
    }
}