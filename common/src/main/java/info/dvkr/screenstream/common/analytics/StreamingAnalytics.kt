package info.dvkr.screenstream.common.analytics

public interface StreamingAnalytics {
    public fun logEvent(event: StreamingAnalyticsEvent)
}

public sealed interface StreamingAnalyticsEvent {
    public data class StreamStartAttempt(
        public val streamMode: StreamMode,
        public val entryPoint: EntryPoint,
    ) : StreamingAnalyticsEvent

    public data class StreamStarted(
        public val streamMode: StreamMode,
        public val entryPoint: EntryPoint,
    ) : StreamingAnalyticsEvent

    public data class StreamStartFailed(
        public val streamMode: StreamMode,
        public val entryPoint: EntryPoint,
        public val startFailGroup: StartFailGroup,
    ) : StreamingAnalyticsEvent

    public data class StreamEnded(
        public val streamMode: StreamMode,
        public val entryPoint: EntryPoint,
        public val stopReasonGroup: StopReasonGroup,
        public val durationBucket: DurationBucket,
        public val maxActiveConsumersBucket: MaxActiveConsumersBucket,
        public val hadActiveConsumer: Boolean,
        public val successfulSession: Boolean,
    ) : StreamingAnalyticsEvent
}
