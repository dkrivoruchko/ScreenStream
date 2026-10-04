package io.screenstream.streaming.capture

import android.media.projection.MediaProjection
import io.screenstream.streaming.module.StreamingModule

/** Coordinates screen consent and prevents overlapping projection creation across streaming modules. */
public interface ScreenCaptureAccess {
    /**
     * Ask for screen capture consent from a resumed Activity, or take saved process-wide consent
     * on SDK 24–33. Returns null on decline, Busy or when a new dialog needs an unavailable resumed
     * host. Technical failures throw. Caller cancellation removes its wait and ignores late results;
     * it does not dismiss Android's dialog. The grant owns no Android resource. Saved consent is
     * consumed by an admitted request, so a failed startup requires fresh consent next time.
     * The grant retains the requesting coroutine's Job, which must stay active through
     * [createProjection]. Request consent in the capture's lifetime coroutine, not a short-lived child.
     */
    public suspend fun requestConsent(attempt: StreamingModule.CaptureAttemptId): Grant?

    /**
     * Consume this coordinator's grant once after foreground promotion. Check the requesting Job
     * at creation admission; an inactive Job throws CancellationException. The attempt worker
     * must store the returned projection before suspending or doing other fallible work. Invalid,
     * already consumed or overlapping creation throws; a platform failure also consumes the grant.
     * The creation guard remains owned by the attempt until [finishProjectionCreation].
     */
    public fun createProjection(grant: Grant): MediaProjection

    /**
     * Release this attempt's creation guard after Engine ownership transfer or completed raw rollback.
     * Idempotent and exact: another attempt and an in-flight platform call are never released.
     * The worker must call again after that call and any rollback have finished.
     */
    public fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId)

    /**
     * Save consent on SDK 24–33 only after successful startup, a normal requested stop and confirmed
     * cleanup. Never call after failed startup, revocation or failed cleanup. The requesting Job may
     * already be cancelled. Only the latest admitted attempt can save once; an older cleanup cannot
     * overwrite a newer attempt's consent decision.
     */
    public fun saveConsentForReuse(grant: Grant)

    /** Opaque, one-use consent bound to its original capture attempt; owns no resource or reservation. */
    public interface Grant
}
