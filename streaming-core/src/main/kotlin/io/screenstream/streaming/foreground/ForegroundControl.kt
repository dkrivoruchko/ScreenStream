package io.screenstream.streaming.foreground

import android.app.Notification
import io.screenstream.streaming.module.StreamingModuleApi

/**
 * Foreground capability for one attached [StreamingModuleApi.InstanceId] and exact Android Service.
 * Results describe public Android API calls, not physical foreground or notification visibility.
 * A notification passed in [Request] must not be mutated after reservation.
 */
public interface ForegroundControl {
    /** Logical launch bound to this control. */
    public val instanceId: StreamingModuleApi.InstanceId

    /** Local admission snapshot; it does not reserve a later call. */
    public fun isOpen(): Boolean

    /** Reserve this host synchronously without starting Android work. The caller adopts the handle before suspending. */
    public fun reserve(request: Request): ReserveResult

    /** Fixed service types and a ready static notification for one capture attempt. */
    public data class Request(
        public val serviceTypes: Int,
        public val notification: Notification,
    )

    /** Result of a synchronous, resource-free reservation request. */
    public sealed interface ReserveResult {
        /** Caller now owns [reservation] and must release it unless transfer makes another owner responsible. */
        public class Reserved(public val reservation: Reservation) : ReserveResult

        /** Another reservation or its release still occupies this Service. */
        public data object Busy : ReserveResult

        /** Closed/stale launch or invalid request refused before Android admission. */
        public data object Rejected : ReserveResult
    }

    /** One exact foreground reservation; its notification ID and service types remain fixed. */
    public interface Reservation {
        /** Snapshot of unreleased ownership on an Open, live host; not promotion success proof. */
        public fun isCurrent(): Boolean

        /**
         * Admit at most one independent Android promotion. Repeated callers await the same result.
         * Cancelling a waiter does not cancel the call or a required later release. The release
         * prerequisite is started before a promotion result can wake its caller.
         */
        public suspend fun promote(): PromotionResult

        /**
         * Close promotion and release once. Repeated callers await the same terminal evidence;
         * waiter cancellation does not cancel Android work. [ReleaseResult.NotRequired] is possible
         * even for a returned reservation when promotion never reached Android admission.
         */
        public suspend fun release(): ReleaseResult
    }

    /** Evidence for the single promotion operation on a reservation. */
    public sealed interface PromotionResult {
        /** The exact-Service Android startForeground call returned normally. */
        public data object ApiCompleted : PromotionResult

        /** Promotion was refused before Android admission, including release or host loss. */
        public data object Rejected : PromotionResult

        /** The admitted call threw or ended without a confirmed normal return. */
        public class Failed(public val cause: Throwable) : PromotionResult
    }

    /**
     * Terminal public-call evidence for release. Pending work has no result; neither normal API
     * return nor Service destruction proves physical resource removal.
     */
    public sealed interface ReleaseResult {
        /** No promotion call was admitted. */
        public data object NotRequired : ReleaseResult

        /** The exact-Service Android REMOVE call returned normally. */
        public data object ApiCompleted : ReleaseResult

        /** Required REMOVE threw or ended without a confirmed normal return. */
        public class Failed(public val cause: Throwable) : ReleaseResult

        /** Host was lost before required REMOVE could be admitted. */
        public data object Unconfirmed : ReleaseResult
    }
}
