package io.screenstream.core.module

import android.app.Service
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Reusable descriptor of a registered streaming mode.
 *
 * [id] identifies the mode across openings. Each opening receives a distinct [InstanceId], and
 * each accepted capture Start receives a [CaptureAttemptId]. The manager starts [serviceClass];
 * that Service creates the instance's [Controller], which reports through its instance-bound handle.
 */
public interface StreamingModule {
    /** Stable identity shared by every launch of a streaming module. */
    @Immutable
    @Serializable
    public data class Id(public val value: String)

    /** Stable selection identity shared by all launches of this module. */
    public val id: Id

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

    /** Concrete [StreamingModuleService] subclass the manager starts to open this module. */
    public val serviceClass: Class<out Service>

    /**
     * Stable foreground notification slot reserved for [serviceClass]. Its ID must be positive and
     * distinct from [StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR]. Descriptors sharing
     * a Service component use the same slot ID; different Service components use different IDs.
     */
    public val foregroundSlot: StreamingModuleNotifications.ForegroundSlot

    /**
     * Logical launch identity allocated before a [Controller] exists. Each launch or restart gets a
     * new UUID; data equality uses both [moduleId] and [uuid].
     *
     * @property moduleId Stable [id] of the selected module.
     * @property uuid Unique identity of this launch within the process.
     */
    @Immutable
    public data class InstanceId(public val moduleId: Id, public val uuid: Uuid = Uuid.random())

    /**
     * Identity assigned to an accepted capture Start within one [InstanceId]. A later accepted
     * Start gets another UUID; data equality uses both [instanceId] and [uuid].
     *
     * @property instanceId Launch that owns the capture attempt.
     * @property uuid Unique identity of this attempt within the instance.
     */
    public data class CaptureAttemptId(public val instanceId: InstanceId, public val uuid: Uuid = Uuid.random())

    /**
     * Capture summary from real module control work, including while capture is idle. The source is
     * responsible for reporting at least once per second while active, subject to scheduling delays.
     *
     * @property isStreaming Whether capture is streaming; consumer presence is reported separately.
     * @property hasConsumer Whether a consumer is present; capture state is reported separately.
     * @property captureAttempt Accepted Start identity retained through its cleanup; null marks an idle capture state.
     * @property error Current operation error while the module can continue; null marks an empty error field.
     */
    public data class Status(
        public val isStreaming: Boolean,
        public val hasConsumer: Boolean,
        public val captureAttempt: CaptureAttemptId?,
        public val error: Error?,
    ) {
        /**
         * Operation error for an instance that can continue, with an optional localized user message.
         * Technical diagnostics belong in logs. The module owns the error episode and any recovery token;
         * this value carries the message only.
         */
        public data class Error(public val message: String? = null)
    }

    /**
     * Controls one module instance and owns its work, UI, resources, and cleanup.
     *
     * The Service creates an inert controller, then the manager installs it before calling [startModule].
     * A controller must retain reference equality so StateFlow distinguishes installed instances.
     * Shutdown and cleanup remain this controller's responsibility through Service destruction and
     * replacement of the instance.
     */
    public interface Controller {
        /** Immutable launch identity, equal to [ManagerHandle.instanceId]. */
        public val instanceId: InstanceId

        /**
         * Begin ordinary control work after installation and return promptly. Recheck
         * [ManagerHandle.isCurrent] before admitting resources because shutdown may win this race.
         */
        @MainThread
        public fun startModule()

        /**
         * Request capture Start within this controller's instance. A stale or unready request may be
         * ignored. Later behavior reflects any resulting work.
         */
        @AnyThread
        public fun requestStreamStart()

        /**
         * Request capture Stop for the exact [attempt] owned by this controller's instance. Stop applies
         * only to that attempt and leaves the module lifecycle to its shutdown operation. Processing and
         * capture-resource release complete separately from return. [fromNotification] retains the
         * action's origin.
         */
        @AnyThread
        public fun requestStreamStop(attempt: CaptureAttemptId, fromNotification: Boolean = false)

        /**
         * Request in-place recovery of this instance's current operation error. A module that offers
         * recovery validates [errorToken] against its current error episode before handling it. The default
         * implementation returns immediately; recovery stays within this instance and Service.
         */
        @AnyThread
        public fun requestRecovery(errorToken: Uuid): Unit = Unit

        /**
         * Idempotently close new work and start cleanup independently of the control loop. Support calls
         * before [startModule] as well as during normal operation; cleanup begins even if the loop is stalled.
         */
        @AnyThread
        public fun requestModuleShutdown()

        /**
         * Wait for this controller's orchestration and declared cleanup after [requestModuleShutdown].
         * Cleanup runs independently of its waiters: cancelling a waiter cancels only that wait. It includes
         * stopping the original Service after capture cleanup settles and awaiting its destruction.
         *
         * Return true only if these operations succeeded; false includes failure or unconfirmed cleanup.
         * Physical resource release has the guarantees of each resource owner's contract. New controller
         * work remains closed.
         */
        public suspend fun awaitCleanup(): Boolean

        /** Render this instance's screen; the manager calls it only while work is admitted. */
        @Composable
        public fun Content(window: WindowSizeClass, modifier: Modifier = Modifier)

        /**
         * Called only by the original Service after its destruction. Record the loss and close admission
         * promptly; cleanup remains bound to this controller and its original instance.
         */
        @MainThread
        public fun onServiceDestroyed()

        /**
         * Instance-bound connection shared by the original Service and its controller.
         *
         * The manager creates the handle at reservation, before Service delivery or controller construction.
         * It supplies fixed identity and foreground-slot metadata, admission checks, and status reports;
         * manager commands go directly to the controller. Keep it through cleanup: it remains bound to the
         * original instance. The controller owns resources. Each admission check describes the current
         * instant; check again before later work.
         */
        public interface ManagerHandle {
            /** Identity the created controller must expose for its whole lifetime. */
            public val instanceId: InstanceId

            /** Validated foreground slot for this admitted instance's exact Service component. */
            public val foregroundSlot: StreamingModuleNotifications.ForegroundSlot

            /** Whether this instance may still accept work; recheck before taking new resources. */
            @AnyThread
            public fun isCurrent(): Boolean

            /**
             * Report completed control work, including idle work, with a nondecreasing
             * SystemClock.uptimeMillis timestamp. Report at least once per second while active, subject to
             * scheduling delays. Heartbeats must reflect control progress so stalled work remains observable.
             * The manager may coalesce pending reports and ignores timestamps older than the last applied
             * heartbeat; reports apply only while the original instance is admitted.
             *
             * The default manager applies its own startup and heartbeat deadlines, which measure different clocks.
             * Fresh progress can restore unresponsive control within the same instance.
             */
            @AnyThread
            public fun reportRunning(status: Status, heartbeatAtUptimeMillis: Long)

            /**
             * Declare terminal failure for this instance; close admission and request shutdown. Startup failure
             * uses generic presentation. An established instance may supply a localized [messageResource].
             * Reports remain bound to their original instance.
             */
            @AnyThread
            public fun reportFailed(@StringRes messageResource: Int? = null)

        }
    }
}
