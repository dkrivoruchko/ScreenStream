package info.dvkr.screenstream.vnc.ui

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
import info.dvkr.screenstream.common.R as CommonR
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.vnc.R
import info.dvkr.screenstream.vnc.internal.VncEvent
import info.dvkr.screenstream.vnc.internal.VncStreamingService
import info.dvkr.screenstream.vnc.settings.VncSettings
import info.dvkr.screenstream.vnc.ui.main.cards.ClientSettingsCard
import info.dvkr.screenstream.vnc.ui.main.cards.EncodingCard
import info.dvkr.screenstream.vnc.ui.main.cards.ErrorCard
import info.dvkr.screenstream.vnc.ui.main.cards.ModeCard
import info.dvkr.screenstream.vnc.ui.main.cards.ServerSettingsCard
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModuleNotifications
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun VncMainScreenUI(
    instanceId: StreamingModule.InstanceId,
    vncStateFlow: StateFlow<VncState>,
    sendEvent: (event: VncEvent) -> Unit,
    windowSizeClass: WindowSizeClass,
    claimInitialLocalNetworkRequest: (request: () -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vncSettings: VncSettings = koinInject(),
    notifications: StreamingModuleNotifications = koinInject()
) {
    val vncState = vncStateFlow.collectAsStateWithLifecycle()
    val vncSettingsState = vncSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state = vncState.value
    val settings = vncSettingsState.value
    val selectedMode = settings.mode
    val runtimePermissions = LocalRuntimePermissions.current
    val currentSendEvent by rememberUpdatedState(sendEvent)
    val currentClaimInitialLocalNetworkRequest by rememberUpdatedState(claimInitialLocalNetworkRequest)
    val permissionActions = rememberVncPermissionActions<PendingPermissionAction>(instanceId)
    val localNetworkRecovery = retain { LocalNetworkRecovery() }
    val localNetworkPermissionState by runtimePermissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle()
    val notificationPermissionState by runtimePermissions.state(RuntimePermission.Notifications).collectAsStateWithLifecycle()
    val updateSettings: (VncSettings.Data.() -> VncSettings.Data) -> Unit = { transform ->
        scope.launch { vncSettings.updateData(transform) }
    }

    fun requestStart(expectedError: VncError? = null, expectedToken: Uuid? = null) {
        val latest = vncStateFlow.value
        if (latest.captureAttempt != null) return
        if (expectedError != null && (latest.error !== expectedError || expectedToken == null || latest.errorToken != expectedToken)) return
        currentSendEvent(
            VncStreamingService.InternalEvent.StartStream(
                clearStartupPolicyError = latest.error?.isStartupPolicyError() == true,
                expectedErrorToken = expectedToken,
            )
        )
    }

    fun recoverGrantedLocalNetworkError(allowStreaming: Boolean = false, expectedRecovery: PendingPermissionAction.RecoverError? = null) {
        val latestState = vncStateFlow.value
        val latestSettings = vncSettings.data.value
        val error = latestState.error
        if (error !is VncError.LocalNetworkPermissionRequired || error === localNetworkRecovery.error) return
        val errorToken = latestState.errorToken ?: return
        if (expectedRecovery != null && (error !== expectedRecovery.expectedError || errorToken != expectedRecovery.errorToken)) return
        if ((!allowStreaming && latestState.isStreaming) || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode || !runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        localNetworkRecovery.error = error
        currentSendEvent(VncEvent.RecoverError(errorToken))
    }

    fun requestPermissionForError(permission: RuntimePermission, expectedError: VncError, expectedToken: Uuid?) {
        val latestState = vncStateFlow.value
        val latestSettings = vncSettings.data.value
        val error = latestState.error ?: return
        val errorToken = latestState.errorToken ?: return
        if (error !== expectedError || errorToken != expectedToken) return
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        val matchesCurrentError = when (permission) {
            RuntimePermission.Notifications -> error is VncError.NotificationPermissionRequired
            RuntimePermission.LocalNetwork -> error is VncError.LocalNetworkPermissionRequired
            else -> false
        }
        if (!matchesCurrentError) return
        if (permission == RuntimePermission.Notifications && runtimePermissions.isGranted(RuntimePermission.Notifications)) {
            permissionActions.cancel()
            currentSendEvent(VncEvent.RecoverError(errorToken))
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
            return
        }
        permissionActions.request(permission, PendingPermissionAction.RecoverError(permission, error, errorToken, latestSettings), runtimePermissions)
        if (permission == RuntimePermission.LocalNetwork) currentClaimInitialLocalNetworkRequest { true }
    }

    fun requestInitialLocalNetworkPermission() {
        recoverGrantedLocalNetworkError()
        val latestState = vncStateFlow.value
        val latestSettings = vncSettings.data.value
        if (permissionActions.pending != null || localNetworkPermissionState == PermissionState.SettingsSuggested) return
        if (runtimePermissions.isGranted(RuntimePermission.LocalNetwork)) return
        if (latestState.isStreaming || latestState.waitingCastPermission || latestState.startAttemptId != null) return
        if (latestState.mode != latestSettings.mode) return
        // Both server discovery and client connections require LAN access in the VNC pipeline.
        currentClaimInitialLocalNetworkRequest {
            permissionActions.request(RuntimePermission.LocalNetwork, PendingPermissionAction.LocalNetworkAssessment(latestSettings), runtimePermissions)
        }
    }

    SideEffect(
        localNetworkPermissionState, notificationPermissionState, permissionActions.pending, selectedMode, state.mode,
        state.error, state.isStreaming, state.waitingCastPermission, state.startAttemptId
    ) { requestInitialLocalNetworkPermission() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { requestInitialLocalNetworkPermission() }

    fun isActionRelevant(action: PendingPermissionAction): Boolean {
        val latestState = vncStateFlow.value
        val latestSettings = vncSettings.data.value
        val modeUnchanged = latestSettings.mode == action.settingsAtRequest.mode && latestState.mode == action.settingsAtRequest.mode
        if (latestState.waitingCastPermission || latestState.startAttemptId != null) return false
        if (action !is PendingPermissionAction.LocalNetworkAssessment && !modeUnchanged) return false
        return when (action) {
            is PendingPermissionAction.LocalNetworkAssessment -> !latestState.isStreaming
            is PendingPermissionAction.RecoverError -> latestState.error === action.expectedError && latestState.errorToken == action.errorToken
        }
    }

    VncPermissionActionEffect(
        permissionActions, selectedMode, state.mode,
        state.isStreaming, state.waitingCastPermission, state.startAttemptId, state.error,
        isRelevant = ::isActionRelevant,
        onGranted = { action ->
            when (action) {
                is PendingPermissionAction.LocalNetworkAssessment -> recoverGrantedLocalNetworkError()
                is PendingPermissionAction.RecoverError -> when (action.permission) {
                    RuntimePermission.LocalNetwork -> recoverGrantedLocalNetworkError(allowStreaming = true, expectedRecovery = action)
                    RuntimePermission.Notifications -> currentSendEvent(VncEvent.RecoverError(action.errorToken))
                    else -> Unit
                }
            }
        }
    )

    BoxWithConstraints(modifier = modifier) {
        state.consentRetryToken?.let { retryToken ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {
                    TextButton(onClick = { sendEvent(VncEvent.RetryConsent(retryToken, retry = true)) }) {
                        Text(stringResource(CommonR.string.common_try_again))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { sendEvent(VncEvent.RetryConsent(retryToken, retry = false)) }) {
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
                error is VncError.UnknownError ||
                        error is VncError.NotificationPermissionRequired ||
                        error is VncError.LocalNetworkPermissionRequired ||
                        error.isStartupPolicyError()
            }?.let { error ->
                item(key = "ERROR") {
                    ErrorCard(
                        error = error,
                        sendEvent = { event ->
                            val displayedToken = state.errorToken
                            val latest = vncStateFlow.value
                            if (displayedToken != null && latest.error === error && latest.errorToken == displayedToken) {
                                sendEvent(if (event is VncEvent.RecoverError) event.copy(errorToken = displayedToken) else event)
                            }
                        },
                        retryStartupPolicyError = { requestStart(error, state.errorToken) },
                        requestNotificationPermission = { requestPermissionForError(RuntimePermission.Notifications, error, state.errorToken) },
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
                    serverNetInterfaces = state.serverNetInterfaces,
                    clients = state.clients,
                    reverseStatus = state.reverseStatus,
                    reverseMessage = state.reverseMessage,
                    error = state.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
            if (selectedMode == VncSettings.Values.Mode.SERVER) {
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
            if (selectedMode == VncSettings.Values.Mode.CLIENT) {
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
            item(key = "ENCODING") {
                EncodingCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowSizeClass = windowSizeClass,
                    enabled = state.captureAttempt == null,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        LaunchedEffect(state.error) {
            if (state.error != null) lazyVerticalStaggeredGridState.animateScrollToItem(0)
        }

        val doubleClickProtection = remember { DoubleClickProtection.get() }

        val clientSettingsError = selectedMode == VncSettings.Values.Mode.CLIENT && settings.reverseConnectHost.isBlank()

        Button(
            onClick = dropUnlessStarted {
                doubleClickProtection.processClick {
                    if (state.isStreaming) {
                        state.captureAttempt?.let { sendEvent(VncEvent.StopStream("User action: Button", it)) }
                    } else {
                        requestStart()
                    }
                }
            },
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .align(alignment = Alignment.BottomCenter),
            enabled = state.isBusy.not() && clientSettingsError.not(),
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
                text = stringResource(id = if (state.isStreaming) R.string.vnc_stream_stop else R.string.vnc_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private class LocalNetworkRecovery {
    var error: VncError? = null
}

private sealed interface PendingPermissionAction {
    val settingsAtRequest: VncSettings.Data

    class LocalNetworkAssessment(
        override val settingsAtRequest: VncSettings.Data
    ) : PendingPermissionAction

    class RecoverError(
        val permission: RuntimePermission,
        val expectedError: VncError,
        val errorToken: Uuid,
        override val settingsAtRequest: VncSettings.Data
    ) : PendingPermissionAction
}
