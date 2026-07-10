package dev.dmkr.screencaptureengine

/**
 * Best-effort diagnostic event for logging, telemetry, or debug UI.
 *
 * Events are not a control-flow contract. State, stats, update return values, and frame callbacks remain authoritative for their respective surfaces.
 */
public class ScreenCaptureEvent public constructor(
    /** Monotonic event sequence. */
    public val sequence: Long,

    /** Event timestamp from elapsed realtime, in nanoseconds. */
    public val timestampElapsedRealtimeNanos: Long,

    /** Stable event category. */
    public val type: ScreenCaptureEventType,

    /** Optional associated problem. */
    public val problem: ScreenCaptureProblem? = null,

    /** Optional diagnostic text, not a parsing contract. */
    public val message: String? = null,
) {
    init {
        require(sequence >= 0L) { "sequence must be non-negative, was $sequence" }
        require(timestampElapsedRealtimeNanos >= 0L) {
            "timestampElapsedRealtimeNanos must be non-negative, was $timestampElapsedRealtimeNanos"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureEvent && sequence == other.sequence && timestampElapsedRealtimeNanos == other.timestampElapsedRealtimeNanos &&
                type == other.type && problem == other.problem && message == other.message

    public override fun hashCode(): Int =
        31 * (31 * (31 * (31 * sequence.hashCode() + timestampElapsedRealtimeNanos.hashCode()) + type.hashCode()) + (problem?.hashCode() ?: 0)) +
                (message?.hashCode() ?: 0)
}

/** Stable diagnostic event category. */
public enum class ScreenCaptureEventType {
    SessionStarted,
    SessionStopped,
    SessionFailed,
    CaptureGeometryChanged,
    CaptureTargetChanged,
    InvalidMetricsIgnored,
    OutputPlanApplied,
    OutputPlanRejected,
    OutputPlanSuspended,
    OutputPlanResumed,
    ReadbackModeChanged,
    EncoderChanged,
    EncodedFrameDropped,
    SlowConsumerPressure,
    FrameDeliveryFailure,
    MemoryTrimmed,
}
