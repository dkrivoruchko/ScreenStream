package io.screenstream.streaming

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.Uuid

/**
 * Process-level selection and launch contract for streaming modules. One [state] value contains
 * the current UI facts: during selection, replacement, or Exit it hides the old module instance.
 * Running confirms live module control work, not capture, HTTP, or consumer readiness.
 *
 * Selection, restart, Start, and Stop return without confirming acceptance or success. Stale, unknown,
 * or busy requests can have no effect; [exit] starts process-owned shutdown without waiting.
 * A slow StateFlow collector may skip brief transition states and sees the latest value.
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
     * Select [moduleId], or use the current saved selection when null. The first request owns
     * settings initialization before a module launches. A known different module replaces the
     * current launch after its shutdown wait. The current launch's module ID is a no-op even when
     * it has failed; retry requires [restartModule]. Loading or an active transition/Exit rejects
     * a concurrent request without queueing it. An explicit changed selection is saved; null does
     * not reread settings on later requests or write it again.
     */
    @MainThread
    public fun selectModule(moduleId: StreamingModule.Id? = null)

    /** Requests a restart of [expectedInstanceId]; stale or busy requests are ignored. */
    @MainThread
    public fun restartModule(expectedInstanceId: StreamingModule.InstanceId)

    /**
     * Begin app Exit and close launch admission without waiting for cleanup. When
     * [expectedInstanceId] is supplied, ignore the request unless it still owns the admitted
     * current launch, including startup. Repeated requests share the existing operation and its
     * three-second shutdown wait deadline; cleanup continues independently after that wait ends.
     * Return confirms neither completed cleanup nor removal of the app's tasks.
     */
    @MainThread
    public fun exit(expectedInstanceId: StreamingModule.InstanceId? = null)

    /**
     * Queue external capture Start for [instanceId]. The module rechecks current admission and
     * capture readiness when the command is dispatched; return does not confirm capture Start.
     */
    public fun requestStreamStart(instanceId: StreamingModule.InstanceId)

    /**
     * Synchronously route Stop for the exact [attempt] of an admitted current instance. The
     * module validates whether that capture attempt is still live. Return is not a Stop result.
     * [fromNotification] identifies the notification's Stop action; other callers retain false.
     */
    @MainThread
    public fun requestStreamStop(attempt: StreamingModule.CaptureAttemptId, fromNotification: Boolean = false)

    /**
     * Queue in-place operation recovery for this exact instance. Admission is checked again when
     * dispatched; the module validates [errorToken] against its current error episode. Return does
     * not confirm acceptance or success and never requests a module restart.
     */
    public fun requestRecovery(instanceId: StreamingModule.InstanceId, errorToken: Uuid)

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

        /** Terminal failure retained through cleanup; null [messageResource] uses generic error text. */
        public data class Failed(
            public val instanceId: StreamingModule.InstanceId,
            @get:StringRes public val messageResource: Int? = null,
        ) : State
    }
}
