package io.screenstream.streaming.module

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
 * Lightweight description of one streaming module.
 * [id] identifies the module; each launch has an [InstanceId], and each accepted capture Start has
 * a [CaptureAttemptId]. The entry itself has no launch identity. A [Controller] belongs to one
 * launch and reports through its runtime bound to that identity.
 *
 * The manager starts its declared Service before that Service creates a controller.
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

    /** Android Service the manager starts to open this module. */
    public val serviceClass: Class<out Service>

    /** Stable foreground notification slot reserved for [serviceClass]. */
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
     * @property isStreaming Whether capture is streaming, independently of consumer presence.
     * @property hasConsumer Whether a consumer is present, independently of capture state.
     * @property captureAttempt Accepted Start identity, retained through its cleanup; null when
     * no capture attempt is active.
     * @property errorMessage Current operation diagnostic while the module can continue; null when healthy.
     */
    public data class Status(
        public val isStreaming: Boolean,
        public val hasConsumer: Boolean,
        public val captureAttempt: CaptureAttemptId?,
        public val errorMessage: String?,
    )

    /**
     * Control and cleanup for one [InstanceId], separate from the process-level module entry.
     * Created without taking resources, then [startModule] is called only after installation. Distinct
     * launches must retain reference equality as installed controllers so StateFlow does not merge
     * them as equal values.
     */
    public interface Controller {
        /** Immutable launch identity, equal to [Runtime.instanceId]. */
        public val instanceId: InstanceId

        /**
         * Begin ordinary control work after installation without blocking the caller. Recheck
         * [Runtime.isCurrent] before admitting resources because shutdown may win this race.
         */
        @MainThread
        public fun startModule()

        /**
         * Request capture Start for this controller's instance without starting a new module launch.
         * A stale or unready request may be ignored. Return confirms neither admission nor processing.
         */
        @AnyThread
        public fun requestStreamStart()

        /**
         * Request capture Stop for the exact [attempt] owned by this controller's instance.
         * It must not stop a newer attempt or shut down the module. Return confirms neither
         * processing nor release of capture resources. [fromNotification] retains the action's origin.
         */
        @AnyThread
        public fun requestStreamStop(attempt: CaptureAttemptId, fromNotification: Boolean = false)

        /**
         * Request in-place recovery of this instance's current operation error. A module that offers
         * recovery validates [errorToken] against its current error episode before handling it. The
         * default has no action; recovery never implies a manager restart or a new Service.
         */
        @AnyThread
        public fun requestRecovery(errorToken: Uuid): Unit = Unit

        /**
         * Idempotently close new work and start cleanup independently of the control loop. This
         * must also work before [startModule] and must not wait for the loop to exit.
         */
        @AnyThread
        public fun requestModuleShutdown()

        /**
         * Wait until controller orchestration and its declared cleanup operations have settled after
         * [requestModuleShutdown]. Return true only when those operations succeeded. The controller
         * owns this work independently of callers, so cancelling a waiter cancels only its wait.
         * This includes stopping the original Service after capture cleanup settles and awaiting
         * its destruction. New controller work remains closed afterward. Best-effort media owners
         * may report false for failure or uncertainty; completion is not proof of physical resource
         * release beyond each owner's contract. Existing owners may provide stronger guarantees.
         */
        public suspend fun awaitCleanup(): Boolean

        /** Render this instance's screen; the manager calls it only while work is admitted. */
        @Composable
        public fun Content(window: WindowSizeClass, modifier: Modifier = Modifier)

        /**
         * Called only by the original Service after its destruction. Record the loss and close
         * admission without waiting for cleanup; never redirect it to a replacement controller.
         */
        @MainThread
        public fun onServiceDestroyed()

        /**
         * Created by the manager for one reserved launch and passed to the Service's controller
         * factory. Keep it for that controller's lifetime to check admission and report progress.
         * It stays bound to that instance and owns no Android resources.
         */
        public interface Runtime {
            /** Identity the created controller must expose for its whole lifetime. */
            public val instanceId: InstanceId

            /** Validated foreground slot for this admitted instance's exact Service component. */
            public val foregroundSlot: StreamingModuleNotifications.ForegroundSlot

            /** Whether this instance may still accept work; recheck before taking new resources. */
            @AnyThread
            public fun isCurrent(): Boolean

            /** Tell the manager about completed control work using its SystemClock.uptimeMillis timestamp. */
            @AnyThread
            public fun reportRunning(status: Status, heartbeatAtUptimeMillis: Long)

            /** Tell the manager this instance cannot continue; it closes admission and requests shutdown. */
            @AnyThread
            public fun reportFailed(@StringRes messageResource: Int? = null)

        }
    }
}
