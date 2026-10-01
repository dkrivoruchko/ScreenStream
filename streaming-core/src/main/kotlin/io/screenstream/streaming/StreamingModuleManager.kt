package io.screenstream.streaming

import android.app.Service
import android.content.Intent
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-level selection and launch contract for streaming modules. One [state] value contains
 * the current UI facts: during selection, replacement, or Exit it hides the old module instance.
 * Running confirms live module control work, not capture, HTTP, or consumer readiness.
 *
 * Selection, restart, Start, and Stop return without confirming acceptance or success. Stale, unknown,
 * or busy requests can have no effect; [exit] has its own bounded wait. A slow StateFlow collector
 * may skip brief transition states and sees the latest value.
 */
public interface StreamingModuleManager {
    /**
     * Read-only catalog of registered streaming modules, ordered by descending [StreamingModule.priority].
     *
     * Membership and order stay fixed for this manager's lifetime. Selectors and other consumers
     * use it for metadata and module lookup; a listed module is not necessarily started or active.
     * Use [state] to determine the current module and its lifecycle state.
     */
    public val modules: List<StreamingModule>

    /**
     * Identifies the streaming module currently presented in the app.
     * A module with an error or one that has stopped responding remains identified while it is presented.
     * Null when no module is open, or while a module is switching or closing.
     * Having an identifier does not mean a stream is active.
     */
    public val currentInstanceId: StateFlow<StreamingModule.InstanceId?>

    /** Process state, independently observable from any Activity. */
    public val state: StateFlow<State>

    /** Render only the exact admitted instance's installed content; otherwise render [fallback]. */
    @Composable
    public fun InstanceContent(instanceId: StreamingModule.InstanceId, window: WindowSizeClass, modifier: Modifier, fallback: @Composable () -> Unit)

    /**
     * The module Service calls this on a startup delivery, before creating ordinary module work.
     * Validate the reserved identity and component before invoking [createController]. A Service
     * with [existingController] cannot adopt a second controller. The factory must store its result
     * in that Service before returning; the manager takes ownership before checking identity or
     * calling startModule. Rejected deliveries do not invoke the factory. Once stored, the original
     * controller stays owned by the Service even if startup fails.
     */
    @MainThread
    public fun onServiceStart(
        service: Service,
        existingController: StreamingModule.Controller?,
        intent: Intent?,
        createController: (StreamingModule.Controller.Runtime) -> StreamingModule.Controller,
    )

    /**
     * Select [moduleId], or use the current saved selection when null. The first request owns
     * settings initialization before a module launches. A known different module replaces the
     * current launch after its shutdown wait. The current launch's module ID is a no-op even when
     * it has failed; retry requires [restartModule]. Loading or an active transition/Exit rejects
     * a concurrent request without queueing it. An explicit changed selection is saved; null does
     * not reread settings on later requests or write it again.
     */
    @MainThread
    public fun selectModule(moduleId: StreamingModule.Id? = null)

    /**
     * Restart only the exact current [expectedInstanceId] while it has a failure or an
     * unresponsive heartbeat and no transition is active. A stale ID, healthy launch, or busy
     * manager ignores the request. Restart does not change the saved selection.
     */
    @MainThread
    public fun restartModule(expectedInstanceId: StreamingModule.InstanceId)

    /**
     * Close launch admission and request shutdown of the current instance. Its shutdown wait
     * deadline is no later than three seconds after the first Exit request; repeated calls share
     * the operation and deadline. Completion is processed when Main can run, so this is not a
     * wall-clock return bound. Return means waiting ended, including when cleanup is unconfirmed.
     * Cancelling this caller ends only its wait, not process-owned shutdown or cleanup.
     */
    @MainThread
    public suspend fun exit()

    /**
     * Queue external capture Start for [instanceId]. The module rechecks current admission and
     * capture readiness when the command is dispatched; return does not confirm capture Start.
     */
    public fun requestStreamStart(instanceId: StreamingModule.InstanceId)

    /**
     * Synchronously route Stop for the exact [attempt] of an admitted current instance. The
     * module validates whether that capture attempt is still live. Return is not a Stop result.
     */
    @MainThread
    public fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId) // TODO Add the string param for place where it was called from.

    public companion object {
        /** Startup-only action; module commands use their own implementation's protocol. */
        public const val ACTION_START_MODULE: String = "io.screenstream.streaming.START_MODULE"
    }

    /** One complete, observable process state. */
    public sealed interface State {
        /** Initial state before the first selection request. */
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

        /** No fresh heartbeat; [lastStatus] remains the last accepted capture summary. */
        public data class Unresponsive(
            public val instanceId: StreamingModule.InstanceId,
            public val lastStatus: StreamingModule.Status,
        ) : State

        /** A terminal [failure] of [instanceId], retained through its cleanup. */
        public data class Failed(
            public val instanceId: StreamingModule.InstanceId,
            public val failure: Failure,
        ) : State
    }

    /** Terminal launch or module failure; recoverable capture errors stay in module UI state. */
    public sealed interface Failure {
        /** Launch failed before the first accepted real status. */
        public data object LaunchFailed : Failure

        /**
         * Module could not continue after its first accepted status. [messageResource] selects an
         * optional module error string; null uses the generic module error text.
         */
        public data class ModuleFailed(@get:StringRes public val messageResource: Int? = null) : Failure
    }
}
