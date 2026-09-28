package io.screenstream.streaming.module

import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import info.dvkr.screenstream.common.module.StreamingModule
import kotlinx.coroutines.Job
import kotlin.uuid.Uuid

/**
 * Process-level entry for one streaming module: selection metadata, UI, and commands for its launches.
 * [id] identifies the module; each launch has an [InstanceId], and each accepted capture Start has
 * a [CaptureAttemptId]. The entry itself has no launch identity. A [Controller] belongs to one
 * launch and reports through callbacks bound to that identity.
 *
 * Request methods can return before Android delivery, module work, or cleanup finishes. Readiness
 * and outcomes come from [InstanceCallbacks], not from the presence of this entry or its UI.
 */
public interface StreamingModuleApi {
    /** Stable selection identity shared by all launches of this module. */
    public val id: StreamingModule.Id

    /** Selection order; modules with higher values appear first. */
    public val priority: Int

    /** Localized name used in module selection. */
    @get:StringRes
    public val nameResource: Int

    /** Localized short description used in module selection. */
    @get:StringRes
    public val descriptionResource: Int

    /** Localized details for the module's UI. */
    @get:StringRes
    public val detailsResource: Int

    /**
     * Render content for [instanceId] within [windowWidthSizeClass], applying [modifier] to its root.
     * Rendering content does not indicate that the launch or capture is ready.
     */
    @Composable
    public fun StreamUIContent(instanceId: InstanceId, windowWidthSizeClass: StreamingModule.WindowWidthSizeClass, modifier: Modifier = Modifier)

    /**
     * Request a new launch for the identity in [callbacks] and retain those callbacks through its
     * finalization. Call on Main. Synchronous rejection throws; a normal return confirms only
     * dispatch of the request. The first accepted [InstanceCallbacks.reportRunning] confirms that
     * module control is running, including when capture is idle.
     */
    @MainThread
    public fun requestLaunch(callbacks: InstanceCallbacks)

    /**
     * Request capture Start for [instanceId] from an external action. The module rechecks the exact
     * launch and capture readiness when handling it; a stale or unready request may be ignored.
     * Return does not confirm an accepted [CaptureAttemptId] or active capture.
     */
    public fun requestStreamStart(instanceId: InstanceId)

    /**
     * Request capture Stop for the exact [attempt] from an external action. It does not shut down
     * the whole launch or stop a newer attempt. Return does not confirm release of capture resources.
     */
    public fun requestStreamStop(attempt: CaptureAttemptId)

    /**
     * Request shutdown of the whole [instanceId] launch, closing its ordinary work and starting
     * owned cleanup. [waitUntilElapsedMillis] is the caller's absolute elapsedRealtime limit for
     * waiting, not a deadline that cancels cleanup. Repeated requests must not extend an earlier
     * limit. A replacement launch may proceed after the wait expires while cleanup continues.
     * Return alone does not confirm [InstanceCallbacks.reportFinished].
     */
    public fun requestShutdown(instanceId: InstanceId, waitUntilElapsedMillis: Long)

    /**
     * Coordinator reporting authority permanently bound to one [InstanceId]. The module retains it
     * through finalization, even if selection moves to another module. Reports always belong to
     * this original launch; closed or stale reports cannot update a newer one. Process death may
     * prevent a final report.
     */
    public interface InstanceCallbacks {
        /** Launch to which this authority and every report belong. */
        public val instanceId: InstanceId

        /**
         * Check whether this launch is admitted now. This snapshot does not reserve future admission;
         * recheck the original action and readiness before admitting resources. False does not cancel
         * already owned cleanup or prevent its final report.
         */
        public fun isCurrent(): Boolean

        /**
         * Report real module control work through [status], including while capture is idle. The
         * first accepted report confirms Running for this launch, not capture, network, or UI readiness.
         * Later accepted reports update its status; a closed launch ignores new Running reports.
         * [heartbeatAtUptimeMillis] is the source time of that work, including when capture is idle.
         * It uses `SystemClock.uptimeMillis()` and must be nondecreasing for this launch; an
         * unchanged stale timestamp does not make the source fresh.
         */
        public fun reportRunning(status: Status, heartbeatAtUptimeMillis: Long)

