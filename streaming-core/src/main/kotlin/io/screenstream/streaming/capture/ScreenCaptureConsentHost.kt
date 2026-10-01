package io.screenstream.streaming.capture

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.common.R
import io.screenstream.streaming.settings.StreamingSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Hosts one-time capture education and the keyed Android consent launcher outside stream tabs. */
@Composable
public fun ScreenCaptureConsentHost() {
    val access: ScreenCaptureAccessImpl = koinInject()
    val request by access.currentRequest.collectAsStateWithLifecycle()
    val token = remember { Any() }
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
        key(current.key) { ConsentRequestLauncher(access, token, current.key, current.submitted) }
    }
}

@Composable
private fun ConsentRequestLauncher(access: ScreenCaptureAccessImpl, token: Any, requestKey: String, submitted: Boolean) {
    val contract = remember { ActivityResultContracts.StartActivityForResult() }
    val launcher = rememberLauncherForActivityResult(contract) { result -> access.deliver(requestKey, result) }

    val settings: StreamingSettings = koinInject()
    val persistenceScope = rememberCoroutineScope()
    var educationGate: Boolean? by remember(requestKey) { mutableStateOf(null) }
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
                        educationGate = true
                        persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                settings.markEducationCompleted()
                            } catch (cause: Exception) {
                                if (cause is CancellationException) throw cause
                                Log.e("ScreenCaptureConsentHost", "Failed to save screen capture education", cause)
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
        val intent = access.submit(requestKey, token) ?: return@LaunchedEffect
        try {
            launcher.launch(intent)
        } catch (cause: Throwable) {
            // A synchronous result may already have consumed the request; never fail a newer one.
            if (!access.failLaunch(requestKey, cause)) throw cause
        }
    }
}
