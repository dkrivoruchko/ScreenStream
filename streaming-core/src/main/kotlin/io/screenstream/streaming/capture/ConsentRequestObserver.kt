package io.screenstream.streaming.capture

import androidx.annotation.MainThread
import io.screenstream.streaming.module.StreamingModule

/**
 * Fixed observation of committed consent-source decisions. Calls are synchronous on Main, outside
 * coordinator locks, before subsequent cancellation checks or functional handoff. Implementations
 * must accept promptly; observer exceptions, including their own cancellation exception, are isolated.
 * These observations do not control consent, foreground work or capture lifetime.
 */
public interface ConsentRequestObserver {
    @MainThread
    public fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId)

    @MainThread
    public fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean)
}