        /**
         * Report terminal inability to continue this launch, rather than a recoverable capture error.
         * Before the first accepted Running report this is a launch failure; afterward it is a module
         * failure. The first terminal error remains through cleanup. [messageResource] is a module
         * string resource resolved by the app; null selects its generic module error text.
         */
        public fun reportFailed(@StringRes messageResource: Int?)

        /**
         * Report finalization after required cleanup reaches terminal outcomes and any attached
         * Service detaches. Foreground release evidence contributes to [cleanupCompleted].
         * Android stop-call outcomes are excluded; failures are logged whether observed before or
         * after this report.
         * [cleanupCompleted] is true when applicable release obligations succeeded; false reports
         * at least one failed or unconfirmed obligation. This report does not itself retain cleanup
         * resources or transfer their ownership. A true result does not erase an earlier error,
         * rule out an unexpected termination error, or prove every native resource was physically freed.
         */
        public fun reportFinished(cleanupCompleted: Boolean)
    }

    /**
     * Logical launch identity allocated before a [Controller] exists. Each launch or restart gets a
     * new UUID; data equality uses both [moduleId] and [uuid].
     *
     * @property moduleId Stable [id] of the selected module.
     * @property uuid Unique identity of this launch within the process.
     */
    @Immutable
    public data class InstanceId(public val moduleId: StreamingModule.Id, public val uuid: Uuid)

    /**
     * Identity assigned to an accepted capture Start within one [InstanceId]. A later accepted
     * Start gets another UUID; data equality uses both [instanceId] and [uuid].
     *
     * @property instanceId Launch that owns the capture attempt.
     * @property uuid Unique identity of this attempt within the instance.
     */
    public data class CaptureAttemptId(public val instanceId: InstanceId, public val uuid: Uuid)

    /**
     * Capture summary from real module control work, including while capture is idle. The source is
     * responsible for reporting at least once per second while active, subject to scheduling delays.
     *
     * @property isStreaming Whether capture is streaming, independently of consumer presence.
     * @property hasConsumer Whether a consumer is present, independently of capture state.
     * @property captureAttempt Accepted Start identity, retained through its cleanup; null when
     * no capture attempt is active.
     */
    public data class Status(
        public val isStreaming: Boolean,
        public val hasConsumer: Boolean,
        public val captureAttempt: CaptureAttemptId?,
    )

    /**
     * Control and cleanup for one [InstanceId], separate from the process-level module entry.
     * Created without taking resources, then [start] is called only after installation. Distinct
     * launches must retain reference equality as installed controllers so StateFlow does not merge
     * them as equal values.
     */
    public interface Controller {
        /** Immutable launch identity, equal to [Callbacks.instanceId]. */
        public val instanceId: InstanceId

        /**
         * Begin ordinary control work after installation without blocking the caller. Recheck
         * [Callbacks.isCurrent] before admitting resources because shutdown may win this race.
         */
        public fun start()

        /**
         * Idempotently close new work and start cleanup independently of the control loop. This
         * must also work before [start] and must not wait for the loop to exit.
         */
        public fun requestShutdown()

        /**
         * After [requestShutdown], wait for this controller's own cleanup obligations to reach
         * terminal outcomes and return true only when they succeeded. This excludes final
         * Service-owned release and detach.
         * Cancelling a waiter must not cancel owned cleanup; all required cleanup work must be
         * registered before this returns, with no new required work afterward.
         */
        public suspend fun awaitCleanup(): Boolean

        /** Reporting, admission checks, and surviving cleanup launch for this exact controller. */
        public interface Callbacks {
            /** Immutable identity of this controller's launch. */
            public val instanceId: InstanceId

            /** Snapshot of current admission; true does not reserve the next operation. */
            public fun isCurrent(): Boolean

            /** Report actual control work as [status] with source `SystemClock.uptimeMillis()` time. */
            public fun reportRunning(status: Status, heartbeatAtUptimeMillis: Long)

            /** Report terminal inability to continue; the instance owner then initiates shutdown. */
            public fun reportFailed(@StringRes messageResource: Int? = null)

            /**
             * Launch cleanup under the instance owner's surviving supervisor, including while
             * Closing or [isCurrent] is false. Its exact [Job] is recorded before execution and
             * can be joined without owning the supervisor. Job completion alone is not a cleanup
             * success result; failures remain part of the instance's final cleanup outcome.
             */
            public fun launchCleanup(block: suspend () -> Unit): Job
        }
    }
}
