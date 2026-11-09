package io.screenstream.core.capture

import android.media.projection.MediaProjection
import androidx.annotation.WorkerThread
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.Job

/**
 * Process-wide coordinator for screen-capture consent and [MediaProjection] acquisition.
 *
 * It admits one consent request or projection creation at a time. A successful request yields a one-use
 * [Grant]; [createProjection] consumes it and holds a creation guard until [finishProjectionCreation]. The
 * caller owns the returned projection until its media owner accepts it; the media owner then governs its
 * lifetime and release. All state, including saved consent, is process-local.
 *
 * Attempt order: [requestConsent], [createProjection], media handoff or rollback,
 * [finishProjectionCreation], then optionally [saveConsentForReuse] after a normal stop.
 */
public interface ScreenCaptureAccess {
    /**
     * Requests consent for [attempt] and, on success, runs [prepareForeground] before returning a grant.
     * Switches to Main. Call from a coroutine under [attemptJob], the attempt's lifetime Job. A returned grant
     * stays usable after the requesting child completes, for as long as that Job is active.
     *
     * On SDK 24–33, consent saved by [saveConsentForReuse] is used without a host and consumed at once,
     * even if later startup fails. Otherwise the selected resumed `ScreenCaptureConsentHost` shows education
     * and Android's consent dialog. Once submitted, a request keeps its result association across host
     * changes and Activity recreation. A request can stay pending if its host is lost without a result;
     * cancelling the attempt ends it.
     *
     * After successful consent, [prepareForeground] runs synchronously on Main, even while the Activity is
     * paused. It must recheck the attempt, module admission, and permissions, and return true only after the
     * foreground call completes. Cancellation can still win after it returns true, so the attempt's cleanup
     * must undo foreground preparation even without a grant.
     *
     * Normal outcomes are [ConsentResult]s; cancellation and technical failures throw. A launcher failure
     * is wrapped in [ConsentLaunchException]; a result without data or before submission throws
     * [IllegalStateException]. Cancelling ends the wait but not Android's dialog, whose late result is ignored.
     */
    public suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ConsentResult

    public sealed interface ConsentResult {
        /** Consent and foreground preparation succeeded; projection creation is still required. */
        public data class Granted(public val grant: Grant) : ConsentResult

        /** Consent ended normally without a grant. */
        public data class NotGranted(public val reason: NoGrantReason) : ConsentResult
    }

    public enum class NoGrantReason {
        /** Another consent request is pending or a creation guard is held; retry with a later request. */
        Busy,

        /**
         * No resumed host for fresh consent, host selection changed before submission, or the submitting
         * launcher was disposed while its Activity was finishing.
         */
        HostUnavailable,

        /** Capture education was dismissed or cancelled before Android submission. */
        EducationDeclined,

        /** Android returned a non-success consent result. */
        Declined,

        /** `prepareForeground` returned false. */
        ForegroundRejected,
    }

    /**
     * Consumes [grant] and creates its projection, blocking the calling worker thread. An inactive attempt
     * throws CancellationException; a foreign or consumed grant and overlapping consent or creation throw
     * [IllegalStateException]. Once admitted, the grant is consumed and the guard stays held until
     * [finishProjectionCreation], even when the platform call fails.
     *
     * Store the returned projection before any suspension or fallible work. Until a media owner accepts it,
     * the caller must stop it after failure or cancellation, then call [finishProjectionCreation].
     *
     * [retryAfterRecentForegroundStart] allows one retry, after 100 ms, of a SecurityException below API 34
     * when creation began within 1500 ms of foreground preparation.
     */
    @WorkerThread
    public fun createProjection(grant: Grant, retryAfterRecentForegroundStart: Boolean = false): MediaProjection

    /**
     * Releases [attempt]'s creation guard after media handoff or completed rollback. Idempotent; ignored for
     * other attempts and while [createProjection] is still running, so call it after that call returns or throws.
     */
    public fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId)

    /**
     * Saves [grant]'s consent for the next request on SDK 24–33; API 34+ always needs fresh consent. Call
     * only after successful startup, a requested stop, and confirmed media cleanup; failure, timeout,
     * revocation, or an unconfirmed stop make the attempt ineligible. The coordinator trusts this
     * certification. Only a consumed grant of the most recently admitted attempt is saved, at most once.
     * The attempt Job may already be cancelled.
     */
    public fun saveConsentForReuse(grant: Grant)

    /** Opaque, one-use consent for one attempt, valid only with the coordinator that issued it. */
    public interface Grant
}

/** A matching Android consent launcher failed; [cause] retains the original technical failure. */
public class ConsentLaunchException(cause: Throwable) : RuntimeException("Could not launch screen capture consent", cause)
