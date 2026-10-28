package info.dvkr.screenstream.common.analytics

import kotlin.uuid.Uuid

/** Complete process-local identity; no identifier is exported to the analytics sink. */
public data class SessionKey(
    public val moduleId: String,
    public val instanceId: Uuid,
    public val attemptId: Uuid,
)

/** Creates recordings without making functional capture owners carry analytics metadata. */
public interface StreamingSessionAnalyticsFactory {
    /**
     * Attach inside the entered consent worker, immediately before its protected consent call.
     * Always call [StreamingSessionAnalytics.consentCallFinished] from that call's finally block.
     * Registration alone emits no analytics attempt.
     */
    public fun beginConsent(
        key: SessionKey,
        streamMode: StreamMode,
        entryPoint: EntryPoint,
    ): StreamingSessionAnalytics

    /** Preserve an existing explicit rejection before consent acquisition or education. */
    public fun preflightFailed(streamMode: StreamMode, entryPoint: EntryPoint, startFailGroup: StartFailGroup)
}

/** One attempt, retained by its original module adapter through the existing media-stop milestone. */
public interface StreamingSessionAnalytics {
    public fun started(activeConsumers: Int, atElapsedRealtimeMs: Long)
    public fun startFailed(startFailGroup: StartFailGroup)
    public fun startAborted()
    public fun consumersChanged(activeConsumers: Int, atElapsedRealtimeMs: Long)
    public fun ended(stopReasonRaw: String?, activeConsumers: Int, atElapsedRealtimeMs: Long)

    /**
     * Nonthrowing bookkeeping after the original consent call returns or throws. The app schedules
     * settlement on Main after any already committed synchronous consent observation finishes.
     * This neither ends a started session nor waits for capture cleanup.
     */
    public fun consentCallFinished()
}
