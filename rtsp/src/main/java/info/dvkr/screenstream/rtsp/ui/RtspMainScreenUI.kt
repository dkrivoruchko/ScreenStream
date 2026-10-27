package info.dvkr.screenstream.rtsp.ui

import android.content.ActivityNotFoundException
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
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
import info.dvkr.screenstream.common.ui.mediaprojection.ScreenCapturePermissionFlow
import info.dvkr.screenstream.common.ui.mediaprojection.rememberScreenCaptureStartRequester
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
import io.screenstream.streaming.legacy.LegacyPermissionActionEffect
import io.screenstream.streaming.legacy.rememberLegacyPermissionActions
import io.screenstream.streaming.module.StreamingModuleNotifications
import io.screenstream.streaming.permission.LocalRuntimePermissions
import io.screenstream.streaming.permission.PermissionState
import io.screenstream.streaming.permission.RuntimePermission
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun RtspMainScreenUI(
    rtspStateFlow: StateFlow<RtspState>,
    sendEvent: (event: RtspEvent) -> Unit,
    onProjectionGranted: (startAttemptId: String, intent: Intent) -> Unit,
    windowSizeClass: WindowSizeClass,
    claimInitialLocalNetworkRequest: (request: () -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
    rtspSettings: RtspSettings = koinInject(),
    notifications: StreamingModuleNotifications = koinInject()
) {
    val rtspState = rtspStateFlow.collectAsStateWithLifecycle()
    val rtspSettingsState = rtspSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val screenCaptureStartRequester = rememberScreenCaptureStartRequester()
    val context = LocalContext.current
    val state = rtspState.value
    val settings = rtspSettingsState.value
    val selectedMode = settings.mode
    val runtimePermissions = LocalRuntimePermissions.current
    val currentSendEvent by rememberUpdatedState(sendEvent)
    val currentClaimInitialLocalNetworkRequest by rememberUpdatedState(claimInitialLocalNetworkRequest)
    val permissionActions = rememberLegacyPermissionActions<PendingPermissionAction>()
    var recoveredLocalNetworkError by retain { mutableStateOf<RtspError?>(null) }
    val localNetworkPermissionState by runtimePermissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle()
    val notificationPermissionState by runtimePermissions.state(RuntimePermission.Notifications).collectAsStateWithLifecycle()
    val audioPermissionState by runtimePermissions.state(RuntimePermission.RecordAudio).collectAsStateWithLifecycle()
    val updateSettings: (RtspSettings.Data.() -> RtspSettings.Data) -> Unit = { transform ->
        scope.launch { rtspSettings.updateData(transform) }
    }

    fun recoverGrantedLocalNetworkError(allowStreaming: Boolean = false) {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        val error = latestState.error
        if (error !is RtspError.LocalNetworkPermissionRequired || error === recoveredLocalNetworkError) return
        if ((!allowStreaming && latestState.isStreaming) || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode || !runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        recoveredLocalNetworkError = error
        currentSendEvent(RtspEvent.Intentable.RecoverError)
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

    fun requestPermissionForError(permission: RuntimePermission) {
        val latestState = rtspStateFlow.value
        val latestSettings = rtspSettings.data.value
        val error = latestState.error ?: return
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
            permissionActions.request(requestedPermission, PendingPermissionAction.RecoverError(permission, error, latestSettings), runtimePermissions)
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
            is PendingPermissionAction.RecoverError -> latestState.error === action.expectedError &&
                    (action.permission.androidPermission != RuntimePermission.RecordAudio.androidPermission ||
                            (!latestState.isStreaming && audioSettingsUnchanged &&
                                    (latestSettings.enableMic || latestSettings.enableDeviceAudio)))
        }
    }

    LegacyPermissionActionEffect(
        permissionActions, settings.enableMic, settings.enableDeviceAudio, selectedMode, state.mode,
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
                    RuntimePermission.RecordAudio, RuntimePermission.Microphone, RuntimePermission.DeviceAudio -> screenCaptureStartRequester.request()
                    RuntimePermission.LocalNetwork -> recoverGrantedLocalNetworkError(allowStreaming = true)
                    RuntimePermission.Notifications -> currentSendEvent(RtspEvent.Intentable.RecoverError)
                }
            }
        }
    )

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionFlow(
            startRequester = screenCaptureStartRequester,
            permissionScopeKey = "rtsp",
            startAttemptId = state.startAttemptId?.takeIf { state.waitingCastPermission },
            onStartRequested = onStartRequested@{ educationShown ->
                if (state.isStreaming) return@onStartRequested
                sendEvent(
                    RtspStreamingService.InternalEvent.StartStream(
                        permissionEducationShown = educationShown,
                        clearStartupPolicyError = state.error?.isStartupPolicyError() == true
                    )
                )
            },
            onPermissionGranted = { startAttemptId, intent -> if (state.startAttemptId == startAttemptId) onProjectionGranted(startAttemptId, intent) },
            onPermissionDenied = { startAttemptId -> if (state.startAttemptId == startAttemptId) sendEvent(RtspEvent.CastPermissionsDenied(startAttemptId)) },
        )

        val lazyVerticalStaggeredGridState = rememberLazyStaggeredGridState()
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(if (this.maxWidth >= 800.dp) 2 else 1),
            modifier = Modifier.fillMaxSize(),
            state = lazyVerticalStaggeredGridState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 64.dp),
        ) {
            state.error?.takeIf { error ->
                error is RtspError.UnknownError ||
                        error is RtspError.NotificationPermissionRequired ||
                        error is RtspError.LocalNetworkPermissionRequired ||
                        error.isStartupPolicyError()
            }?.let { error ->
                item(key = "ERROR") {
                    ErrorCard(
                        error = error,
                        sendEvent = sendEvent,
                        hasEnabledAudioSource = settings.enableMic || settings.enableDeviceAudio,
                        retryStartupPolicyError = { screenCaptureStartRequester.request() },
                        requestAudioPermission = { requestPermissionForError(RuntimePermission.RecordAudio) },
                        requestNotificationPermission = requestNotificationPermission@{
                            if (rtspStateFlow.value.error !is RtspError.NotificationPermissionRequired) return@requestNotificationPermission
                            if (runtimePermissions.isGranted(RuntimePermission.Notifications)) {
                                permissionActions.cancel()
                                sendEvent(RtspEvent.Intentable.RecoverError)
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
                        requestLocalNetworkPermission = { requestPermissionForError(RuntimePermission.LocalNetwork) },
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
                        windowSizeClass = windowSizeClass,
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
                        windowSizeClass = windowSizeClass,
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
                    isStreaming = state.isStreaming || state.waitingCastPermission || state.startAttemptId != null,
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

        val mediaServerUrlError = selectedMode == RtspSettings.Values.Mode.CLIENT &&
                runCatching { RtspUrl.parse(settings.serverAddress) }.isFailure

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (state.isStreaming) {
                        sendEvent(RtspEvent.Intentable.StopStream("User action: Button"))
                    } else {
                        screenCaptureStartRequester.request()
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
        override val settingsAtRequest: RtspSettings.Data
    ) : PendingPermissionAction
}
