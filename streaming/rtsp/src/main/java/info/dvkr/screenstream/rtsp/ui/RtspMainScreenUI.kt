package info.dvkr.screenstream.rtsp.ui

import android.content.ActivityNotFoundException
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessStarted
import info.dvkr.screenstream.rtsp.R
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
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.Section
import io.screenstream.core.module.StreamingModule.Ui.Section.Role
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid
import io.screenstream.core.R as CoreR

@Composable
internal fun RtspMainScreenUI(
    instanceId: StreamingModule.InstanceId,
    rtspStateFlow: StateFlow<RtspState>,
    sendEvent: (event: RtspEvent) -> Unit,
    claimInitialLocalNetworkRequest: (request: () -> Boolean) -> Unit,
    layout: @Composable (StreamingModule.Ui) -> Unit,
    rtspSettings: RtspSettings = koinInject(),
    notifications: StreamingModuleNotifications = koinInject()
) {
    val rtspState = rtspStateFlow.collectAsStateWithLifecycle()
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val log: Logger = koinInject(parametersHolder = remember(instanceId) { parametersOf(instanceId) })
    val state = rtspState.value
    val settings = rtspSettingsState.value
    val selectedMode = settings.mode
    val runtimePermissions = LocalRuntimePermissions.current
    val currentSendEvent by rememberUpdatedState(sendEvent)
    val currentClaimInitialLocalNetworkRequest by rememberUpdatedState(claimInitialLocalNetworkRequest)
    val permissionActions = rememberRtspPermissionActions<PendingPermissionAction>(instanceId)
    val localNetworkRecovery = retain { LocalNetworkRecovery() }
    val localNetworkPermissionState by runtimePermissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle()
    val notificationPermissionState by runtimePermissions.state(RuntimePermission.Notifications).collectAsStateWithLifecycle()
    val audioPermissionState by runtimePermissions.state(RuntimePermission.RecordAudio).collectAsStateWithLifecycle()
    val updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit = { transform ->
        scope.launch { rtspSettings.updateData(transform) }
    }

    fun requestStart(expectedError: RtspError? = null, expectedToken: Uuid? = null) {
        val latest = rtspStateFlow.value
        if (latest.captureAttempt != null) return
        if (expectedError != null && (latest.error !== expectedError || expectedToken == null || latest.errorToken != expectedToken)) return
        currentSendEvent(
            RtspStreamingService.InternalEvent.StartStream(
                clearStartupPolicyError = latest.error?.isStartupPolicyError() == true,
                expectedErrorToken = expectedToken,
            )
        )
    }

    fun recoverGrantedLocalNetworkError(allowStreaming: Boolean = false, expectedRecovery: PendingPermissionAction.RecoverError? = null) {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        val error = latestState.error
        if (error !is RtspError.LocalNetworkPermissionRequired || error === localNetworkRecovery.error) return
        val errorToken = latestState.errorToken ?: return
        if (expectedRecovery != null && (error !== expectedRecovery.expectedError || errorToken != expectedRecovery.errorToken)) return
        if ((!allowStreaming && latestState.isStreaming) || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode || !runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        localNetworkRecovery.error = error
        currentSendEvent(RtspEvent.RecoverError(errorToken))
    }

    fun changeAudioEnabled(isMicrophone: Boolean, enabled: Boolean) {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
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

    fun requestPermissionForError(permission: RuntimePermission, expectedError: RtspError, expectedToken: Uuid?) {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        val error = latestState.error ?: return
        val errorToken = latestState.errorToken ?: return
        if (error !== expectedError || errorToken != expectedToken) return
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        val matchesCurrentError = when (permission) {
            RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio -> error is RtspError.AudioPermissionRequired &&
                    !latestState.isStreaming && (latestSettings.enableMic || latestSettings.enableDeviceAudio)

            RuntimePermission.Notifications -> error is RtspError.NotificationPermissionRequired
            RuntimePermission.LocalNetwork -> error is RtspError.LocalNetworkPermissionRequired
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
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        if (permissionActions.pending != null || localNetworkPermissionState == PermissionState.SettingsSuggested) return
        if (runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        // Both server discovery and client connections require LAN access in the current RTSP pipeline.
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
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
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

    RtspPermissionActionEffect(
        permissionActions, runtimePermissions, settings.enableMic, settings.enableDeviceAudio, selectedMode, state.mode,
        state.isStreaming, state.waitingCastPermission, state.startAttemptId, state.error,
        isRelevant = ::isActionRelevant,
        onGranted = { action ->
            when (action) {
                is PendingPermissionAction.EnableAudio -> {
                    val effectJob = currentCoroutineContext().job
                    rtspSettings.updateData {
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
                    RuntimePermission.Notifications -> currentSendEvent(RtspEvent.RecoverError(action.errorToken))
                }
            }
        }
    )

    fun retryConsent(token: Uuid, retry: Boolean) {
        if (rtspStateFlow.value.consentRetryToken == token) {
            currentSendEvent(RtspEvent.RetryConsent(token, retry))
        }
    }

    state.consentRetryToken?.let { retryToken ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { retryConsent(retryToken, retry = true) }) {
                    Text(stringResource(R.string.rtsp_error_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { retryConsent(retryToken, retry = false) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(CoreR.string.core_screen_capture_permission_required_title)) },
            text = { Text(stringResource(R.string.rtsp_error_screen_capture_permission_denied_message)) },
        )
    }

    val modeSections = buildList {
        state.error?.takeIf { error ->
            error is RtspError.UnknownError ||
                    error is RtspError.NotificationPermissionRequired ||
                    error is RtspError.LocalNetworkPermissionRequired ||
                    error.isStartupPolicyError()
        }?.let { error ->
            add(Section("error", Role.Overview) {
                val errorVisibility = remember { BringIntoViewRequester() }
                var isErrorPlaced by remember { mutableStateOf(false) }
                LaunchedEffect(error, isErrorPlaced) {
                    if (isErrorPlaced) errorVisibility.bringIntoView()
                }
                val hasEnabledAudioSource = settings.enableMic || settings.enableDeviceAudio
                val errorPermissionState = when (error) {
                    is RtspError.AudioPermissionRequired -> if (hasEnabledAudioSource) audioPermissionState else null
                    is RtspError.NotificationPermissionRequired -> notificationPermissionState
                    is RtspError.LocalNetworkPermissionRequired -> localNetworkPermissionState
                    else -> null
                }
                ErrorCard(
                    error = error,
                    sendEvent = { event ->
                        val displayedToken = state.errorToken
                        val latest = rtspStateFlow.value
                        if (displayedToken != null && latest.error === error && latest.errorToken == displayedToken) {
                            currentSendEvent(if (event is RtspEvent.RecoverError) event.copy(errorToken = displayedToken) else event)
                        }
                    },
                    hasEnabledAudioSource = hasEnabledAudioSource,
                    permissionState = errorPermissionState,
                    retryStartupPolicyError = { requestStart(error, state.errorToken) },
                    requestAudioPermission = { requestPermissionForError(RuntimePermission.RecordAudio, error, state.errorToken) },
                    requestNotificationPermission = requestNotificationPermission@{
                        val latest = rtspStateFlow.value
                        val errorToken = latest.errorToken ?: return@requestNotificationPermission
                        if (latest.error !== error || errorToken != state.errorToken || error !is RtspError.NotificationPermissionRequired) {
                            return@requestNotificationPermission
                        }
                        if (runtimePermissions.isGranted(RuntimePermission.Notifications)) {
                            permissionActions.cancel()
                            currentSendEvent(RtspEvent.RecoverError(errorToken))
                            val settingsIntent = notifications.channelSettingsIntent(StreamingModuleNotifications.Channel.Streaming)
                            if (settingsIntent != null) {
                                try {
                                    context.startActivity(settingsIntent)
                                } catch (failure: ActivityNotFoundException) {
                                    log.w("NotificationSettings", "Settings Activity is unavailable", failure)
                                } catch (failure: SecurityException) {
                                    log.w("NotificationSettings", "Settings Activity launch was denied", failure)
                                }
                            }
                        } else {
                            requestPermissionForError(RuntimePermission.Notifications, error, errorToken)
                        }
                    },
                    requestLocalNetworkPermission = { requestPermissionForError(RuntimePermission.LocalNetwork, error, state.errorToken) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(errorVisibility)
                        .onPlaced { isErrorPlaced = true },
                )
            })
        }

        add(Section("mode", Role.Overview) {
            ModeCard(
                sendEvent = currentSendEvent,
                selectedMode = selectedMode,
                onSelectMode = { mode -> updateSettings { copy(mode = mode) } },
                isStreaming = state.captureAttempt != null,
                serverBindings = state.serverBindings,
                clientStatus = state.clientStatus,
                error = state.error,
                modifier = Modifier.fillMaxWidth(),
            )
        })
        when (selectedMode) {
            RtspSettings.Values.Mode.SERVER -> add(Section("server-parameters", Role.Settings) {
                ServerSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    enabled = state.captureAttempt == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            })

            RtspSettings.Values.Mode.CLIENT -> add(Section("client-parameters", Role.Settings) {
                ClientSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    enabled = state.captureAttempt == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            })
        }
        add(Section("video", Role.Settings) {
            VideoCard(
                isStreaming = state.captureAttempt != null,
                activeH264ProfileIdc = state.activeH264ProfileIdc,
                selectedVideoEncoder = state.selectedVideoEncoder,
                settings = settings,
                updateSettings = updateSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        })
        add(Section("audio", Role.Settings) {
            AudioCard(
                isStreaming = state.captureAttempt != null,
                selectedAudioEncoder = state.selectedAudioEncoder,
                settings = settings,
                updateSettings = updateSettings,
                onMicEnabledChange = { changeAudioEnabled(isMicrophone = true, enabled = it) },
                onDeviceAudioEnabledChange = { changeAudioEnabled(isMicrophone = false, enabled = it) },
                modifier = Modifier.fillMaxWidth(),
            )
        })
    }

    var lastClickTimeMs by remember(instanceId) { mutableStateOf<Long?>(null) }
    val captureEnabled = canToggleCapture(state, settings)
    fun toggleCapture() {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        if (latestState.captureAttempt != state.captureAttempt || latestState.isStreaming != state.isStreaming) return
        if (latestSettings.mode != selectedMode || !canToggleCapture(latestState, latestSettings)) return
        if (selectedMode == RtspSettings.Values.Mode.CLIENT && latestSettings.serverAddress != settings.serverAddress) return

        val now = SystemClock.elapsedRealtime()
        val previousClickTimeMs = lastClickTimeMs
        lastClickTimeMs = now
        if (previousClickTimeMs != null && now - previousClickTimeMs < 300L) return

        if (state.isStreaming) {
            state.captureAttempt?.let { currentSendEvent(RtspEvent.StopStream("User action: Button", it)) }
        } else {
            requestStart()
        }
    }

    layout(object : StreamingModule.Ui {
        override val sections: List<Section> = modeSections

        @Composable
        override fun CaptureActionButton(modifier: Modifier) {
            Button(
                onClick = dropUnlessStarted { toggleCapture() },
                modifier = modifier,
                enabled = captureEnabled,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 3.0.dp,
                    pressedElevation = 3.0.dp,
                    focusedElevation = 3.0.dp,
                    hoveredElevation = 6.0.dp,
                ),
            ) {
                Crossfade(targetState = state.isStreaming, label = "StreamingButtonCrossfade") { isStreaming ->
                    Icon(
                        painter = painterResource(if (isStreaming) R.drawable.stop_24px else R.drawable.play_arrow_24px),
                        contentDescription = null,
                    )
                }
                Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    text = stringResource(if (state.isStreaming) R.string.rtsp_stream_stop else R.string.rtsp_stream_start),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    })
}

private fun canToggleCapture(state: RtspState, settings: RtspSettings.Data): Boolean {
    return !(state.isBusy || state.mode != settings.mode || (state.captureAttempt != null && !state.isStreaming)) && (settings.mode != RtspSettings.Values.Mode.CLIENT || runCatching {
        RtspUrl.parse(
            settings.serverAddress
        )
    }.isSuccess)
}

private class LocalNetworkRecovery {
    var error: RtspError? = null
}

private sealed interface PendingPermissionAction {
    val settingsAtRequest: RtspSettings.Data

    class EnableAudio(
        val isMicrophone: Boolean,
        override val settingsAtRequest: RtspSettings.Data
    ) : PendingPermissionAction

    class LocalNetworkAssessment(
        override val settingsAtRequest: RtspSettings.Data
    ) : PendingPermissionAction

    class RecoverError(
        val permission: RuntimePermission,
        val expectedError: RtspError,
        val errorToken: Uuid,
        override val settingsAtRequest: RtspSettings.Data
    ) : PendingPermissionAction
}
