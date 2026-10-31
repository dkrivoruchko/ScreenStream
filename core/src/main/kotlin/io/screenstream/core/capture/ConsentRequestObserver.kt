package io.screenstream.core.capture

import androidx.annotation.MainThread
import io.screenstream.core.module.StreamingModule

/**
 * Observes committed consent-source decisions. Calls are synchronous on Main, outside coordinator
 * locks, before subsequent cancellation checks or functional handoff. Implementations must accept
 * promptly; observer exceptions, including their own cancellation exception, are isolated.
 *
 * The attempt and host retain control of consent, foreground work, and capture lifetime. Android
 * approval, grant delivery, foreground preparation, and capture startup are separate outcomes of
 * subsequent steps; later cancellation can interrupt them after an observation has been delivered.
 */
public interface ConsentRequestObserver {
    /** Saved consent was taken for [attempt], before foreground preparation or grant delivery. */
    @MainThread
    public fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId)

    /** Android submission was committed; [educationAccepted] records acceptance of the app education. */
    @MainThread
    public fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean)
}
