package io.screenstream.streaming.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.compose.koinInject

/** Hosts the current result-keyed consent launcher outside the switching stream tabs. */
@Composable
public fun ScreenCaptureConsentHost() {
    val bridge: ScreenCaptureConsentBridge = koinInject()
    val token = remember { Any() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(bridge, token, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bridge.onHostResumed(token)
        val observer = LifecycleEventObserver { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bridge.onHostResumed(token)
            else bridge.onHostPaused(token)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            bridge.unmountHost(token)
        }
    }

    bridge.currentRequest?.let { request ->
        key(request.key) { ConsentRequestLauncher(bridge, token, request.key) }
    }
}

@Composable
private fun ConsentRequestLauncher(bridge: ScreenCaptureConsentBridge, token: Any, requestKey: String) {
    val contract = remember { ActivityResultContracts.StartActivityForResult() }
    val launcher = rememberLauncherForActivityResult(contract) { result -> bridge.deliver(requestKey, result) }

    LaunchedEffect(bridge, token, requestKey, launcher) {
        val intent = bridge.submit(requestKey, token) ?: return@LaunchedEffect
        try {
            launcher.launch(intent)
        } catch (cause: Throwable) {
            // A synchronous result removes its request before invoking the consumer. Propagate a
            // consumer exception instead of reporting it again as a launch failure.
            if (!bridge.failLaunch(requestKey, cause)) throw cause
        }
    }
}
