package io.screenstream.core.capture

import android.media.projection.MediaProjection
import androidx.annotation.WorkerThread
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.Job

/**
 * Coordinates process-wide screen consent and projection acquisition for streaming modules.
 *
 * A grant carries consent metadata; each acquisition still requires admission. Once creation is
 * admitted, its guard coordinates competing consent and creation until media handoff or completed raw
 * rollback. The media owner governs the projection's active lifetime and physical release. The caller
 * owns a returned projection until its chosen media owner accepts it. Requests, grants, and saved
 * consent are process-local; a new process starts with fresh access state.
 */
public interface ScreenCaptureAccess {
    /**
     * Request consent for [attempt], using saved consent on SDK 24–33 when available or the selected
     * resumed Activity host otherwise. The latest eligibility report selects the host, falling back to
     * another resumed host when needed. Admission runs on Main. Pending consent or a held creation guard
     * returns [NoGrantReason.Busy] immediately. Fresh consent requires an eligible host; otherwise the
     * result is [NoGrantReason.HostUnavailable]. A host selection change ends a request awaiting submission;
     * a submitted request keeps its keyed result association across host changes and Activity recreation.
     *
     * Call under the installed capture's [attemptJob]. A grant retains that job through the lifetime of
     * the attempt, including after its requesting child completes. After successful consent,
     * [prepareForeground] runs synchronously on Main, including when the Activity is paused. It must
     * recheck the exact attempt, module admission, and permissions, then return after foreground API
     * completion. False rejects delivery. Install the attempt's rollback owner before requesting:
     * cancellation after promotion can interrupt delivery of the resource-free grant.
     *
     * Normal non-grant outcomes are explicit; technical failures throw. A matching launcher failure is
     * wrapped in [ConsentLaunchException]. Cancellation removes the wait; Android's dialog can remain
     * visible and late results are ignored. Missing result data and results before submission are technical
     * failures. Saved consent is consumed when admitted, even if subsequent startup fails.
     */
    public suspend fun requestConsent(
        attempt: StreamingModule.CaptureAttemptId,
        attemptJob: Job,
        prepareForeground: () -> Boolean,
    ): ConsentResult

    public sealed interface ConsentResult {
        /** Consent and foreground preparation succeeded; projection creation is still required. */
        public data class Granted(public val grant: Grant) : ConsentResult

        /** A normal non-grant outcome; technical failure and cancellation use the exception path. */
        public data class NotGranted(public val reason: NoGrantReason) : ConsentResult
    }

    public enum class NoGrantReason {
        /** A pending consent request or creation guard occupies admission; retry with a later request. */
        Busy,

        /** Fresh consent ended because an eligible host was unavailable or selection changed before submission. */
        HostUnavailable,

        /** Capture education was dismissed or cancelled before Android submission. */
        EducationDeclined,

        /** Android returned a non-success consent result. */
        Declined,

        /** The synchronous foreground callback rejected preparation. */
        ForegroundRejected,
    }

    /**
     * Consume this coordinator's grant once and acquire a projection after foreground preparation.
     * An inactive attempt job throws CancellationException. Invalid or consumed grants and overlapping
     * acquisition throw; a platform failure also consumes the grant. Once creation is admitted, the guard
     * remains held until [finishProjectionCreation], including when the platform call throws.
     *
     * Store the returned projection before suspension or fallible work to retain ownership across
     * cancellation. Until a media owner accepts it, the caller must stop it after startup failure or
     * cancellation and settle that rollback before releasing the guard.
     *
     * [retryAfterRecentForegroundStart] enables one pre-API-34 SecurityException retry after 100 ms if
     * acquisition began within 1500 ms of foreground preparation. Both calls share the grant and guard;
     * the default makes at most one platform acquisition call.
     */
    @WorkerThread
    public fun createProjection(grant: Grant, retryAfterRecentForegroundStart: Boolean = false): MediaProjection

    /**
     * Release this attempt's creation guard after ownership handoff or completed raw rollback. The
     * operation is idempotent and applies only to the matching attempt after the platform call settles.
     * An admitted creation that throws still holds the guard; finish that path after the call settles too.
     * If called while creation is in flight, call again after that call and any rollback have finished.
     */
    public fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId)

    /**
     * Save consent on SDK 24–33 only after successful startup, a normal requested stop and successful
     * media cleanup under the owner's declared contract. Observed failure, timeout, revocation, and an
     * unconfirmed stop make the attempt ineligible. Physical release has the media owner's guarantees.
     * The attempt Job may already be cancelled. Only the latest admitted attempt's consumed grant can
     * save once, keeping older cleanup scoped to its own decision. The caller certifies the media outcome;
     * the coordinator relies on that certification. API 34 and later always need fresh consent.
     */
    public fun saveConsentForReuse(grant: Grant)

    /**
     * Opaque one-use consent metadata bound to its coordinator and original attempt. Projection
     * acquisition has its own admission and resource lifetime.
     */
    public interface Grant
}

/** A matching Android consent launcher failed; [cause] retains the original technical failure. */
public class ConsentLaunchException(cause: Throwable) : RuntimeException("Could not launch screen capture consent", cause)
