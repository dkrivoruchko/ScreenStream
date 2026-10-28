package info.dvkr.screenstream.rtsp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.RetainedEffect
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.screenstream.streaming.compose.LocalRuntimePermissions
import io.screenstream.streaming.permission.PermissionState
import io.screenstream.streaming.permission.RuntimePermission
import io.screenstream.streaming.permission.RuntimePermissions

/** Retains RTSP action data across Activity recreation, without retaining its permission host. */
internal class RtspPermissionActions<A : Any> internal constructor() {
    internal var request by mutableStateOf<Request<A>?>(null)
        private set

    internal val pending: A? get() = request?.action

    internal fun isCurrent(action: A): Boolean = request?.action === action

    internal fun request(permission: RuntimePermission, action: A, permissions: RuntimePermissions): Boolean {
        if (!permissions.tryRequest(permission)) return false
        request = Request(permission, action)
        return true
    }

    internal fun cancel() {
        request = null
    }

    internal class Request<A>(val permission: RuntimePermission, val action: A)
}

@Composable
internal fun <A : Any> rememberRtspPermissionActions(ownerKey: Any? = Unit): RtspPermissionActions<A> {
    val actions = retain(ownerKey) { RtspPermissionActions<A>() }
    RetainedEffect(actions) { onRetire { actions.cancel() } }
    return actions
}

/** Observes the current Activity's permission host; business checks stay with the RTSP controller. */
@Composable
internal fun <A : Any> RtspPermissionActionEffect(
    actions: RtspPermissionActions<A>,
    vararg relevanceKeys: Any?,
    isRelevant: (A) -> Boolean,
    onGranted: suspend (A) -> Unit,
) {
    val request = actions.request ?: return
    val permissions = LocalRuntimePermissions.current
    val currentIsRelevant by rememberUpdatedState(isRelevant)
    val currentOnGranted by rememberUpdatedState(onGranted)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val state by permissions.state(request.permission).collectAsStateWithLifecycle()
    LaunchedEffect(actions, request, permissions, state, lifecycle, lifecycleState, *relevanceKeys) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (currentIsRelevant(request.action)) {
            if (state == PermissionState.Requesting) return@LaunchedEffect
            if (state == PermissionState.Granted && permissions.isGranted(request.permission)) currentOnGranted(request.action)
        }
        if (actions.request === request) actions.cancel()
    }
}
