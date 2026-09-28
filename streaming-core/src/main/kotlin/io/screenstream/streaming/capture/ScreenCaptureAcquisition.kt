package io.screenstream.streaming.capture

import android.media.projection.MediaProjection
import io.screenstream.streaming.foreground.ForegroundControl
import io.screenstream.streaming.module.StreamingModuleApi

/**
 * One process-wide screen acquisition at a time. The acquisition owner holds consent, raw projection,
 * and foreground reservation until their cancellation-safe transfer to a module.
 * Once applicable rollback calls have terminal outcomes, acquisition may release its slot and raw
 * references before its still-required fast report; an ordinary result follows that report. A
 * resource-free consent wait may detach its slot immediately on revocation while its report remains
 * owed. Pending resource-producing or cleanup calls keep the operation occupied. None of this
 * proves physical cleanup.
 */
public interface ScreenCaptureAcquisition {
    /**
     * Acquire resources for [attempt]. A mismatched [foregroundControl] instance, stale attempt,
     * closed admission, or occupied slot returns [Result.Rejected] or [Result.Busy] directly, without
     * an installed operation or rollback report. An accepted operation is installed before the first
     * cancellable suspension. Cancellation stops this wait, not independently owned production or
     * rollback. Ordinary Failed or Declined returns follow terminal rollback; a cancelled waiter
     * may leave earlier. [isAttemptCurrent] must be a fast nonthrowing snapshot of this attempt, not a
     * reservation; becoming stale does not cancel required cleanup.
     *
     * Fresh screen consent needs a RESUMED Activity. An already current screen-consent result can
     * progress while paused; microphone permission and while-in-use eligibility remain separate.
     * [reusableConsent] is an optional prior old-platform candidate from the acquisition owner;
     * an ineligible candidate is discarded in favor of fresh consent. A rejected cached grant fails this attempt without
     * an automatic second consent request.
     */
    public suspend fun acquire(
        attempt: StreamingModuleApi.CaptureAttemptId,
        request: ForegroundControl.Request,
        foregroundControl: ForegroundControl,
        isAttemptCurrent: () -> Boolean,
        reports: Reports,
        reusableConsent: ReusableConsent? = null,
    ): Result

    /**
     * Atomically revoke only this attempt while it still awaits screen consent. True permits a
     * replacement attempt while the revoked operation still owes its terminal report; false leaves
     * an advanced operation or different owner in place.
     */
    public fun cancelIfAwaitingConsent(attempt: StreamingModuleApi.CaptureAttemptId): Boolean

    /**
     * Opaque prior consent candidate from the acquisition owner for SDK 24–33 and its original module instance.
     * Modules only store and return this value; they cannot inspect or copy its result Intent.
     */
    public interface ReusableConsent

    /** Stage of the original acquisition failure; rollback steps report separately. */
    public enum class Stage { CONSENT, FOREGROUND, PROJECTION }

    /** Result of one acquisition request. */
    public sealed interface Result {
        /**
         * Module setup immediately adopts [projection], [reservation], and [reusableConsent] before
         * its next suspension or dispatch. Successful session creation transfers the projection to
         * the capture engine; until then module setup owns its cleanup.
         */
        public class Acquired(
            public val projection: MediaProjection,
            public val reservation: ForegroundControl.Reservation,
            public val reusableConsent: ReusableConsent?,
        ) : Result

        /** User declined screen consent; the installed operation still reports terminal rollback. */
        public data object Declined : Result

        /** A different acquisition or rollback occupies the process slot; no operation was installed. */
        public data object Busy : Result

        /** Closed, mismatched, or stale request refused before an operation was installed. */
        public data object Rejected : Result

        /** Original failure; independent rollback outcomes reach [Reports.reportRollbackFinished]. */
        public class Failed(public val stage: Stage, public val cause: Throwable) : Result
    }

    /** Public-call evidence for projection cleanup owned by this acquisition operation. */
    public sealed interface ProjectionStopResult {
        /** No projection needed stopping by acquisition. */
        public data object NotRequired : ProjectionStopResult

        /** MediaProjection.stop returned normally; physical release is not confirmed. */
        public data object ApiCompleted : ProjectionStopResult

        /** The stop call threw or ended without a confirmed normal return. */
        public class Failed(public val cause: Throwable) : ProjectionStopResult
    }

    /**
     * Original failure and independent terminal rollback evidence. [primaryFailure] is null when
     * there was no acquisition failure, including Declined or cancellation. Terminal failed calls
     * preserve evidence in the attempt owner; they do not require retaining raw payload forever.
     */
    public data class RollbackResult(
        public val primaryFailure: Result.Failed?,
        public val projectionStop: ProjectionStopResult,
        public val foregroundRelease: ForegroundControl.ReleaseResult,
    )

    /**
     * Fast nonthrowing reports to the exact attempt owner, including after its waiting coroutine
     * ends. Every installed nontransferred operation reports rollback exactly once, including
     * Declined and cancellation before its resource worker; preentry Busy/Rejected never report.
     * The receiver stores evidence before waking its control loop.
     */
    public interface Reports {
        /** Report accepted fresh consent or selection of an eligible cached grant. */
        public fun reportConsentAccepted()

        /** Report terminal outcomes for this installed attempt after all applicable rollback calls. */
        public fun reportRollbackFinished(result: RollbackResult)
    }
}
