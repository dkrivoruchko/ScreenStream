package info.dvkr.screenstream.mjpeg.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.common.ui.DoubleClickProtection
import info.dvkr.screenstream.common.ui.get
import info.dvkr.screenstream.common.ui.mediaprojection.ScreenCapturePermissionFlow
import info.dvkr.screenstream.common.ui.mediaprojection.rememberScreenCaptureStartRequester
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.main.cards.AdvancedSettingsCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.ClientsCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.ErrorCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.GeneralSettingsCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.ImageSettingsCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.InterfacesCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.PinCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.SecuritySettingsCard
import info.dvkr.screenstream.mjpeg.ui.main.cards.TrafficCard
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.legacy.LegacyPermissionActionEffect
import io.screenstream.streaming.legacy.rememberLegacyPermissionActions
import io.screenstream.streaming.permission.LocalRuntimePermissions
import io.screenstream.streaming.permission.PermissionState
import io.screenstream.streaming.permission.RuntimePermission
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun MjpegMainScreenUI(
    mjpegStateFlow: StateFlow<MjpegState>,
    sendEvent: (event: MjpegEvent) -> Unit,
    onProjectionGranted: (startAttemptId: String, intent: android.content.Intent) -> Unit,
    claimInitialLocalNetworkRequest: (request: () -> Boolean) -> Unit,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    mjpegSettings: MjpegSettings = koinInject(),
    notificationHelper: NotificationHelper = koinInject(),
    streamingModuleManager: StreamingModuleManager = koinInject()
) {
    val mjpegState = mjpegStateFlow.collectAsStateWithLifecycle()
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val screenCaptureStartRequester = rememberScreenCaptureStartRequester()
    val context = LocalContext.current
    val state = mjpegState.value
    val settings = mjpegSettingsState.value
    val permissions = LocalRuntimePermissions.current
    val screenInstanceId = retain { streamingModuleManager.currentInstanceId.value }
    val currentInstanceId by streamingModuleManager.currentInstanceId.collectAsStateWithLifecycle()
    val localNetworkPermissionState by permissions.state(RuntimePermission.LocalNetwork).collectAsStateWithLifecycle()
    val notificationPermissionState by permissions.state(RuntimePermission.Notifications).collectAsStateWithLifecycle()
    val audioPermissionState by permissions.state(RuntimePermission.RecordAudio).collectAsStateWithLifecycle()
    val permissionActions = rememberLegacyPermissionActions<PendingPermissionAction>(screenInstanceId)
    val updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit = { transform ->
        scope.launch { mjpegSettings.updateData(transform) }
    }

    fun requestPermission(permission: RuntimePermission, expectedError: MjpegError? = null) {
        if (screenInstanceId == null || streamingModuleManager.currentInstanceId.value != screenInstanceId) return
        val accepted = permissionActions.request(permission, PendingPermissionAction(permission, expectedError), permissions)
        if (accepted && permission == RuntimePermission.LocalNetwork) claimInitialLocalNetworkRequest { true }
    }

    fun recoverGrantedLocalNetworkError() {
        if (screenInstanceId != null && streamingModuleManager.currentInstanceId.value == screenInstanceId &&
            mjpegStateFlow.value.error is MjpegError.LocalNetworkPermissionRequired && permissions.isGranted(RuntimePermission.LocalNetwork)
        ) {
            sendEvent(MjpegEvent.Intentable.RecoverError)
        }
    }

    fun requestInitialLocalNetworkPermission() {
        if (screenInstanceId != null && streamingModuleManager.currentInstanceId.value == screenInstanceId &&
            permissionActions.pending == null && localNetworkPermissionState != PermissionState.SettingsSuggested &&
            !permissions.isGranted(RuntimePermission.LocalNetwork)
        ) {
            claimInitialLocalNetworkRequest {
                permissionActions.request(RuntimePermission.LocalNetwork, PendingPermissionAction(RuntimePermission.LocalNetwork, null), permissions)
            }
        }
    }

    SideEffect(screenInstanceId, currentInstanceId, localNetworkPermissionState, notificationPermissionState, audioPermissionState, permissionActions.pending) {
        requestInitialLocalNetworkPermission()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        recoverGrantedLocalNetworkError()
        requestInitialLocalNetworkPermission()
    }

    // Also covers a grant arriving before the backend publishes its permission error.
    SideEffect(screenInstanceId, currentInstanceId, localNetworkPermissionState, state.error) {
        recoverGrantedLocalNetworkError()
    }

    LegacyPermissionActionEffect(
        permissionActions, state.error, screenInstanceId, currentInstanceId,
        isRelevant = { action ->
            streamingModuleManager.currentInstanceId.value == screenInstanceId &&
                    (action.expectedError == null || mjpegStateFlow.value.error === action.expectedError)
        },
        onGranted = { action ->
            if (action.permission == RuntimePermission.Notifications && mjpegStateFlow.value.error === action.expectedError) {
                sendEvent(MjpegEvent.Intentable.RecoverError)
            }
        }
    )

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionFlow(
            startRequester = screenCaptureStartRequester,
            permissionScopeKey = "mjpeg",
            startAttemptId = state.startAttemptId?.takeIf { state.waitingCastPermission },
            onStartRequested = onStartRequested@{ educationShown ->
                if (state.isStreaming) return@onStartRequested
                sendEvent(
                    MjpegStreamingService.InternalEvent.StartStream(
                        permissionEducationShown = educationShown,
                        clearStartupPolicyError = state.error?.isStartupPolicyError() == true
                    )
                )
            },
            onPermissionGranted = { startAttemptId, intent -> if (state.startAttemptId == startAttemptId) onProjectionGranted(startAttemptId, intent) },
            onPermissionDenied = { startAttemptId -> if (state.startAttemptId == startAttemptId) sendEvent(MjpegEvent.CastPermissionsDenied(startAttemptId)) },
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
                        retryStartupPolicyError = { screenCaptureStartRequester.request() },
                        requestNotificationPermission = requestNotificationPermission@{
                            if (mjpegStateFlow.value.error !is MjpegError.NotificationPermissionRequired) return@requestNotificationPermission
                            if (permissions.isGranted(RuntimePermission.Notifications)) {
                                permissionActions.cancel()
                                sendEvent(MjpegEvent.Intentable.RecoverError)
                                context.startActivity(notificationHelper.getStreamNotificationSettingsIntent())
                            } else {
                                requestPermission(RuntimePermission.Notifications, mjpegStateFlow.value.error)
                            }
                        },
                        requestLocalNetworkPermission = {
                            if (mjpegStateFlow.value.error is MjpegError.LocalNetworkPermissionRequired) {
                                requestPermission(RuntimePermission.LocalNetwork, mjpegStateFlow.value.error)
                            }
                        },
                        notificationOpensSettings = notificationPermissionState == PermissionState.SettingsSuggested || permissions.isGranted(RuntimePermission.Notifications),
                        localNetworkOpensSettings = localNetworkPermissionState == PermissionState.SettingsSuggested,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            item(key = "INTERFACES") {
                InterfacesCard(serverNetInterfaces = state.serverNetInterfaces, modifier = Modifier.padding(8.dp))
            }

            item(key = "PIN") { //TODO notify user that this will disconnect all clients
                PinCard(
                    pin = state.pin,
                    isStreaming = state.isStreaming,
                    onCreateNewPin = { sendEvent(MjpegEvent.CreateNewPin) },
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "TRAFFIC") {
                TrafficCard(traffic = state.traffic, modifier = Modifier.padding(8.dp))
            }

            item(key = "CLIENTS") {
                ClientsCard(clients = state.clients, modifier = Modifier.padding(8.dp))
            }

            item(key = "SETTINGS_GENERAL") {
                GeneralSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowSizeClass = windowSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_IMAGE") {
                ImageSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowSizeClass = windowSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_SECURITY") {
                SecuritySettingsCard(
                    settings = settings,
                    isStreaming = state.isStreaming,
                    updateSettings = updateSettings,
                    windowSizeClass = windowSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_ADVANCED") {
                AdvancedSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowSizeClass = windowSizeClass,
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
                        sendEvent(MjpegEvent.Intentable.StopStream("User action: Button"))
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
                text = stringResource(id = if (state.isStreaming) R.string.mjpeg_stream_stop else R.string.mjpeg_stream_start),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

private data class PendingPermissionAction(
    val permission: RuntimePermission,
    val expectedError: MjpegError?,
)
