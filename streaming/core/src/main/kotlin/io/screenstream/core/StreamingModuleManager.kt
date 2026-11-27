package io.screenstream.core

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
 * Commands are requests. Stale, unknown, or ill-timed requests are ignored. Observe [state] for
 * lifecycle outcomes. A slow collector sees only the latest snapshot.
 */
public interface StreamingModuleManager {
    /**
     * Registered modules, ordered by descending [StreamingModule.priority] and fixed for the manager's
     * lifetime. Construction fails unless the list is nonempty, module IDs are nonblank and unique, and every
     * [StreamingModule.foregroundSlot] is valid.
     */
    public val modules: List<StreamingModule>

    /**
     * Current process state, observable independently of any Activity. Collectors can skip intermediate
     * states: an Exit without a remaining wait publishes [State.Closed] directly, and a later selection can
     * replace [State.Closed] with [State.Opening] before a collector runs.
     */
    public val state: StateFlow<State>

    /**
     * Composes [layout] with the installed controller's UI only while [instanceId] is the current
     * admitted instance. Keep the supplied UI within that instance's layout composition.
     * Composes [fallback] when that instance is unavailable.
     */
    @Composable
    public fun InstanceContent(
        instanceId: StreamingModule.InstanceId,
        fallback: @Composable () -> Unit,
        layout: @Composable (StreamingModule.Ui) -> Unit,
    )

    /**
     * Selects [moduleId], or the remembered module when null, and opens it unless it is already the
     * current instance. Selecting the current module keeps its instance, even after failure; use
     * [restartModule] to replace it. Unknown IDs and requests during an active transition, including
     * [State.Closing], are ignored. In [State.Closed], only a null request opens a new instance of the
     * remembered module; explicit IDs are ignored.
     *
     * The first selection waits for settings before claiming the opening. Requests waiting for settings
     * check admission again on resumption and return `false` if an active transition or an Exit accepted
     * during the wait supersedes them, even when a later selection has reopened a module. Cancelling a
     * waiting caller abandons its request. Settings loading and any claimed opening continue independently
     * of the caller.
     *
     * A null request uses the first entry of [modules] when the saved ID is missing or unregistered.
     * Later null requests use the in-memory choice. The manager saves the choice asynchronously on the
     * first opening when no ID is stored and when an explicit selection changes the automatic choice.
     *
     * Returns `true` only when a null request claims the first opening and no ID is stored. Other
     * selections return `false`, including accepted explicit selections and remembered choices.
     * Observe [state] for the startup outcome.
     *
     * Opening starts the module's Service with ordinary `Context.startService`; the admitted Service then
     * promotes itself to foreground. Android can reject either step, for example after the app leaves the
     * foreground during preparation. Opening also fails if the same Service component still hosts the
     * previous controller when the new start is delivered. Any startup failure, including a missing
     * first status, publishes generic [State.Failed] and is not retried; call [restartModule] from
     * foreground UI to retry.
     */
    @MainThread
    public suspend fun selectModule(moduleId: StreamingModule.Id? = null): Boolean

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
     * selection, Exit can supersede [State.Opening] and cancels its pending opening. It also supersedes
     * selections waiting for settings. Null applies regardless of the current instance, including before
     * any module has opened. A non-null [expectedInstanceId] applies only while it is the current admitted
     * instance, including during startup; stale, replaced, closed, and failed instances are ignored.
     *
     * Returns `true` only for the request that accepts Exit. Ignored exact requests and requests during
     * [State.Closing] or [State.Closed] return `false` without accepting another Exit.
     *
     * Without a current instance, after its cleanup is reported, or past its existing shutdown deadline,
     * the manager publishes [State.Closed] before returning. Otherwise it publishes [State.Closing] until
     * cleanup is reported or that deadline passes, then [State.Closed]. Later requests don't extend the
     * deadline, and cleanup can continue after the wait. The host owns any UI or task changes that follow
     * an accepted Exit.
     */
    @MainThread
    public fun exit(expectedInstanceId: StreamingModule.InstanceId? = null): Boolean

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
    @Immutable
    public sealed interface State {
        /** The instance ID reported by this state, including [Failed]. The manager checks admission separately. */
        public val instanceId: StreamingModule.InstanceId?
            get() = null

        /**
         * No opening is claimed; the initial state, distinct from [Closed]. A first selection may already be
         * waiting for settings.
         */
        @Immutable
        public data object NoModule : State

        /**
         * A selection or restart has claimed an opening. It may be waiting for the previous instance's
         * bounded shutdown or for the new instance's first status, and ends in [Running], [Unresponsive],
         * or [Failed] unless Exit supersedes it.
         */
        @Immutable
        public data object Opening : State

        /**
         * Exit has closed admission and waits, until the instance's shutdown deadline at most, for its
         * cleanup. Selection, restart, and commands are ignored.
         */
        @Immutable
        public data object Closing : State

        /**
         * Exit has finished its bounded wait and no instance is current; earlier cleanup may continue.
         * Remains until a null [selectModule] request opens a new instance.
         */
        @Immutable
        public data object Closed : State

        /** [status] is the latest accepted report and its heartbeat is fresh. */
        @Immutable
        public data class Running(
            override val instanceId: StreamingModule.InstanceId,
            public val status: StreamingModule.Status,
        ) : State

        /** The heartbeat is stale; [lastStatus] is the last accepted report. */
        @Immutable
        public data class Unresponsive(
            override val instanceId: StreamingModule.InstanceId,
            public val lastStatus: StreamingModule.Status,
        ) : State

        /**
         * Terminal failure; admission is closed and cleanup proceeds. Remains until another module is
         * selected, the instance is restarted, or Exit. Null [messageResource] means generic error text.
         */
        @Immutable
        public data class Failed(
            override val instanceId: StreamingModule.InstanceId,
            @get:StringRes public val messageResource: Int? = null,
        ) : State
    }
}
