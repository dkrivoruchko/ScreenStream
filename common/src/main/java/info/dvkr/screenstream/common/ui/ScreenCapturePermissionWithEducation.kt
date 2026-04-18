package info.dvkr.screenstream.common.ui

import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.R
import info.dvkr.screenstream.common.settings.AppSettings
import org.koin.compose.koinInject

@Stable
public class ScreenCapturePermissionWithEducationState internal constructor() {
    internal var showEducationDialog: Boolean by mutableStateOf(false)
    internal var showPermissionRetryDialog: Boolean by mutableStateOf(false)
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
                    showPermissionRetryDialog = false
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

    internal fun onPermissionDenied(isCurrentAttempt: Boolean) {
        if (isCurrentAttempt) showPermissionRetryDialog = true
    }

    internal fun onPermissionRetryRequested(onStartRequested: (permissionEducationShown: Boolean) -> Unit) {
        showPermissionRetryDialog = false
        onStartRequested(pendingEducationCompletion)
    }

    internal fun onPermissionRetryCancelled() {
        showPermissionRetryDialog = false
        pendingEducationCompletion = false
    }

    internal suspend fun onStreamingStarted(appSettings: AppSettings, isEducationCompleted: Boolean) {
        showPermissionRetryDialog = false
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
    startAttemptId: String?,
    isStreaming: Boolean,
    onStartRequested: (permissionEducationShown: Boolean) -> Unit,
    onPermissionGranted: (String, Intent) -> Unit,
    onPermissionDenied: (String) -> Unit,
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
        startAttemptId = startAttemptId,
        shouldShowEducationDialog = state.showEducationDialog,
        onEducationConfirmed = { state.onEducationConfirmed(currentOnStartRequested) },
        onEducationCancelled = state::onEducationCancelled,
        onPermissionGranted = { requestId, intent ->
            state.showPermissionRetryDialog = false
            onPermissionGranted(requestId, intent)
        },
        onPermissionDenied = { requestId ->
            onPermissionDenied(requestId)
            state.onPermissionDenied(isCurrentAttempt = startAttemptId == requestId)
        },
        modifier = modifier
    )

    if (state.showPermissionRetryDialog) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isStreaming.not()) state.onPermissionRetryRequested(currentOnStartRequested)
                    }
                ) {
                    Text(text = stringResource(id = R.string.common_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = state::onPermissionRetryCancelled) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            modifier = modifier,
            icon = { Icon(painter = painterResource(R.drawable.cast_warning_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.common_screen_capture_permission_required_title)) },
            text = { Text(text = stringResource(id = R.string.common_screen_capture_permission_denied_message)) },
            shape = MaterialTheme.shapes.large
        )
    }

    LaunchedEffect(isStreaming, isEducationCompleted) {
        if (isStreaming) state.onStreamingStarted(appSettings, isEducationCompleted)
    }
}
