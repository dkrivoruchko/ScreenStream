package info.dvkr.screenstream.webrtc.ui

import android.content.Intent
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
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.common.ui.mediaprojection.ScreenCapturePermissionFlow
import info.dvkr.screenstream.common.ui.mediaprojection.rememberScreenCaptureStartRequester
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.internal.WebRtcStreamingService
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.main.cards.AudioCard
import info.dvkr.screenstream.webrtc.ui.main.cards.ClientsCard
import info.dvkr.screenstream.webrtc.ui.main.cards.ErrorCard
import info.dvkr.screenstream.webrtc.ui.main.cards.OtherSettingsCard
import info.dvkr.screenstream.webrtc.ui.main.cards.StreamCard
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun WebRtcMainScreenUI(
    webRtcStateFlow: StateFlow<WebRtcState>,
    sendEvent: (event: WebRtcEvent) -> Unit,
    onProjectionGranted: (startAttemptId: String, intent: Intent) -> Unit,
    modifier: Modifier = Modifier,
    webRtcSettings: WebRtcSettings = koinInject(),
    notificationHelper: NotificationHelper = koinInject()
) {
    val webRtcState = webRtcStateFlow.collectAsStateWithLifecycle()
    val webRtcSettingsState = webRtcSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val screenCaptureStartRequester = rememberScreenCaptureStartRequester()
    val context = LocalContext.current
    val state = webRtcState.value
    val settings = webRtcSettingsState.value
    val updateSettings: (WebRtcSettings.Data.() -> WebRtcSettings.Data) -> Unit = { transform ->
        scope.launch { webRtcSettings.updateData(transform) }
    }

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionFlow(
            startRequester = screenCaptureStartRequester,
            permissionScopeKey = "webrtc",
            startAttemptId = state.startAttemptId?.takeIf { state.waitingCastPermission },
            onStartRequested = onStartRequested@{ educationShown ->
                if (state.isStreaming) return@onStartRequested
                sendEvent(WebRtcStreamingService.InternalEvent.StartStream(permissionEducationShown = educationShown))
            },
            onPermissionGranted = { startAttemptId, intent -> if (state.startAttemptId == startAttemptId) onProjectionGranted(startAttemptId, intent) },
            onPermissionDenied = { startAttemptId -> if (state.startAttemptId == startAttemptId) sendEvent(WebRtcEvent.CastPermissionsDenied(startAttemptId)) },
        )

        val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (this.maxWidth >= 800.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
        ) {
            state.error?.let {
                item(key = "ERROR") {
                    ErrorCard(
                        error = it,
                        sendEvent = sendEvent,
                        openNotificationSettings = {
                            context.startActivity(notificationHelper.getStreamNotificationSettingsIntent())
                        },
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item(key = "STREAM") {
                StreamCard(
                    state = state,
                    onGetNewStreamId = { sendEvent(WebRtcEvent.GetNewStreamId) }, //TODO notify user that this will disconnect all clients
                    onCreateNewPassword = { sendEvent(WebRtcEvent.CreateNewPassword) }, //TODO notify user that this will disconnect all clients
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "CLIENTS") {
                ClientsCard(
                    state = state,
                    onClientDisconnect = { clientId -> sendEvent(WebRtcEvent.RemoveClient(clientId, true, "User request")) },
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "AUDIO") {
                AudioCard(
                    isStreaming = state.isStreaming,
                    settings = settings,
                    updateSettings = updateSettings,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "OTHER_PARAMETERS") {
                OtherSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    enabled = state.isStreaming.not(),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LaunchedEffect(state.error) {
            if (state.error != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        val doubleClickProtection = remember { DoubleClickProtection.get() }

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (state.isStreaming) {
                        sendEvent(WebRtcEvent.Intentable.StopStream("User action: Button"))
                    } else {
                        screenCaptureStartRequester.request()
                    }
                }
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .align(alignment = Alignment.BottomCenter),
            enabled = state.isBusy.not(),
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 3.0.dp,
                pressedElevation = 3.0.dp,
                focusedElevation = 3.0.dp,
                hoveredElevation = 6.0.dp,
            )
        ) {
            Crossfade(targetState = state.isStreaming, label = "StreamingButtonCrossfade") { isStreaming ->
                Icon(
                    painter = if (isStreaming) painterResource(R.drawable.stop_24px) else painterResource(R.drawable.play_arrow_24px),
                    contentDescription = null
                )
            }
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(
                text = stringResource(id = if (state.isStreaming) R.string.webrtc_stream_stop else R.string.webrtc_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
