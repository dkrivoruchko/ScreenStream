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
import info.dvkr.screenstream.mjpeg.MjpegModuleService
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun MjpegMainScreenUI(
    mjpegStateFlow: StateFlow<MjpegState>,
    sendEvent: (event: MjpegEvent) -> Unit,
    windowWidthSizeClass: StreamingModule.WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    mjpegSettings: MjpegSettings = koinInject(),
    notificationHelper: NotificationHelper = koinInject()
) {
    val mjpegState = mjpegStateFlow.collectAsStateWithLifecycle()
    val mjpegSettingsState = mjpegSettings.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val screenCapturePermissionWithEducationState = rememberScreenCapturePermissionWithEducationState()
    val context = LocalContext.current
    val state = mjpegState.value
    val settings = mjpegSettingsState.value
    val updateSettings: (MjpegSettings.Data.() -> MjpegSettings.Data) -> Unit = { transform ->
        scope.launch { mjpegSettings.updateData(transform) }
    }

    BoxWithConstraints(modifier = modifier) {
        ScreenCapturePermissionWithEducation(
            state = screenCapturePermissionWithEducationState,
            shouldRequestPermission = state.waitingCastPermission,
            isStreaming = state.isStreaming,
            onStartRequested = { educationShown -> sendEvent(MjpegStreamingService.InternalEvent.StartStream(permissionEducationShown = educationShown)) },
            onPermissionGranted = { intent -> if (state.waitingCastPermission) MjpegModuleService.startProjection(context, intent) },
            onPermissionDenied = { if (state.waitingCastPermission) sendEvent(MjpegEvent.CastPermissionsDenied) },
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

            item(key = "SETTINGS_GENERAL") {
                GeneralSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowWidthSizeClass = windowWidthSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_IMAGE") {
                ImageSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowWidthSizeClass = windowWidthSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_SECURITY") {
                SecuritySettingsCard(
                    settings = settings,
                    isStreaming = state.isStreaming,
                    updateSettings = updateSettings,
                    windowWidthSizeClass = windowWidthSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "SETTINGS_ADVANCED") {
                AdvancedSettingsCard(
                    settings = settings,
                    updateSettings = updateSettings,
                    windowWidthSizeClass = windowWidthSizeClass,
                    modifier = Modifier.padding(8.dp)
                )
            }

            item(key = "TRAFFIC") {
                TrafficCard(traffic = state.traffic, modifier = Modifier.padding(8.dp))
            }

            item(key = "CLIENTS") {
                ClientsCard(clients = state.clients, modifier = Modifier.padding(8.dp))
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
                        screenCapturePermissionWithEducationState.requestStart()
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
