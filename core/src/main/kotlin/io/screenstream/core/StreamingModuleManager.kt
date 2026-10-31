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
 * Selects and manages streaming modules for the application process.
 *
 * At most one instance is admitted to new work. Replacement or Exit closes its admission; its
 * cleanup can continue while a replacement opens. During transitions [state] presents the transition
 * in place of the old instance. Running describes control progress; capture and network readiness
 * have separate lifetimes.
 *
 * Commands submit requests for evaluation; later behavior and state reflect any resulting effects.
 * A slow [StateFlow] collector can skip intermediate states and observe only the latest snapshot.
 */
public interface StreamingModuleManager {
    /**
     * Read-only catalog of registered streaming modules, ordered by descending [StreamingModule.priority].
     *
     * Membership and order stay fixed for this manager's lifetime. Selectors and other consumers use it
     * for metadata and module lookup; use [state] to determine current lifecycle state. Registration
     * requires a nonempty catalog with unique module IDs and the foreground-slot constraints declared
     * by [StreamingModule.foregroundSlot].
     */
    public val modules: List<StreamingModule>

    /** Process state, independently observable from any Activity. */
    public val state: StateFlow<State>

    /** Render only the exact admitted instance's installed content; otherwise render [fallback]. */
    @Composable
    public fun InstanceContent(
        instanceId: StreamingModule.InstanceId,
        window: WindowSizeClass,
        modifier: Modifier = Modifier,
        fallback: @Composable () -> Unit
    )

    /**
     * Select [moduleId], or the remembered selection when null. On the first request, settings are
     * initialized before opening; an absent or unavailable saved ID falls back to the first catalog
     * entry. A changed explicit choice is saved asynchronously; return precedes confirmation of persistence.
     * Later null requests use the in-memory selection.
     *
     * Selecting the current module retains its instance even after failure; use [restartModule] to retry.
     * Unknown IDs and requests during initialization or a transition are ignored; retry after the transition.
     */
    @MainThread
    public fun selectModule(moduleId: StreamingModule.Id? = null)

    /**
     * Replace the exact current live or failed instance with a new launch identity. Startup, an active
     * transition, and stale identities cause the request to be ignored. State reflects any resulting
     * transition.
     */
    @MainThread
    public fun restartModule(expectedInstanceId: StreamingModule.InstanceId)

    /**
     * Close admission and begin process-owned shutdown, returning while cleanup proceeds. If supplied,
     * [expectedInstanceId] must identify the admitted current instance, including during startup. A null
     * identity applies Exit regardless of the current instance.
     *
     * Repeated requests share the pending bounded shutdown wait; cleanup continues independently of it.
     * [State.Exiting] remains visible afterward until a later selection opens a module. Cleanup and app
     * task removal have their own completion lifetimes.
     */
    @MainThread
    public fun exit(expectedInstanceId: StreamingModule.InstanceId? = null)

    /**
     * Queue Start for the exact admitted [instanceId]. Routing requires an established status and an
     * idle transition slot; [State.Unresponsive] remains eligible. The controller rechecks admission and
     * readiness on dispatch. Subsequent behavior reflects any resulting capture.
     * May be called from any thread.
     */
    public fun requestStreamStart(instanceId: StreamingModule.InstanceId)

    /**
     * Route Stop synchronously for the exact [attempt] of the admitted current instance, provided it
     * has an established status and the transition slot is idle. The reported summary can lag, so the
     * controller validates the attempt. [fromNotification] identifies the notification action; other
     * callers retain false. Stop and resource release complete separately from this return.
     */
    @MainThread
    public fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean = false)

    /**
     * Queue in-place recovery for the exact admitted [instanceId], provided it has an established
     * status and the transition slot is idle. The controller rechecks admission on dispatch and validates
     * [errorToken] against its current error episode. Subsequent behavior reflects any recovery.
     * Recovery stays within the instance; the default controller returns immediately.
     * May be called from any thread.
     */
    public fun requestRecovery(instanceId: StreamingModule.InstanceId, errorToken: Uuid)

    /** One complete, observable process state. */
    public sealed interface State {
        /** Idle state awaiting module selection. */
        public data object NoModule : State

        /** Settings initialization, module replacement, or first real status is pending. */
        public data object Switching : State

        /** Exit is pending or completed; a later opening may request selection again. */
        public data object Exiting : State

        /** Module control reports [status]; capture and network readiness are independent. */
        public data class Running(
            public val instanceId: StreamingModule.InstanceId,
            public val status: StreamingModule.Status,
        ) : State

        /** Heartbeat is stale; [lastStatus] remains the last accepted capture summary. */
        public data class Unresponsive(
            public val instanceId: StreamingModule.InstanceId,
            public val lastStatus: StreamingModule.Status,
        ) : State

        /** Terminal failure retained through cleanup; null [messageResource] uses generic error text. */
        public data class Failed(
            public val instanceId: StreamingModule.InstanceId,
            @get:StringRes public val messageResource: Int? = null,
        ) : State
    }
}
