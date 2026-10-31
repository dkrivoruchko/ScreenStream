package info.dvkr.screenstream.webrtc.ui

import android.content.ActivityNotFoundException
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.dropUnlessStarted
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.webrtc.R
import info.dvkr.screenstream.webrtc.internal.WebRtcEvent
import info.dvkr.screenstream.webrtc.settings.WebRtcSettings
import info.dvkr.screenstream.webrtc.ui.main.cards.AudioCard
import info.dvkr.screenstream.webrtc.ui.main.cards.ClientsCard
import info.dvkr.screenstream.webrtc.ui.main.cards.ErrorCard
import info.dvkr.screenstream.webrtc.ui.main.cards.OtherSettingsCard
import info.dvkr.screenstream.webrtc.ui.main.cards.StreamCard
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import info.dvkr.screenstream.common.R as CommonR

@Composable
internal fun WebRtcMainScreenUI(
    instanceId: StreamingModule.InstanceId,
    webRtcStateFlow: StateFlow<WebRtcState>,
    sendEvent: (event: WebRtcEvent) -> Unit,
    requestStart: () -> Unit,
    requestStop: (StreamingModule.CaptureAttemptId) -> Unit,
    requestRecovery: (Uuid) -> Unit,
    dismissCaptureRetry: () -> Unit,
    modifier: Modifier = Modifier,
    webRtcSettings: WebRtcSettings = koinInject(),
    notifications: StreamingModuleNotifications = koinInject()
) {
    val webRtcState = webRtcStateFlow.collectAsStateWithLifecycle()
    val webRtcSettingsState = webRtcSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = webRtcState.value
    val settings = webRtcSettingsState.value
    val runtimePermissions = LocalRuntimePermissions.current
    var pendingPermission by retain(instanceId) { mutableStateOf<PendingPermissionRequest?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    fun recoverCurrentError() {
        webRtcStateFlow.value.errorToken?.let(requestRecovery)
    }

    fun requestPermission(permission: RuntimePermission, action: PendingPermissionAction) {
        if (runtimePermissions.tryRequest(permission)) pendingPermission = PendingPermissionRequest(permission, action)
    }

    val updateSettings: (WebRtcSettings.Data.() -> WebRtcSettings.Data) -> Unit = { transform ->
        scope.launch { webRtcSettings.updateData(transform) }
    }

    fun changeAudioEnabled(isMicrophone: Boolean, enabled: Boolean) {
        val latestState = webRtcStateFlow.value
        val latestSettings = webRtcSettings.data.value
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        pendingPermission = null
        if (enabled) {
            val requestedPermission = if (isMicrophone) RuntimePermission.Microphone else RuntimePermission.DeviceAudio
            requestPermission(requestedPermission, PendingPermissionAction.EnableAudio(isMicrophone, latestSettings))
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
        requestPermission(requestedPermission, PendingPermissionAction.RecoverError(permission, error, latestSettings))
    }

    fun isActionRelevant(action: PendingPermissionAction): Boolean {
        val latestState = webRtcStateFlow.value
        val latestSettings = webRtcSettings.data.value
        val audioSettingsUnchanged = latestSettings.enableMic == action.settingsAtRequest.enableMic &&
                latestSettings.enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio
        return !(latestState.waitingCastPermission || latestState.startAttemptId != null) && when (action) {
            is PendingPermissionAction.EnableAudio -> !latestState.isStreaming && audioSettingsUnchanged
            is PendingPermissionAction.RecoverError -> latestState.error === action.expectedError &&
                    (action.permission.androidPermission != RuntimePermission.RecordAudio.androidPermission ||
                            (!latestState.isStreaming && audioSettingsUnchanged &&
                                    (latestSettings.enableMic || latestSettings.enableDeviceAudio)))
        }
    }

    val permissionRequest = pendingPermission
    val permissionState = permissionRequest?.let { runtimePermissions.state(it.permission).collectAsStateWithLifecycle().value }
    LaunchedEffect(
        permissionRequest, permissionState, lifecycleState, settings.enableMic, settings.enableDeviceAudio,
        state.isStreaming, state.waitingCastPermission, state.startAttemptId, state.error
    ) {
        val request = permissionRequest ?: return@LaunchedEffect
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val action = request.action
        if (isActionRelevant(action)) {
            if (permissionState == PermissionState.Requesting) return@LaunchedEffect
            if (permissionState == PermissionState.Granted && runtimePermissions.isGranted(request.permission)) {
                when (action) {
                    is PendingPermissionAction.EnableAudio -> {
                        val effectJob = currentCoroutineContext().job
                        webRtcSettings.updateData {
                            val unchanged = enableMic == action.settingsAtRequest.enableMic && enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio
                            if (effectJob.isActive && pendingPermission?.action === action && unchanged &&
                                runtimePermissions.isGranted(RuntimePermission.RecordAudio) && isActionRelevant(action)
                            ) {
                                if (action.isMicrophone) copy(enableMic = true) else copy(enableDeviceAudio = true)
                            } else this
                        }
                    }

                    is PendingPermissionAction.RecoverError -> {
                        if (action.permission.androidPermission == RuntimePermission.RecordAudio.androidPermission) requestStart()
                        else recoverCurrentError()
                    }
                }
            }
        }
        if (pendingPermission === request) pendingPermission = null
    }

    BoxWithConstraints(modifier = modifier) {
        if (state.showCaptureRetry) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {
                    TextButton(onClick = {
                        dismissCaptureRetry()
                        requestStart()
                    }) { Text(stringResource(CommonR.string.common_try_again)) }
                },
                dismissButton = { TextButton(onClick = dismissCaptureRetry) { Text(stringResource(android.R.string.cancel)) } },
                title = { Text(stringResource(CommonR.string.common_screen_capture_permission_required_title)) },
                text = { Text(stringResource(CommonR.string.common_screen_capture_permission_denied_message)) },
            )
        }

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
                        recoverError = { state.errorToken?.let(requestRecovery) },
                        hasEnabledAudioSource = settings.enableMic || settings.enableDeviceAudio,
                        retryStartupPolicyError = { requestStart() },
                        requestAudioPermission = { requestPermissionForError(RuntimePermission.RecordAudio) },
                        requestNotificationPermission = requestNotificationPermission@{
                            if (webRtcStateFlow.value.error !is WebRtcError.NotificationPermissionRequired) return@requestNotificationPermission
                            if (runtimePermissions.isGranted(RuntimePermission.Notifications)) {
                                pendingPermission = null
                                recoverCurrentError()
                                val settingsIntent = notifications.channelSettingsIntent(StreamingModuleNotifications.Channel.Streaming)
                                if (settingsIntent != null) {
                                    try {
                                        context.startActivity(settingsIntent)
                                    } catch (failure: ActivityNotFoundException) {
                                        XLog.w(context.getLog("NotificationSettings", "Settings Activity is unavailable"), failure)
                                    } catch (failure: SecurityException) {
                                        XLog.w(context.getLog("NotificationSettings", "Settings Activity launch was denied"), failure)
                                    }
                                }
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
                        state.captureAttempt?.let(requestStop)
                    } else {
                        requestStart()
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

private class PendingPermissionRequest(val permission: RuntimePermission, val action: PendingPermissionAction)
