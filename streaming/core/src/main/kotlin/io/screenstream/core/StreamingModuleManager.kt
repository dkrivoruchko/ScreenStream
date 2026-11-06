package io.screenstream.core

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Process-wide owner of streaming-module selection, instance lifecycle, and command routing.
 *
 * At most one module instance is admitted to new work. Replacement and Exit close the current instance's
 * admission and wait a bounded time for its cleanup; cleanup itself continues independently, possibly
 * alongside a replacement. [State.Running] describes control progress; capture and network readiness
 * belong to the module.
 *
 * Commands are requests: stale, unknown, or ill-timed requests are ignored, and returning does not
 * confirm any effect. Observe [state] for results; a slow collector sees only the latest snapshot.
 */
public interface StreamingModuleManager {
    /**
     * Registered modules, ordered by descending [StreamingModule.priority] and fixed for the manager's
     * lifetime. Construction fails unless the list is nonempty, module IDs are unique, and every
     * [StreamingModule.foregroundSlot] is valid.
     */
    public val modules: List<StreamingModule>

    /** Current process state, observable independently of any Activity. */
    public val state: StateFlow<State>

    /**
     * Renders the installed controller's content while [instanceId] is the current admitted instance;
     * otherwise renders [fallback].
     */
    @Composable
    public fun InstanceContent(
        instanceId: StreamingModule.InstanceId,
        window: WindowSizeClass,
        modifier: Modifier = Modifier,
        fallback: @Composable () -> Unit
    )

    /**
     * Selects [moduleId], or the remembered module when null, and opens it unless it is already the
     * current instance. Selecting the current module keeps its instance, even after failure; use
     * [restartModule] to replace it. Unknown IDs and requests during a transition, including the first
     * settings load, are ignored.
     *
     * The first selection loads settings; a missing or unregistered saved ID resolves to the first entry
     * of [modules]. A changed choice is saved asynchronously; later null requests use the in-memory choice.
     *
     * Opening starts the module's Service with ordinary `Context.startService`; the admitted Service then
     * promotes itself to foreground. Android can reject either step, for example after the app leaves the
     * foreground during preparation. Opening also fails if the same Service component still hosts the
     * previous controller when the bounded shutdown wait ends. Any startup failure, including a missing
     * first status, publishes generic [State.Failed] and is not retried; call [restartModule] from
     * foreground UI to retry.
     */
    @MainThread
    public fun selectModule(moduleId: StreamingModule.Id? = null)

    /**
     * Replaces [expectedInstanceId] with a new instance of the same module. Applies only when no
     * transition is active and it is the current instance in [State.Running], [State.Unresponsive], or
     * [State.Failed]; otherwise the request is ignored. The new opening follows [selectModule]'s startup
     * rules; retry a failed restart with the new instance's identity.
     */
    @MainThread
    public fun restartModule(expectedInstanceId: StreamingModule.InstanceId)

    /**
     * Closes admission and starts process-owned shutdown, returning before cleanup completes. Unlike
     * selection, Exit is accepted during a transition and cancels a pending opening. A non-null
     * [expectedInstanceId] applies only while it is the current admitted instance, including during
     * startup; null applies regardless of the current instance.
     *
     * Repeated requests join the pending bounded wait; cleanup continues past it. [State.Exiting] remains
     * until a later selection. The app owns removal of its tasks.
     */
    @MainThread
    public fun exit(expectedInstanceId: StreamingModule.InstanceId? = null)

    /**
     * Asynchronously forwards Start to the controller when, at dispatch, [instanceId] is the current
     * admitted instance in [State.Running] or [State.Unresponsive] and no transition is active. The
     * controller decides readiness. May be called from any thread.
     */
    public fun requestStreamStart(instanceId: StreamingModule.InstanceId)

    /**
     * Synchronously forwards Stop for [attempt] under the same routing checks as [requestStreamStart].
     * Reported status can lag behind the notification that offered Stop, so the controller, not the
     * manager, validates [attempt]. [fromNotification] marks the notification action. Stop and resource
     * release complete after return.
     */
    @MainThread
    public fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean = false)

    /**
     * Asynchronously forwards in-place recovery under the same routing checks as [requestStreamStart].
     * The controller validates [errorToken] against its current error episode. May be called from any thread.
     */
    public fun requestRecovery(instanceId: StreamingModule.InstanceId, errorToken: Uuid)

    /** One complete, observable process state. */
    public sealed interface State {
        /** Idle state awaiting module selection. */
        public data object NoModule : State

        /**
         * An opening is in progress: settings loading, the previous instance's bounded shutdown wait, or
         * the new instance's first status.
         */
        public data object Switching : State

        /** Exit has closed admission; remains after the bounded wait until a later selection. */
        public data object Exiting : State

        /** [status] is the latest accepted report and its heartbeat is fresh. */
        public data class Running(
            public val instanceId: StreamingModule.InstanceId,
            public val status: StreamingModule.Status,
        ) : State

        /** The heartbeat is stale; [lastStatus] is the last accepted report. */
        public data class Unresponsive(
            public val instanceId: StreamingModule.InstanceId,
            public val lastStatus: StreamingModule.Status,
        ) : State

        /**
         * Terminal failure; admission is closed and cleanup proceeds. Remains until another module is
         * selected, the instance is restarted, or Exit. Null [messageResource] means generic error text.
         */
        public data class Failed(
            public val instanceId: StreamingModule.InstanceId,
            @get:StringRes public val messageResource: Int? = null,
        ) : State
    }
}
