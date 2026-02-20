package info.dvkr.screenstream.common.analytics

public object StreamingAnalyticsSchema {

    public object EventName {
        public const val STREAM_START_ATTEMPT: String = "stream_start_attempt"
        public const val STREAM_STARTED: String = "stream_started"
        public const val STREAM_START_FAILED: String = "stream_start_failed"
        public const val STREAM_ENDED: String = "stream_ended"
    }

    public object ParamName {
        public const val STREAM_MODE: String = "stream_mode"
        public const val ENTRY_POINT: String = "entry_point"
        public const val START_FAIL_GROUP: String = "start_fail_group"
        public const val STOP_REASON_GROUP: String = "stop_reason_group"
        public const val DURATION_BUCKET: String = "duration_bucket"
        public const val MAX_ACTIVE_CONSUMERS_BUCKET: String = "max_active_consumers_bucket"
        public const val HAD_ACTIVE_CONSUMER: String = "had_active_consumer"
        public const val SUCCESSFUL_SESSION: String = "successful_session"
    }
}

public enum class StreamMode(public val analyticsValue: String) {
    MJPEG("mjpeg"),
    RTSP_SERVER("rtsp_server"),
    RTSP_CLIENT("rtsp_client"),
    WEBRTC("webrtc")
}

public enum class EntryPoint(public val analyticsValue: String) {
    BUTTON("button"),
    WEB("web"),
    UNKNOWN("unknown")
}

public enum class StartFailGroup(public val analyticsValue: String) {
    PERMISSION_DENIED("permission_denied"),
    BUSY("busy"),
    BLOCKED("blocked"),
    FATAL("fatal"),
    UNKNOWN("unknown")
}

public enum class StopReasonGroup(public val analyticsValue: String) {
    USER("user"),
    SYSTEM("system"),
    ERROR("error"),
    UNKNOWN("unknown")
}

public enum class DurationBucket(public val analyticsValue: String) {
    LT_1M("lt_1m"),
    FROM_1_TO_5M("1_5m"),
    FROM_5_TO_15M("5_15m"),
    FROM_15_TO_60M("15_60m"),
    GT_60M("gt_60m")
}

public enum class MaxActiveConsumersBucket(public val analyticsValue: String) {
    ZERO("0"),
    ONE("1"),
    FROM_2_TO_3("2_3"),
    FOUR_PLUS("4_plus")
}
