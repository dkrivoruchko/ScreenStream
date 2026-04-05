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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessStarted
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.ScreenCapturePermissionWithEducation
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.common.ui.rememberScreenCapturePermissionWithEducationState
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.RtspModuleService
import info.dvkr.screenstream.rtsp.internal.RtspEvent
import info.dvkr.screenstream.rtsp.internal.RtspStreamingService
import info.dvkr.screenstream.rtsp.internal.rtsp.RtspUrl
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.cards.AudioCard
import info.dvkr.screenstream.rtsp.ui.main.cards.ClientSettingsCard
import info.dvkr.screenstream.rtsp.ui.main.cards.ErrorCard
import info.dvkr.screenstream.rtsp.ui.main.cards.ModeCard
import info.dvkr.screenstream.rtsp.ui.main.cards.ServerSettingsCard
import info.dvkr.screenstream.rtsp.ui.main.cards.VideoCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun RtspMainScreenUI(
    rtspStateFlow: StateFlow<RtspState>,
    sendEvent: (event: RtspEvent) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject(),
    notificationHelper: NotificationHelper = koinInject()
) {
    val rtspState = rtspStateFlow.collectAsStateWithLifecycle()
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val screenCapturePermissionWithEducationState = rememberScreenCapturePermissionWithEducationState()
    val context = LocalContext.current
    val state = rtspState.value
    val settings = rtspSettingsState.value
    val selectedMode = settings.mode
    val updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit = { transform ->
        scope.launch { rtspSettings.updateData(transform) }
    }

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionWithEducation(
            state = screenCapturePermissionWithEducationState,
            shouldRequestPermission = state.waitingCastPermission,
            isStreaming = state.isStreaming,
            onStartRequested = { educationShown -> sendEvent(RtspStreamingService.InternalEvent.StartStream(permissionEducationShown = educationShown)) },
            onPermissionGranted = { intent -> if (state.waitingCastPermission) RtspModuleService.startProjection(context, intent) },
            onPermissionDenied = { if (state.waitingCastPermission) sendEvent(RtspEvent.CastPermissionsDenied) },
        )

        val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (this.maxWidth >= 800.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
        ) {
            if (state.error is RtspError.UnknownError || state.error is RtspError.NotificationPermissionRequired) {
                item(key = "ERROR") {
                    ErrorCard(
                        error = state.error,
                        sendEvent = sendEvent,
                        openNotificationSettings = {
                            context.startActivity(notificationHelper.getStreamNotificationSettingsIntent())
                        },
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item(key = "MODE") {
                ModeCard(
                    sendEvent = sendEvent,
                    selectedMode = selectedMode,
                    onModeSelected = { mode -> updateSettings { copy(mode = mode) } },
                    isStreaming = state.isStreaming,
                    serverBindings = state.serverBindings,
                    clientStatus = state.clientStatus,
                    error = state.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            if (selectedMode == RtspSettings.Values.Mode.SERVER) {
                item(key = "SERVER_PARAMETERS") {
                    ServerSettingsCard(
                        settings = settings,
                        updateSettings = updateSettings,
                        windowWidthSizeClass = windowWidthSizeClass,
                        enabled = state.isStreaming.not(),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            if (selectedMode == RtspSettings.Values.Mode.CLIENT) {
                item(key = "CLIENT_PARAMETERS") {
                    ClientSettingsCard(
                        settings = settings,
                        updateSettings = updateSettings,
                        windowWidthSizeClass = windowWidthSizeClass,
                        enabled = state.isStreaming.not(),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            item(key = "VIDEO") {
                VideoCard(
                    isStreaming = state.isStreaming,
                    selectedVideoEncoder = state.selectedVideoEncoder,
                    settings = settings,
                    updateSettings = updateSettings,
                    modifier = Modifier.padding(8.dp)
                )
            }
            item(key = "AUDIO") {
                AudioCard(
                    isStreaming = state.isStreaming,
                    selectedAudioEncoder = state.selectedAudioEncoder,
                    settings = settings,
                    updateSettings = updateSettings,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LaunchedEffect(state.error) {
            if (state.error != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        val doubleClickProtection = remember { DoubleClickProtection.get() }

        val mediaServerUrlError = selectedMode == RtspSettings.Values.Mode.CLIENT &&
                runCatching { RtspUrl.parse(settings.serverAddress) }.isFailure

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (state.isStreaming) {
                        sendEvent(RtspEvent.Intentable.StopStream("User action: Button"))
                    } else {
                        screenCapturePermissionWithEducationState.requestStart()
                    }
                }
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .align(alignment = Alignment.BottomCenter),
            enabled = state.isBusy.not() && mediaServerUrlError.not(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.0.dp,
                pressedElevation = 3.0.dp,
                focusedElevation = 3.0.dp,
                hoveredElevation = 6.0.dp
            )
        ) {
            Crossfade(targetState = state.isStreaming, label = "StreamingButtonCrossfade") { isStreaming ->
                Icon(
                    painter = painterResource(if (isStreaming) R.drawable.stop_24px else R.drawable.play_arrow_24px),
                    contentDescription = null
                )
            }
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(id = if (state.isStreaming) R.string.rtsp_stream_stop else R.string.rtsp_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
