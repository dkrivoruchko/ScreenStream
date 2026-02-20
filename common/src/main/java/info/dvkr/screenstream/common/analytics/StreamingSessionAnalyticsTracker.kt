package info.dvkr.screenstream.common.analytics

public class StreamingSessionAnalyticsTracker(
    private val analytics: StreamingAnalytics,
    private val streamModeProvider: () -> StreamMode,
    private val nowElapsedRealtimeMs: () -> Long,
) {

    private companion object {
        private const val MIN_SUCCESSFUL_SESSION_USEFUL_MS: Long = 60_000L
    }

    private var pendingEntryPoint: EntryPoint = EntryPoint.UNKNOWN

    private var sessionActive: Boolean = false
    private var sessionStreamMode: StreamMode = StreamMode.MJPEG
    private var sessionEntryPoint: EntryPoint = EntryPoint.UNKNOWN
    private var sessionStartedAtElapsedMs: Long = 0L
    private var usefulSegmentStartElapsedMs: Long? = null
    private var usefulStreamingMs: Long = 0L
    private var maxActiveConsumers: Int = 0
    private var hadActiveConsumer: Boolean = false

    public fun onStartAttempt(entryPoint: EntryPoint) {
        if (!sessionActive && pendingEntryPoint != EntryPoint.UNKNOWN) return
        pendingEntryPoint = entryPoint
        analytics.logEvent(StreamingAnalyticsEvent.StreamStartAttempt(streamMode = streamModeProvider.invoke(), entryPoint = entryPoint))
    }

    public fun onStartFailed(startFailGroup: StartFailGroup) {
        analytics.logEvent(
            StreamingAnalyticsEvent.StreamStartFailed(
                streamMode = streamModeProvider.invoke(), entryPoint = pendingEntryPoint, startFailGroup = startFailGroup
            )
        )
        pendingEntryPoint = EntryPoint.UNKNOWN
    }

    public fun onStartAborted() {
        if (sessionActive || pendingEntryPoint == EntryPoint.UNKNOWN) return
        onStartFailed(StartFailGroup.UNKNOWN)
    }

    public fun onStarted(initialActiveConsumers: Int = 0) {
        val now: Long = nowElapsedRealtimeMs.invoke()
        val streamMode: StreamMode = streamModeProvider.invoke()
        val entryPoint: EntryPoint = pendingEntryPoint

        analytics.logEvent(StreamingAnalyticsEvent.StreamStarted(streamMode = streamMode, entryPoint = entryPoint))

        pendingEntryPoint = EntryPoint.UNKNOWN
        sessionActive = true
        sessionStreamMode = streamMode
        sessionEntryPoint = entryPoint
        sessionStartedAtElapsedMs = now
        usefulSegmentStartElapsedMs = null
        usefulStreamingMs = 0L
        maxActiveConsumers = 0
        hadActiveConsumer = false

        updateActiveConsumers(initialActiveConsumers, now)
    }

    public fun onActiveConsumersChanged(activeConsumers: Int) {
        if (!sessionActive) return
        updateActiveConsumers(activeConsumers, nowElapsedRealtimeMs.invoke())
    }

    public fun onEnded(stopReasonRaw: String?, activeConsumers: Int) {
        if (!sessionActive) return

        val now: Long = nowElapsedRealtimeMs.invoke()
        updateActiveConsumers(activeConsumers, now)
        closeUsefulSegment(now)

        val durationMs: Long = (now - sessionStartedAtElapsedMs).coerceAtLeast(0L)
        val successfulSession: Boolean = usefulStreamingMs >= MIN_SUCCESSFUL_SESSION_USEFUL_MS

        analytics.logEvent(
            StreamingAnalyticsEvent.StreamEnded(
                streamMode = sessionStreamMode,
                entryPoint = sessionEntryPoint,
                stopReasonGroup = StreamingAnalyticsMappers.mapStopReasonGroup(stopReasonRaw),
                durationBucket = StreamingAnalyticsMappers.mapDurationBucket(durationMs),
                maxActiveConsumersBucket = StreamingAnalyticsMappers.mapMaxActiveConsumersBucket(maxActiveConsumers),
                hadActiveConsumer = hadActiveConsumer,
                successfulSession = successfulSession
            )
        )

        clearSession()
    }

    private fun updateActiveConsumers(activeConsumers: Int, now: Long) {
        val normalizedActiveConsumers: Int = activeConsumers.coerceAtLeast(0)
        if (normalizedActiveConsumers > 0) {
            hadActiveConsumer = true
            if (usefulSegmentStartElapsedMs == null) usefulSegmentStartElapsedMs = now
        } else {
            closeUsefulSegment(now)
        }

        if (normalizedActiveConsumers > maxActiveConsumers) {
            maxActiveConsumers = normalizedActiveConsumers
        }
    }

    private fun closeUsefulSegment(now: Long) {
        val segmentStart: Long = usefulSegmentStartElapsedMs ?: return
        usefulStreamingMs += (now - segmentStart).coerceAtLeast(0L)
        usefulSegmentStartElapsedMs = null
    }

    private fun clearSession() {
        sessionActive = false
        sessionEntryPoint = EntryPoint.UNKNOWN
        sessionStartedAtElapsedMs = 0L
        usefulSegmentStartElapsedMs = null
        usefulStreamingMs = 0L
        maxActiveConsumers = 0
        hadActiveConsumer = false
    }
}
