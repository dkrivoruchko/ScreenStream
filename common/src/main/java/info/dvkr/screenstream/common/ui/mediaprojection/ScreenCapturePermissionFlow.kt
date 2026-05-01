package info.dvkr.screenstream.common.ui.mediaprojection

import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import info.dvkr.screenstream.common.R
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@Stable
public class ScreenCaptureStartRequester internal constructor() {
    internal var handler: (() -> Unit)? = null

    public fun request() {
        handler?.invoke()
    }
}

@Composable
public fun rememberScreenCaptureStartRequester(): ScreenCaptureStartRequester =
    retain { ScreenCaptureStartRequester() }

@Composable
public fun ScreenCapturePermissionFlow(
    startRequester: ScreenCaptureStartRequester,
    permissionScopeKey: String,
    startAttemptId: String?,
    onStartRequested: (educationShown: Boolean) -> Unit,
    onPermissionGranted: (String, Intent) -> Unit,
    onPermissionDenied: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel = koinViewModel<ScreenCapturePermissionViewModel>(key = "ScreenCapturePermission:$permissionScopeKey")
    val currentStartAttemptId by rememberUpdatedState(startAttemptId)
    val currentOnStartRequested by rememberUpdatedState(onStartRequested)
    val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
    val currentOnPermissionDenied by rememberUpdatedState(onPermissionDenied)

    val screenCapturePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
        onResult = { activityResult ->
            viewModel.onActivityResult(
                resultCode = activityResult.resultCode,
                data = activityResult.data,
                currentStartAttemptId = currentStartAttemptId,
                lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                onPermissionGranted = currentOnPermissionGranted,
                onPermissionDenied = currentOnPermissionDenied
            )
        }
    )

    SideEffect {
        startRequester.handler = {
            viewModel.requestStart(onStartRequested = currentOnStartRequested)
        }
    }

    LaunchedEffect(startAttemptId) {
        viewModel.onStartAttemptChanged(startAttemptId)
    }

    val context = LocalContext.current
    viewModel.uiState.launchAttemptId?.let { launchAttemptId ->
        LaunchedEffect(launchAttemptId) {
            if (viewModel.onLaunchStarted(launchAttemptId).not()) return@LaunchedEffect
            runCatching {
                val mediaProjectionManager = context.getSystemService(MediaProjectionManager::class.java)
                screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }.onFailure {
                viewModel.onLaunchFailed(launchAttemptId, it, currentOnPermissionDenied)
            }
        }
    }

    viewModel.uiState.receivedGrantAttemptId?.let { receivedGrantAttemptId ->
        LaunchedEffect(receivedGrantAttemptId) {
            currentStartAttemptId?.let { currentStartAttemptIdValue ->
                if (viewModel.deliverGrantIfResumed(
                        currentStartAttemptId = currentStartAttemptIdValue,
                        lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        onPermissionGranted = currentOnPermissionGranted,
                        onPermissionDenied = currentOnPermissionDenied
                    )
                ) {
                    return@LaunchedEffect
                }
            }
            delay(ScreenCapturePermissionViewModel.GRANT_DELIVERY_TIMEOUT)
            viewModel.onGrantTimeout(receivedGrantAttemptId, currentOnPermissionDenied)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.deliverGrantIfResumed(
            currentStartAttemptId = currentStartAttemptId,
            lifecycleResumed = true,
            onPermissionGranted = currentOnPermissionGranted,
            onPermissionDenied = currentOnPermissionDenied
        )
    }

    if (viewModel.uiState.showEducationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onEducationResult(confirmed = false, onStartRequested = currentOnStartRequested) },
            confirmButton = {
                TextButton(onClick = { viewModel.onEducationResult(confirmed = true, onStartRequested = currentOnStartRequested) }) {
                    Text(text = stringResource(id = R.string.common_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEducationResult(confirmed = false, onStartRequested = currentOnStartRequested) }) {
                    Text(text = stringResource(id = android.R.string.cancel))
                }
            },
            modifier = modifier,
            icon = { Icon(painter = painterResource(R.drawable.cast_warning_24px), contentDescription = null) },
            title = { Text(text = stringResource(id = R.string.common_screen_capture_permission_required_title)) },
            text = { Text(text = stringResource(id = R.string.common_screen_capture_permission_education_message)) },
            shape = MaterialTheme.shapes.large
        )
    }

    if (viewModel.uiState.showRetryDialog) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                TextButton(onClick = { viewModel.onRetryResult(retry = true, onStartRequested = currentOnStartRequested) }) {
                    Text(text = stringResource(id = R.string.common_try_again))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onRetryResult(retry = false, onStartRequested = currentOnStartRequested) }) {
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
}
