package io.screenstream.core.module

import android.app.Service
import androidx.annotation.AnyThread
import androidx.annotation.ArrayRes
import androidx.annotation.DrawableRes
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

    /** This module's identity; unique among registered modules. */
    public val id: Id

    /** Selection order; modules with higher values appear first. */
    public val priority: Int

    /** Localized resources owned by this module for method selection and details. */
    public val description: Description

    /**
     * Immutable resource IDs for a streaming method's selection and details UI.
     *
     * @property iconRes Monochrome method icon used in the selected-method card and chooser.
     * @property titleRes Method title used in the selected-method card, chooser, and details header.
     * @property summaryRes Short explanation shown in the chooser.
     * @property introRes Introduction shown in the details body.
     * @property conditionsRes Concise requirements and limitations shown in the chooser.
     * @property benefitsRes List of benefits shown in the details body.
     * @property thingsToKnowRes List of requirements and limitations shown in the details body.
     */
    public data class Description(
        @get:DrawableRes @param:DrawableRes public val iconRes: Int,
        @get:StringRes @param:StringRes public val titleRes: Int,
        @get:StringRes @param:StringRes public val summaryRes: Int,
        @get:StringRes @param:StringRes public val introRes: Int,
        @get:StringRes @param:StringRes public val conditionsRes: Int,
        @get:ArrayRes @param:ArrayRes public val benefitsRes: Int,
        @get:ArrayRes @param:ArrayRes public val thingsToKnowRes: Int,
    )

    /** Concrete [StreamingModuleService] subclass the manager starts to open this module. */
    public val serviceClass: Class<out Service>

    /**
     * Stable foreground notification slot reserved for [serviceClass]. Its ID must be positive and
     * distinct from [StreamingModuleNotifications.NOTIFICATION_ID_MODULE_ERROR]. Descriptors sharing
     * a Service component use the same slot ID; different Service components use different IDs.
     */
    public val foregroundSlot: StreamingModuleNotifications.ForegroundSlot

    /**
     * Identity of one opening, allocated before its [Controller] exists. Each launch or restart gets a
     * new UUID; equality uses both [moduleId] and [uuid].
     *
     * @property moduleId Stable [id] of the selected module.
     * @property uuid Unique identity of this launch within the process.
     */
    @Immutable
    public data class InstanceId(public val moduleId: Id, public val uuid: Uuid = Uuid.random())

    /**
     * Identity of one accepted capture Start within an [InstanceId]. Each accepted Start gets a new UUID;
     * equality uses both [instanceId] and [uuid].
     *
     * @property instanceId Launch that owns the capture attempt.
     * @property uuid Unique identity of this attempt within the instance.
     */
    public data class CaptureAttemptId(public val instanceId: InstanceId, public val uuid: Uuid = Uuid.random())

    /**
     * Capture summary reported with each heartbeat, including while capture is idle.
     *
     * @property isStreaming Whether capture is streaming, independently of consumers.
     * @property hasConsumer Whether a consumer is present, independently of capture.
     * @property captureAttempt Current accepted attempt, kept until its cleanup settles; null when none.
     * @property error Current operation error that leaves the instance usable; null when none. A non-null
     * value shows the manager's generic warning notification.
     */
    public data class Status(
        public val isStreaming: Boolean,
        public val hasConsumer: Boolean,
        public val captureAttempt: CaptureAttemptId?,
        public val error: Error?,
    ) {
        /**
         * Operation error with an optional localized user message; technical diagnostics belong in logs.
         * The module owns the error episode and any recovery token.
         */
        public data class Error(public val message: String? = null)
    }

    /**
     * Controls one module instance and owns its work, UI, resources, and cleanup.
     *
     * The Service creates an inert controller, and the manager installs it before calling [startModule].
     * Keep reference equality (do not override `equals`): the manager tracks installed controllers in a
     * StateFlow. Cleanup remains this controller's responsibility through Service destruction and
     * replacement of the instance.
     */
    public interface Controller {
        /** Immutable launch identity, equal to [ManagerHandle.instanceId]. */
        public val instanceId: InstanceId

        /**
         * Begins ordinary control work after installation and returns promptly. Recheck
         * [ManagerHandle.isCurrent] before acquiring resources because shutdown may win this race.
         */
        @MainThread
        public fun startModule()

        /** Requests capture Start for this instance; the controller decides readiness and may ignore it. */
        @AnyThread
        public fun requestStreamStart()

        /**
         * Requests capture Stop for exactly [attempt], ignoring other attempts. Stop leaves the instance
         * open; processing and resource release complete after return. [fromNotification] marks the
         * notification action.
         */
        @AnyThread
        public fun requestStreamStop(attempt: CaptureAttemptId, fromNotification: Boolean = false)

        /**
         * Requests in-place recovery from the current operation error. A module that offers recovery
         * validates [errorToken] against its current error episode; the default ignores the request.
         */
        @AnyThread
        public fun requestRecovery(errorToken: Uuid): Unit = Unit

        /**
         * Closes new work and starts cleanup. Must be idempotent, valid before [startModule], and
         * independent of the control loop, which may be stalled.
         */
        @AnyThread
        public fun requestModuleShutdown()

        /**
         * Suspends until cleanup started by [requestModuleShutdown] finishes, including stopping the
         * original Service with [StreamingModuleService.stopAndAwaitDestruction] after capture cleanup.
         * Cancelling a waiter cancels only that wait. Returns true only when every step is confirmed;
         * physical release follows each resource owner's guarantees.
         */
        public suspend fun awaitCleanup(): Boolean

        /** Renders this instance's screen; the manager calls it only while the instance is admitted. */
        @Composable
        public fun Content(window: WindowSizeClass, modifier: Modifier = Modifier)

        /**
         * Called by the original Service from `onDestroy`, whether or not this controller requested the
         * stop. Close admission promptly; cleanup stays with this controller.
         */
        @MainThread
        public fun onServiceDestroyed()

        /**
         * An instance's connection to the manager, shared by its Service and controller.
         *
         * The manager creates it before Service delivery or controller construction, and it stays bound
         * to that instance through cleanup. It supplies identity, the foreground slot, admission checks,
         * and reports; manager commands go directly to the controller. Each admission check describes
         * only the current instant.
         */
        public interface ManagerHandle {
            /** Identity the created controller must expose for its whole lifetime. */
            public val instanceId: InstanceId

            /** Validated foreground slot of this instance's Service component. */
            public val foregroundSlot: StreamingModuleNotifications.ForegroundSlot

            /**
             * Whether this instance may still accept work; recheck before acquiring resources. Startup
             * admission expires at the manager's first-status deadline.
             */
            @AnyThread
            public fun isCurrent(): Boolean

            /**
             * Reports [status] after completed control work, including while idle, with a nondecreasing
             * `SystemClock.uptimeMillis` [heartbeatAtUptimeMillis]. Report at least once per second; take
             * heartbeats from control progress so a stalled loop becomes unresponsive. The first report
             * must meet the manager's startup deadline.
             *
             * Reports are ignored once admission closes. The manager may coalesce pending reports and
             * ignores timestamps older than the last applied one. A fresh report restores an unresponsive
             * instance.
             */
            @AnyThread
            public fun reportRunning(status: Status, heartbeatAtUptimeMillis: Long)

            /**
             * Declares terminal failure: closes admission and requests controller shutdown. [messageResource]
             * is shown only after a status was accepted; startup failures use generic text. Ignored once
             * shutdown has begun.
             */
            @AnyThread
            public fun reportFailed(@StringRes messageResource: Int? = null)

        }
    }
}
