package io.screenstream.core.compose

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.R
import io.screenstream.core.capture.ScreenCaptureAccessImpl
import io.screenstream.core.logE
import io.screenstream.core.logW
import io.screenstream.core.settings.StreamingSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Hosts capture education and the Android consent launcher for `ScreenCaptureAccess`. Mount once at the
 * Activity root, outside module screens. While resumed, the host is eligible for fresh consent; a submitted
 * request keeps its single launch and result association across pause and recreation.
 *
 * Education is shown until accepted once. Acceptance is saved independently of consent or capture
 * success, and a failed save can show it again. When the host's Activity owns the result registry,
 * disposing the submitting launcher while that Activity is finishing ends the request as HostUnavailable.
 * Other terminal losses, such as before the launcher mounts, are not detected; with a different registry,
 * a warning is logged and this hook is disabled. Runtime permission requests use the app's separate host.
 */
@Composable
public fun ScreenCaptureConsentHost() {
    val access: ScreenCaptureAccessImpl = koinInject()
    val request by access.currentRequest.collectAsStateWithLifecycle()
    val token = remember { Any() }
    val activity = LocalActivity.current
    val registry = LocalActivityResultRegistryOwner.current?.activityResultRegistry
    val resultActivity = activity?.takeIf {
        registry != null && (it as? ActivityResultRegistryOwner)?.activityResultRegistry === registry
    }
    LaunchedEffect(access, activity, registry) {
        if (resultActivity == null) {
            access.logW(
                "ScreenCaptureConsentHost",
                "Terminal consent settlement unavailable: Activity and result registry do not match",
            )
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(access, token, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) access.onHostResumed(token)
        val observer = LifecycleEventObserver { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) access.onHostResumed(token)
            else access.onHostInactive(token)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            access.onHostInactive(token)
        }
    }

    request?.let { current ->
        key(current.key) { ConsentRequestLauncher(access, token, current.key, current.submitted, resultActivity) }
    }
}

@Composable
private fun ConsentRequestLauncher(
    access: ScreenCaptureAccessImpl,
    token: Any,
    requestKey: String,
    submitted: Boolean,
    resultActivity: Activity?,
) {
    val contract = remember { ActivityResultContracts.StartActivityForResult() }
    val launcher = rememberLauncherForActivityResult(contract) { result -> access.deliver(requestKey, result) }
    val launchedHere = rememberSaveable(requestKey) { mutableStateOf(false) }
    DisposableEffect(access, resultActivity, requestKey) {
        onDispose {
            if (launchedHere.value && resultActivity?.isFinishing == true) access.abandonSubmitted(requestKey)
        }
    }

    val settings: StreamingSettingsStore = koinInject()
    val persistenceScope = rememberCoroutineScope()
    var educationGate: Boolean? by remember(requestKey) { mutableStateOf(null) }
    var educationAccepted by remember(requestKey) { mutableStateOf(false) }
    val educationGateOpen = submitted || educationGate == true

    LaunchedEffect(settings, access, token, requestKey) {
        settings.initialize()
        coroutineContext.ensureActive()
        if (educationGate == null && access.canSubmit(requestKey, token)) {
            educationGate = settings.screenCaptureEducationCompleted
        }
    }

    if (educationGate == false && !educationGateOpen) {
        AlertDialog(
            onDismissRequest = { access.cancelBeforeSubmission(requestKey, token) },
            confirmButton = {
                TextButton(onClick = {
                    if (educationGate == false && access.canSubmit(requestKey, token)) {
                        educationAccepted = true
                        educationGate = true
                        persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                settings.markEducationCompleted()
                            } catch (cause: Exception) {
                                if (cause is CancellationException) throw cause
                                settings.logE("ScreenCaptureConsentHost", "Failed to save screen capture education", cause)
                            }
                        }
                    }
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { access.cancelBeforeSubmission(requestKey, token) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.common_screen_capture_permission_required_title)) },
            text = { Text(stringResource(R.string.common_screen_capture_permission_education_message)) },
        )
    }

    LaunchedEffect(access, token, requestKey, launcher, educationGateOpen) {
        if (!educationGateOpen) return@LaunchedEffect
        coroutineContext.ensureActive()
        val intent = access.submit(requestKey, token, educationAccepted) ?: return@LaunchedEffect
        launchedHere.value = true
        try {
            launcher.launch(intent)
        } catch (cause: Throwable) {
            // A synchronous result may already have consumed the request; failures remain keyed to that request.
            if (!access.failLaunch(requestKey, cause)) throw cause
        }
    }
}
