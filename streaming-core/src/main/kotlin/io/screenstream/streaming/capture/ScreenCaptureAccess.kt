package io.screenstream.streaming.capture

import android.media.projection.MediaProjection
import androidx.annotation.WorkerThread
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.Job

/** Coordinates screen consent and prevents overlapping projection creation across streaming modules. */
public interface ScreenCaptureAccess {
    /**
     * Request fresh screen consent from the process-wide selected resumed host, or take saved
     * process-wide consent on SDK 24–33. Selection follows the latest eligible host report and falls
     * back to a remaining resumed host. Selection changes abandon an unsubmitted request; a submitted
     * request keeps its keyed result ownership across host changes.
     *
     * The calling coroutine must run under the installed capture's [attemptJob]; the returned grant
     * retains that lifetime rather than the short-lived requesting child.
     *
     * Admission and [prepareForeground] run on Main. After matching successful consent, invoke that
     * callback synchronously without waiting for Activity resume. It rechecks the exact attempt,
     * module admission and permissions, then returns immediately after foreground API completion.
     * False rejects grant delivery. Install the attempt's rollback owner before requesting: cancellation
     * after promotion can prevent delivery of the resource-free grant, leaving rollback with that owner.
     *
     * Normal outcomes are explicit. Technical failures throw; only matching Android launcher failures
     * are wrapped in [ConsentLaunchException]. Cancellation removes the wait and ignores late results,
     * but cannot dismiss Android's dialog. Saved consent is consumed on admission, so failed startup
     * requires fresh consent next time. A completed requesting child does not invalidate its grant.
     */
    public suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ConsentResult

    public sealed interface ConsentResult {
        public data class Granted(public val grant: Grant) : ConsentResult

        public data class NotGranted(public val reason: NoGrantReason) : ConsentResult
    }

    public enum class NoGrantReason {
        Busy,
        HostUnavailable,
        EducationDeclined,
        Declined,
        ForegroundRejected,
    }

    /**
     * Consume this coordinator's grant once after foreground preparation. An inactive attempt Job
     * throws CancellationException. Store the returned projection before suspension or fallible work;
     * cancellation after Android returns must not discard it. Invalid, consumed or overlapping
     * acquisition throws, and platform failure also consumes the grant. The exact creation guard
     * remains held until [finishProjectionCreation].
     *
     * [retryAfterRecentForegroundStart] explicitly selects the existing pre-34 compatibility retry:
     * one retry after 100 ms for SecurityException when acquisition began within 1500 ms of successful
     * foreground preparation. Both calls share the consumed grant and guard. The default never retries.
     */
    @WorkerThread
    public fun createProjection(grant: Grant, retryAfterRecentForegroundStart: Boolean = false): MediaProjection

    /**
     * Release this attempt's creation guard after ownership handoff or completed raw rollback.
     * Idempotent and exact: another attempt and an in-flight platform call are never released.
     * Call again after that call and any rollback have finished.
     */
    public fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId)

    /**
     * Save consent on SDK 24–33 only after successful startup, a normal requested stop and successful
     * media cleanup under the owner's declared contract. Skip observed failure, timeout, revocation
     * and explicitly unconfirmed stop; no stronger physical-release proof is implied. The attempt Job
     * may already be cancelled. Only the latest admitted attempt can save once, so older cleanup
     * cannot overwrite a later consent decision.
     */
    public fun saveConsentForReuse(grant: Grant)

    /** Opaque one-use consent bound to its original attempt; owns no Android resource or reservation. */
    public interface Grant
}

/** A matching Android consent launcher failed; [cause] retains the original technical failure. */
public class ConsentLaunchException(cause: Throwable) : RuntimeException("Could not launch screen capture consent", cause)
