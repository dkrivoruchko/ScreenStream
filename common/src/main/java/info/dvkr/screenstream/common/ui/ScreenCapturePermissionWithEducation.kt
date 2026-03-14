package info.dvkr.screenstream.common.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.settings.AppSettings
import org.koin.compose.koinInject

@Stable
public class ScreenCapturePermissionWithEducationState internal constructor() {
    internal var showEducationDialog: Boolean by mutableStateOf(false)
    internal var pendingEducationCompletion: Boolean by mutableStateOf(false)
    private var requestStartHandler: (() -> Unit)? = null

    public fun requestStart() {
        requestStartHandler?.invoke()
    }

    internal fun bindStartHandler(
        isStreaming: Boolean,
        isEducationCompleted: Boolean,
        onStartRequested: (permissionEducationShown: Boolean) -> Unit
    ) {
        requestStartHandler =
            if (isStreaming) {
                null
            } else {
                {
                    pendingEducationCompletion = false
                    if (isEducationCompleted) {
                        onStartRequested(false)
                    } else {
                        showEducationDialog = true
                    }
                }
            }
    }

    internal fun onEducationConfirmed(onStartRequested: (permissionEducationShown: Boolean) -> Unit) {
        showEducationDialog = false
        pendingEducationCompletion = true
        onStartRequested(true)
    }

    internal fun onEducationCancelled() {
        showEducationDialog = false
        pendingEducationCompletion = false
    }

    internal fun onPermissionRetryCancelled() {
        pendingEducationCompletion = false
    }

    internal suspend fun onStreamingStarted(appSettings: AppSettings, isEducationCompleted: Boolean) {
        if (pendingEducationCompletion && isEducationCompleted.not()) {
            appSettings.updateData { copy(screenCaptureEducationCompleted = true) }
            pendingEducationCompletion = false
        }
    }
}

@Composable
public fun rememberScreenCapturePermissionWithEducationState(): ScreenCapturePermissionWithEducationState =
    retain { ScreenCapturePermissionWithEducationState() }

@Composable
public fun ScreenCapturePermissionWithEducation(
    state: ScreenCapturePermissionWithEducationState,
    shouldRequestPermission: Boolean,
    isStreaming: Boolean,
    onStartRequested: (permissionEducationShown: Boolean) -> Unit,
    onPermissionGranted: (Intent) -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
    appSettings: AppSettings = koinInject()
) {
    val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
    val isEducationCompleted = appSettingsState.value.screenCaptureEducationCompleted
    val currentOnStartRequested by rememberUpdatedState(onStartRequested)

    SideEffect {
        state.bindStartHandler(
            isStreaming = isStreaming,
            isEducationCompleted = isEducationCompleted,
            onStartRequested = currentOnStartRequested
        )
    }

    MediaProjectionPermission(
        shouldRequestPermission = shouldRequestPermission,
        shouldShowEducationDialog = state.showEducationDialog,
        onEducationConfirmed = { state.onEducationConfirmed(currentOnStartRequested) },
        onEducationCancelled = state::onEducationCancelled,
        onPermissionGranted = onPermissionGranted,
        onPermissionDenied = onPermissionDenied,
        onPermissionRetryRequested = {
            if (isStreaming.not()) currentOnStartRequested(state.pendingEducationCompletion)
        },
        onPermissionRetryCancelled = state::onPermissionRetryCancelled,
        modifier = modifier
    )

    LaunchedEffect(isStreaming, isEducationCompleted) {
        if (isStreaming) state.onStreamingStarted(appSettings, isEducationCompleted)
    }
}
