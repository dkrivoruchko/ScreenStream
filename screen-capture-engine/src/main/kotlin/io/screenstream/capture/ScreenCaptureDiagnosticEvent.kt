package io.screenstream.capture

/**
 * A best-effort, informational diagnostic event for one screen capture session.
 *
 * Events are published through the replay-free, positive-capacity, bounded, lossy, nonblocking
 * [ScreenCaptureSession.diagnosticEvents] flow. Diagnostics are non-authoritative: their presence, absence, content,
 * count, order, loss, and collector behavior are not lifecycle evidence and cannot be used as a cleanup receipt.
 * Sources and event names are extensible. Sequence exhaustion silently stops later diagnostic emission. Instances
 * retain identity equality.
 *
 * @property sequence a positive, nonrepeating, nonwrapping session-local identifier. Collectors may observe gaps,
 * and the value does not guarantee delivery or observed ordering.
 * @property timestampEpochMillis a wall-clock timestamp for approximate correlation only; it is not a monotonic
 * lifecycle or ordering signal.
 * @property source an extensible best-effort origin label, not a closed or stable taxonomy.
 * @property eventName an extensible best-effort event label, not a closed or stable taxonomy.
 * @property message a short, noncontractual diagnostic description that callers must not parse for semantics.
 * @property cause optional opaque diagnostic context. Its presence, type, identity, object graph, and text are not
 * API guarantees.
 */
public class ScreenCaptureDiagnosticEvent private constructor(
    public val sequence: Long,
    public val timestampEpochMillis: Long,
    public val source: String,
    public val eventName: String,
    public val message: String,
    public val cause: Throwable?,
) {
    init {
        require(sequence > 0L)
    }

    internal companion object {
        @JvmSynthetic
        internal fun create(
            sequence: Long,
            timestampEpochMillis: Long,
            source: String,
            eventName: String,
            message: String,
            cause: Throwable? = null,
        ): ScreenCaptureDiagnosticEvent = ScreenCaptureDiagnosticEvent(
            sequence = sequence,
            timestampEpochMillis = timestampEpochMillis,
            source = source,
            eventName = eventName,
            message = message,
            cause = cause,
        )
    }
}
