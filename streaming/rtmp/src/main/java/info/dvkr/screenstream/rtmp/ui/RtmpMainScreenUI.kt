package info.dvkr.screenstream.rtmp.ui

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessStarted
import androidx.window.core.layout.WindowSizeClass
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.rtmp.R
import info.dvkr.screenstream.rtmp.internal.RtmpEvent
import info.dvkr.screenstream.rtmp.internal.RtmpStreamingService
import info.dvkr.screenstream.rtmp.internal.rtmp.RtmpUrl
import info.dvkr.screenstream.rtmp.settings.RtmpSettings
import info.dvkr.screenstream.rtmp.ui.main.cards.AudioCard
import info.dvkr.screenstream.rtmp.ui.main.cards.ClientSettingsCard
import info.dvkr.screenstream.rtmp.ui.main.cards.ErrorCard
import info.dvkr.screenstream.rtmp.ui.main.cards.ModeCard
import info.dvkr.screenstream.rtmp.ui.main.cards.ServerSettingsCard
import info.dvkr.screenstream.rtmp.ui.main.cards.VideoCard
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
internal fun RtmpMainScreenUI(
    instanceId: StreamingModule.InstanceId,
    rtmpStateFlow: StateFlow<RtmpState>,
    sendEvent: (event: RtmpEvent) -> Unit,
    windowSizeClass: WindowSizeClass,
    claimInitialLocalNetworkRequest: (request: () -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rtmpSettings: RtmpSettings = koinInject(),
    notifications: StreamingModuleNotifications = koinInject()
) {
    val rtmpState = rtmpStateFlow.collectAsStateWithLifecycle()
    val rtmpSettingsState = rtmpSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = rtmpState.value
    val settings = rtmpSettingsState.value
    val selectedMode = settings.mode
    val runtimePermissions = LocalRuntimePermissions.current
    val currentSendEvent by rememberUpdatedState(sendEvent)
    val currentClaimInitialLocalNetworkRequest by rememberUpdatedState(claimInitialLocalNetworkRequest)
    val permissionActions = rememberRtmpPermissionActions<PendingPermissionAction>(instanceId)
    val localNetworkRecovery = retain { LocalNetworkRecovery() }
    val localNetworkPermissionState by runtimePermissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle()
    val notificationPermissionState by runtimePermissions.state(RuntimePermission.Notifications).collectAsStateWithLifecycle()
    val audioPermissionState by runtimePermissions.state(RuntimePermission.RecordAudio).collectAsStateWithLifecycle()
    val updateSettings: (RtmpSettings.Data.() -> RtmpSettings.Data) -> Unit = { transform ->
        scope.launch { rtmpSettings.updateData(transform) }
    }

    fun requestStart(expectedError: RtmpError? = null, expectedToken: Uuid? = null) {
        val latest = rtmpStateFlow.value
        if (latest.captureAttempt != null) return
        if (expectedError != null && (latest.error !== expectedError || expectedToken == null || latest.errorToken != expectedToken)) return
        currentSendEvent(
            RtmpStreamingService.InternalEvent.StartStream(
                clearStartupPolicyError = latest.error?.isStartupPolicyError() == true,
                expectedErrorToken = expectedToken,
            )
        )
    }

    fun recoverGrantedLocalNetworkError(allowStreaming: Boolean = false, expectedRecovery: PendingPermissionAction.RecoverError? = null) {
        val latestState = rtmpStateFlow.value
        val latestSettings = rtmpSettings.data.value
        val error = latestState.error
        if (error !is RtmpError.LocalNetworkPermissionRequired || error === localNetworkRecovery.error) return
        val errorToken = latestState.errorToken ?: return
        if (expectedRecovery != null && (error !== expectedRecovery.expectedError || errorToken != expectedRecovery.errorToken)) return
        if ((!allowStreaming && latestState.isStreaming) || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode || !runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        localNetworkRecovery.error = error
        currentSendEvent(RtmpEvent.RecoverError(errorToken))
    }

    fun changeAudioEnabled(isMicrophone: Boolean, enabled: Boolean) {
        val latestState = rtmpStateFlow.value
        val latestSettings = rtmpSettings.data.value
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        permissionActions.cancel()
        if (enabled) {
            val requestedPermission = if (isMicrophone) RuntimePermission.Microphone else RuntimePermission.DeviceAudio
            permissionActions.request(requestedPermission, PendingPermissionAction.EnableAudio(isMicrophone, latestSettings), runtimePermissions)
        } else {
            updateSettings { if (isMicrophone) copy(enableMic = false) else copy(enableDeviceAudio = false) }
        }
    }

    fun requestPermissionForError(permission: RuntimePermission, expectedError: RtmpError, expectedToken: Uuid?) {
        val latestState = rtmpStateFlow.value
        val latestSettings = rtmpSettings.data.value
        val error = latestState.error ?: return
        val errorToken = latestState.errorToken ?: return
        if (error !== expectedError || errorToken != expectedToken) return
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        val matchesCurrentError = when (permission) {
            RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio -> error is RtmpError.AudioPermissionRequired &&
                    !latestState.isStreaming && (latestSettings.enableMic || latestSettings.enableDeviceAudio)

            RuntimePermission.Notifications -> error is RtmpError.NotificationPermissionRequired
            RuntimePermission.LocalNetwork -> error is RtmpError.LocalNetworkPermissionRequired
        }
        if (!matchesCurrentError) return
        val requestedPermission = when {
            permission.androidPermission != RuntimePermission.RecordAudio.androidPermission -> permission
            latestSettings.enableMic && latestSettings.enableDeviceAudio -> RuntimePermission.RecordAudio
            latestSettings.enableMic -> RuntimePermission.Microphone
            else -> RuntimePermission.DeviceAudio
        }
        val accepted =
            permissionActions.request(
                requestedPermission,
                PendingPermissionAction.RecoverError(permission, error, errorToken, latestSettings),
                runtimePermissions
            )
        if (accepted && permission == RuntimePermission.LocalNetwork) currentClaimInitialLocalNetworkRequest { true }
    }

    fun requestInitialLocalNetworkPermission() {
        recoverGrantedLocalNetworkError()
        val latestState = rtmpStateFlow.value
        val latestSettings = rtmpSettings.data.value
        if (permissionActions.pending != null || localNetworkPermissionState == PermissionState.SettingsSuggested) return
        if (runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        // Both server discovery and client connections require LAN access in the current RTMP pipeline.
        currentClaimInitialLocalNetworkRequest {
            permissionActions.request(RuntimePermission.LocalNetwork, PendingPermissionAction.LocalNetworkAssessment(latestSettings), runtimePermissions)
        }
    }

    SideEffect(
        localNetworkPermissionState, notificationPermissionState, audioPermissionState, permissionActions.pending, selectedMode, state.mode,
        state.error, state.isStreaming, state.waitingCastPermission, state.startAttemptId
    ) { requestInitialLocalNetworkPermission() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { requestInitialLocalNetworkPermission() }

    fun isActionRelevant(action: PendingPermissionAction): Boolean {
        val latestState = rtmpStateFlow.value
        val latestSettings = rtmpSettings.data.value
        val audioSettingsUnchanged = latestSettings.enableMic == action.settingsAtRequest.enableMic &&
                latestSettings.enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio
        val modeUnchanged = latestSettings.mode == action.settingsAtRequest.mode && latestState.mode == action.settingsAtRequest.mode
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return false
        if (action !is PendingPermissionAction.LocalNetworkAssessment && !modeUnchanged) return false
        return when (action) {
            is PendingPermissionAction.EnableAudio -> !latestState.isStreaming && audioSettingsUnchanged
            is PendingPermissionAction.LocalNetworkAssessment -> !latestState.isStreaming
            is PendingPermissionAction.RecoverError -> latestState.error === action.expectedError && latestState.errorToken == action.errorToken &&
                    (action.permission.androidPermission != RuntimePermission.RecordAudio.androidPermission ||
                            (!latestState.isStreaming && audioSettingsUnchanged &&
                                    (latestSettings.enableMic || latestSettings.enableDeviceAudio)))
        }
    }

    RtmpPermissionActionEffect(
        permissionActions, settings.enableMic, settings.enableDeviceAudio, selectedMode, state.mode,
        state.isStreaming, state.waitingCastPermission, state.startAttemptId, state.error,
        isRelevant = ::isActionRelevant,
        onGranted = { action ->
            when (action) {
                is PendingPermissionAction.EnableAudio -> {
                    val effectJob = currentCoroutineContext().job
                    rtmpSettings.updateData {
                        val settingsUnchanged = enableMic == action.settingsAtRequest.enableMic &&
                                enableDeviceAudio == action.settingsAtRequest.enableDeviceAudio && mode == action.settingsAtRequest.mode
                        if (effectJob.isActive && permissionActions.isCurrent(action) && settingsUnchanged &&
                            runtimePermissions.isGranted(RuntimePermission.RecordAudio) && isActionRelevant(action)
                        ) {
                            if (action.isMicrophone) copy(enableMic = true) else copy(enableDeviceAudio = true)
                        } else this
                    }
                }

                is PendingPermissionAction.LocalNetworkAssessment -> recoverGrantedLocalNetworkError()
                is PendingPermissionAction.RecoverError -> when (action.permission) {
                    RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio ->
                        requestStart(action.expectedError, action.errorToken)

                    RuntimePermission.LocalNetwork -> recoverGrantedLocalNetworkError(allowStreaming = true, expectedRecovery = action)
                    RuntimePermission.Notifications -> currentSendEvent(RtmpEvent.RecoverError(action.errorToken))
                }
            }
        }
    )

    BoxWithConstraints(modifier = modifier) {
        state.consentRetryToken?.let { retryToken ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {
                    TextButton(onClick = { sendEvent(RtmpEvent.RetryConsent(retryToken, retry = true)) }) {
                        Text(stringResource(CommonR.string.common_try_again))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sendEvent(RtmpEvent.RetryConsent(retryToken, retry = false)) }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
                title = { Text(stringResource(CommonR.string.common_screen_capture_permission_required_title)) },
                text = { Text(stringResource(CommonR.string.common_screen_capture_permission_denied_message)) },
            )
        }

        val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (this.maxWidth >= 800.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 64.dp),
        ) {
            state.error?.takeIf { error ->
                error is RtmpError.UnknownError ||
                        error is RtmpError.NotificationPermissionRequired ||
                        error is RtmpError.LocalNetworkPermissionRequired ||
                        error.isStartupPolicyError()
            }?.let { error ->
                item(key = "ERROR") {
                    ErrorCard(
                        error = error,
                        sendEvent = { event ->
                            val displayedToken = state.errorToken
                            val latest = rtmpStateFlow.value
                            if (displayedToken != null && latest.error === error && latest.errorToken == displayedToken) {
                                sendEvent(if (event is RtmpEvent.RecoverError) event.copy(errorToken = displayedToken) else event)
                            }
                        },
                        hasEnabledAudioSource = settings.enableMic || settings.enableDeviceAudio,
                        retryStartupPolicyError = { requestStart(error, state.errorToken) },
                        requestAudioPermission = { requestPermissionForError(RuntimePermission.RecordAudio, error, state.errorToken) },
                        requestNotificationPermission = requestNotificationPermission@{
                            val latest = rtmpStateFlow.value
                            val errorToken = latest.errorToken ?: return@requestNotificationPermission
                            if (latest.error !== error || errorToken != state.errorToken || error !is RtmpError.NotificationPermissionRequired) {
                                return@requestNotificationPermission
                            }
                            if (runtimePermissions.isGranted(RuntimePermission.Notifications)) {
                                permissionActions.cancel()
                                sendEvent(RtmpEvent.RecoverError(errorToken))
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
                                requestPermissionForError(RuntimePermission.Notifications, error, errorToken)
                            }
                        },
                        requestLocalNetworkPermission = { requestPermissionForError(RuntimePermission.LocalNetwork, error, state.errorToken) },
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item(key = "MODE") {
                ModeCard(
                    sendEvent = sendEvent,
                    selectedMode = selectedMode,
                    onModeSelected = { mode -> updateSettings { copy(mode = mode) } },
                    isStreaming = state.captureAttempt != null,
                    serverBindings = state.serverBindings,
                    clientStatus = state.clientStatus,
                    error = state.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            if (selectedMode == RtmpSettings.Values.Mode.SERVER) {
                item(key = "SERVER_PARAMETERS") {
                    ServerSettingsCard(
                        settings = settings,
                        updateSettings = updateSettings,
                        windowSizeClass = windowSizeClass,
                        enabled = state.captureAttempt == null,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            if (selectedMode == RtmpSettings.Values.Mode.CLIENT) {
                item(key = "CLIENT_PARAMETERS") {
                    ClientSettingsCard(
                        settings = settings,
                        updateSettings = updateSettings,
                        windowSizeClass = windowSizeClass,
                        enabled = state.captureAttempt == null,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            item(key = "VIDEO") {
                VideoCard(
                    isStreaming = state.captureAttempt != null,
                    activeH264ProfileIdc = state.activeH264ProfileIdc,
                    selectedVideoEncoder = state.selectedVideoEncoder,
                    settings = settings,
                    updateSettings = updateSettings,
                    modifier = Modifier.padding(8.dp)
                )
            }
            item(key = "AUDIO") {
                AudioCard(
                    isStreaming = state.captureAttempt != null,
                    selectedAudioEncoder = state.selectedAudioEncoder,
                    settings = settings,
                    updateSettings = updateSettings,
                    onMicEnabledChange = { changeAudioEnabled(isMicrophone = true, enabled = it) },
                    onDeviceAudioEnabledChange = { changeAudioEnabled(isMicrophone = false, enabled = it) },
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LaunchedEffect(state.error) {
            if (state.error != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        val doubleClickProtection = remember { DoubleClickProtection.get() }

        val mediaServerUrlError = selectedMode == RtmpSettings.Values.Mode.CLIENT &&
                runCatching { RtmpUrl.parse(settings.serverAddress) }.isFailure

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (state.isStreaming) {
                        state.captureAttempt?.let { sendEvent(RtmpEvent.StopStream("User action: Button", it)) }
                    } else {
                        requestStart()
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
                text = stringResource(id = if (state.isStreaming) R.string.rtmp_stream_stop else R.string.rtmp_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private class LocalNetworkRecovery {
    var error: RtmpError? = null
}

private sealed interface PendingPermissionAction {
    val settingsAtRequest: RtmpSettings.Data

    class EnableAudio(
        val isMicrophone: Boolean,
        override val settingsAtRequest: RtmpSettings.Data
    ) : PendingPermissionAction

    class LocalNetworkAssessment(
        override val settingsAtRequest: RtmpSettings.Data
    ) : PendingPermissionAction

    class RecoverError(
        val permission: RuntimePermission,
        val expectedError: RtmpError,
        val errorToken: Uuid,
        override val settingsAtRequest: RtmpSettings.Data
    ) : PendingPermissionAction
}
