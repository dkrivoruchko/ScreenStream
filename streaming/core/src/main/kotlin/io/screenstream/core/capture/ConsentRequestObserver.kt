package io.screenstream.core.capture

import androidx.annotation.MainThread
import io.screenstream.core.module.StreamingModule

/**
 * App-supplied observer of where each attempt's consent came from. Calls run synchronously on Main,
 * outside coordinator locks, once the decision is committed and before later cancellation checks; return
 * promptly. Exceptions, including cancellation, are logged and ignored. An observation does not imply
 * approval, a grant, or capture startup: later cancellation or failure can still end the attempt.
 */
public interface ConsentRequestObserver {
    /** Saved consent was taken for [attempt], before foreground preparation or grant delivery. */
    @MainThread
    public fun savedConsentTaken(attempt: StreamingModule.CaptureAttemptId)

    /**
     * [attempt]'s request was committed for Android's consent dialog; [educationAccepted] is true when this
     * request showed capture education and the user accepted it.
     */
    @MainThread
    public fun systemConsentSubmitted(attempt: StreamingModule.CaptureAttemptId, educationAccepted: Boolean)
}
