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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import io.screenstream.streaming.legacy.LegacyPermissionActionEffect
import io.screenstream.streaming.legacy.rememberLegacyPermissionActions
import io.screenstream.streaming.permission.LocalRuntimePermissions
import io.screenstream.streaming.permission.RuntimePermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
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
    val runtimePermissions = LocalRuntimePermissions.current
    val currentSendEvent by rememberUpdatedState(sendEvent)
    val permissionActions = rememberLegacyPermissionActions<PendingPermissionAction>()
    val updateSettings: (WebRtcSettings.Data.() -> WebRtcSettings.Data) -> Unit = { transform ->
        scope.launch { webRtcSettings.updateData(transform) }
    }

    fun changeAudioEnabled(isMicrophone: Boolean, enabled: Boolean) {
        val latestState = webRtcStateFlow.value
        val latestSettings = webRtcSettings.data.value
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        permissionActions.cancel()
        if (enabled) {
            val requestedPermission = if (isMicrophone) RuntimePermission.Microphone else RuntimePermission.DeviceAudio
            permissionActions.request(requestedPermission, PendingPermissionAction.EnableAudio(isMicrophone, latestSettings), runtimePermissions)
        } else {
            updateSettings { if (isMicrophone) copy(enableMic = false) else copy(enableDeviceAudio = false) }
        }
    }

    fun requestPermissionForError(permission: RuntimePermission) {
        val latestState = webRtcStateFlow.value
        val latestSettings = webRtcSettings.data.value
        val error = latestState.error ?: return
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return
        val matchesCurrentError = when (permission) {
            RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio -> error is WebRtcError.AudioPermissionRequired &&
                    !latestState.isStreaming && (latestSettings.enableMic || latestSettings.enableDeviceAudio)

            RuntimePermission.Notifications -> error is WebRtcError.NotificationPermissionRequired
            RuntimePermission.LocalNetwork -> false
        }
        if (!matchesCurrentError) return
        val requestedPermission = when {
            permission.androidPermission != RuntimePermission.RecordAudio.androidPermission -> permission
            latestSettings.enableMic && latestSettings.enableDeviceAudio -> RuntimePermission.RecordAudio
            latestSettings.enableMic -> RuntimePermission.Microphone
            else -> RuntimePermission.DeviceAudio
        }
        permissionActions.request(requestedPermission, PendingPermissionAction.RecoverError(permission, error, latestSettings), runtimePermissions)
    }

    fun isActionRelevant(action: PendingPermissionAction): Boolean {
        val latestState = webRtcStateFlow.value
        val latestSettings = webRtcSettings.data.value
        val audioSettingsUnchanged = latestSettings.enableMic == action.settingsAtRequest.enableMic &&
                latestSettings.enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return false
        return when (action) {
            is PendingPermissionAction.EnableAudio -> !latestState.isStreaming && audioSettingsUnchanged
            is PendingPermissionAction.RecoverError -> latestState.error === action.expectedError &&
                    (action.permission.androidPermission != RuntimePermission.RecordAudio.androidPermission ||
                            (!latestState.isStreaming && audioSettingsUnchanged &&
                                    (latestSettings.enableMic || latestSettings.enableDeviceAudio)))
        }
    }

    LegacyPermissionActionEffect(
        permissionActions, settings.enableMic, settings.enableDeviceAudio,
        state.isStreaming, state.waitingCastPermission, state.startAttemptId, state.error,
        isRelevant = ::isActionRelevant,
        onGranted = { action ->
            when (action) {
                is PendingPermissionAction.EnableAudio -> {
                    val effectJob = currentCoroutineContext().job
                    webRtcSettings.updateData {
                        val settingsUnchanged = enableMic == action.settingsAtRequest.enableMic &&
                                enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio
                        if (effectJob.isActive && permissionActions.isCurrent(action) && settingsUnchanged &&
                            runtimePermissions.isGranted(RuntimePermission.RecordAudio) && isActionRelevant(action)
                        ) {
                            if (action.isMicrophone) copy(enableMic = true) else copy(enableDeviceAudio = true)
                        } else this
                    }
                }

                is PendingPermissionAction.RecoverError -> {
                    if (action.permission.androidPermission == RuntimePermission.RecordAudio.androidPermission) screenCaptureStartRequester.request()
                    else currentSendEvent(WebRtcEvent.Intentable.RecoverError)
                }
            }
        }
    )

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionFlow(
            startRequester = screenCaptureStartRequester,
            permissionScopeKey = "webrtc",
            startAttemptId = state.startAttemptId?.takeIf { state.waitingCastPermission },
            onStartRequested = onStartRequested@{ educationShown ->
                if (state.isStreaming) return@onStartRequested
                sendEvent(
                    WebRtcStreamingService.InternalEvent.StartStream(
                        permissionEducationShown = educationShown,
                        clearStartupPolicyError = state.error?.isStartupPolicyError() == true
                    )
                )
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
                        hasEnabledAudioSource = settings.enableMic || settings.enableDeviceAudio,
                        retryStartupPolicyError = { screenCaptureStartRequester.request() },
                        requestAudioPermission = { requestPermissionForError(RuntimePermission.RecordAudio) },
                        requestNotificationPermission = requestNotificationPermission@{
                            if (webRtcStateFlow.value.error !is WebRtcError.NotificationPermissionRequired) return@requestNotificationPermission
                            if (runtimePermissions.isGranted(RuntimePermission.Notifications)) {
                                permissionActions.cancel()
                                sendEvent(WebRtcEvent.Intentable.RecoverError)
                                context.startActivity(notificationHelper.getStreamNotificationSettingsIntent())
                            } else {
                                requestPermissionForError(RuntimePermission.Notifications)
                            }
                        },
                        modifier = Modifier.padding(8.dp),
                        showRecoverAction = !(state.isStreaming && it is WebRtcError.SignalingServerUnavailable)
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
                    enabled = !state.isStreaming && !state.waitingCastPermission && state.startAttemptId == null,
                    settings = settings,
                    onMicEnabledChange = { changeAudioEnabled(isMicrophone = true, enabled = it) },
                    onDeviceAudioEnabledChange = { changeAudioEnabled(isMicrophone = false, enabled = it) },
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
            enabled = state.isStreaming || state.isBusy.not(),
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

private sealed interface PendingPermissionAction {
    val settingsAtRequest: WebRtcSettings.Data

    class EnableAudio(
        val isMicrophone: Boolean,
        override val settingsAtRequest: WebRtcSettings.Data
    ) : PendingPermissionAction

    class RecoverError(
        val permission: RuntimePermission,
        val expectedError: WebRtcError,
        override val settingsAtRequest: WebRtcSettings.Data
    ) : PendingPermissionAction
}
