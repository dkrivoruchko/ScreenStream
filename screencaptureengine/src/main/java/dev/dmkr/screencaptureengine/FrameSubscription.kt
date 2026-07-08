package dev.dmkr.screencaptureengine

/** Handle for a frame callback registration. */
public interface FrameSubscription {
    /**
     * Cancels future deliveries.
     *
     * Thread-safe and idempotent; does not interrupt a callback invocation that has already been admitted.
     */
    public fun cancel()
}
