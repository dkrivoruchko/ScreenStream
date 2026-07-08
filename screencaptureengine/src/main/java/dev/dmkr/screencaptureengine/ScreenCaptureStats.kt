package dev.dmkr.screencaptureengine

/**
 * Session counters, rates, byte sizes, and delivery pressure.
 *
 * Production drops and delivery drops are separate: one encoded frame can be published successfully even if one or more subscription delivery attempts fail.
 */
public class ScreenCaptureStats public constructor(
    /** Successful encoder outputs before stale-generation and encoded-size publication checks. */
    public val framesEncoded: Long = 0L,

    /** Successful replacements of the engine's internal latest encoded frame. */
    public val framesPublished: Long = 0L,

    /** Production opportunities/results that did not become the internal latest frame. */
    public val droppedFrames: ScreenCaptureFrameDropStats = ScreenCaptureFrameDropStats(),

    /** Per-subscription delivery attempts that did not complete; never increments [droppedFrames]. */
    public val droppedDeliveries: ScreenCaptureDeliveryDropStats = ScreenCaptureDeliveryDropStats(),

    /** Published frames per second over session lifetime using monotonic elapsed time. */
    public val publishedFps: Double = 0.0,

    /** Average synchronous encoder duration in milliseconds. */
    public val averageEncodeMs: Double = 0.0,

    /** Average readback duration in milliseconds. */
    public val averageReadbackMs: Double = 0.0,

    /** Encoded byte count of the last successfully encoded frame. */
    public val lastEncodedByteCount: Int = 0,

    /** Average encoded byte count for successfully encoded frames. */
    public val averageEncodedByteCount: Int = 0,

    /** Registered frame subscriptions. */
    public val activeFrameSubscriptions: Int = 0,

    /** Active subscriptions classified as slow or failing by consecutive direct delivery problems. */
    public val slowConsumers: Int = 0,
) {
    init {
        require(framesEncoded >= 0L) { "framesEncoded must be non-negative, was $framesEncoded" }
        require(framesPublished >= 0L) { "framesPublished must be non-negative, was $framesPublished" }
        require(publishedFps.isFinite() && publishedFps >= 0.0) { "publishedFps must be finite and non-negative, was $publishedFps" }
        require(averageEncodeMs.isFinite() && averageEncodeMs >= 0.0) { "averageEncodeMs must be finite and non-negative, was $averageEncodeMs" }
        require(averageReadbackMs.isFinite() && averageReadbackMs >= 0.0) { "averageReadbackMs must be finite and non-negative, was $averageReadbackMs" }
        require(lastEncodedByteCount >= 0) { "lastEncodedByteCount must be non-negative, was $lastEncodedByteCount" }
        require(averageEncodedByteCount >= 0) { "averageEncodedByteCount must be non-negative, was $averageEncodedByteCount" }
        require(activeFrameSubscriptions >= 0) { "activeFrameSubscriptions must be non-negative, was $activeFrameSubscriptions" }
        require(slowConsumers >= 0) { "slowConsumers must be non-negative, was $slowConsumers" }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureStats && framesEncoded == other.framesEncoded && framesPublished == other.framesPublished &&
                droppedFrames == other.droppedFrames && droppedDeliveries == other.droppedDeliveries && publishedFps == other.publishedFps &&
                averageEncodeMs == other.averageEncodeMs && averageReadbackMs == other.averageReadbackMs &&
                lastEncodedByteCount == other.lastEncodedByteCount && averageEncodedByteCount == other.averageEncodedByteCount &&
                activeFrameSubscriptions == other.activeFrameSubscriptions && slowConsumers == other.slowConsumers

    public override fun hashCode(): Int {
        var result = framesEncoded.hashCode()
        result = 31 * result + framesPublished.hashCode()
        result = 31 * result + droppedFrames.hashCode()
        result = 31 * result + droppedDeliveries.hashCode()
        result = 31 * result + publishedFps.hashCode()
        result = 31 * result + averageEncodeMs.hashCode()
        result = 31 * result + averageReadbackMs.hashCode()
        result = 31 * result + lastEncodedByteCount
        result = 31 * result + averageEncodedByteCount
        result = 31 * result + activeFrameSubscriptions
        result = 31 * result + slowConsumers
        return result
    }
}

/**
 * Production-frame drop counters.
 *
 * [total] must equal the sum of the listed categories. Each dropped production opportunity or completed encoded result is counted in exactly one category.
 */
public class ScreenCaptureFrameDropStats public constructor(
    /** Sum of all production drop categories. */
    public val total: Long = 0L,

    /** Frame skipped by max-FPS or periodic-refresh pacing. */
    public val byFrameRatePolicy: Long = 0L,

    /** Frame skipped because readback resources were busy. */
    public val byReadbackBusy: Long = 0L,

    /** Frame skipped because encoder resources were busy. */
    public val byEncoderBusy: Long = 0L,

    /** Frame skipped because current output is suspended. */
    public val byOutputSuspended: Long = 0L,

    /** Completed or signaled work discarded because it belonged to an old generation. */
    public val byStaleGeneration: Long = 0L,

    /** Encoded result discarded because it exceeded the encoded-size cap. */
    public val byEncodedSizeLimit: Long = 0L,

    /** Single recoverable readback or encode failure. */
    public val byTransientFailure: Long = 0L,
) {
    init {
        require(total >= 0L) { "total must be non-negative, was $total" }
        require(byFrameRatePolicy >= 0L) { "byFrameRatePolicy must be non-negative, was $byFrameRatePolicy" }
        require(byReadbackBusy >= 0L) { "byReadbackBusy must be non-negative, was $byReadbackBusy" }
        require(byEncoderBusy >= 0L) { "byEncoderBusy must be non-negative, was $byEncoderBusy" }
        require(byOutputSuspended >= 0L) { "byOutputSuspended must be non-negative, was $byOutputSuspended" }
        require(byStaleGeneration >= 0L) { "byStaleGeneration must be non-negative, was $byStaleGeneration" }
        require(byEncodedSizeLimit >= 0L) { "byEncodedSizeLimit must be non-negative, was $byEncodedSizeLimit" }
        require(byTransientFailure >= 0L) { "byTransientFailure must be non-negative, was $byTransientFailure" }
        val categoryTotal = try {
            Math.addExact(
                Math.addExact(Math.addExact(byFrameRatePolicy, byReadbackBusy), Math.addExact(byEncoderBusy, byOutputSuspended)),
                Math.addExact(Math.addExact(byStaleGeneration, byEncodedSizeLimit), byTransientFailure),
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("sum of frame drop categories must not overflow Long", exception)
        }
        require(total == categoryTotal) {
            "total must equal the sum of frame drop categories"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureFrameDropStats && total == other.total && byFrameRatePolicy == other.byFrameRatePolicy &&
                byReadbackBusy == other.byReadbackBusy && byEncoderBusy == other.byEncoderBusy && byOutputSuspended == other.byOutputSuspended &&
                byStaleGeneration == other.byStaleGeneration && byEncodedSizeLimit == other.byEncodedSizeLimit &&
                byTransientFailure == other.byTransientFailure

    public override fun hashCode(): Int {
        var result = total.hashCode()
        result = 31 * result + byFrameRatePolicy.hashCode()
        result = 31 * result + byReadbackBusy.hashCode()
        result = 31 * result + byEncoderBusy.hashCode()
        result = 31 * result + byOutputSuspended.hashCode()
        result = 31 * result + byStaleGeneration.hashCode()
        result = 31 * result + byEncodedSizeLimit.hashCode()
        result = 31 * result + byTransientFailure.hashCode()
        return result
    }
}

/**
 * Per-subscription delivery drop counters.
 *
 * [total] must equal the sum of the listed categories. Delivery drops are failed delivery attempts and do not mean production failed.
 */
public class ScreenCaptureDeliveryDropStats public constructor(
    /** Sum of all delivery drop categories. */
    public val total: Long = 0L,

    /** Subscription already had a scheduled, handed-off, or admitted callback for another publication. */
    public val bySubscriptionBusy: Long = 0L,

    /** Dispatch failed or saturated before callback admission. */
    public val byDispatchFailed: Long = 0L,

    /** User callback threw after callback admission. */
    public val byCallbackThrew: Long = 0L,

    /** No immutable public delivery snapshot slot was available for this delivery. */
    public val bySnapshotSlotsExhausted: Long = 0L,

    /** Materialized delivery record retired before callback admission due to stale or terminal session, generation, or subscription. */
    public val byStaleSession: Long = 0L,
) {
    init {
        require(total >= 0L) { "total must be non-negative, was $total" }
        require(bySubscriptionBusy >= 0L) { "bySubscriptionBusy must be non-negative, was $bySubscriptionBusy" }
        require(byDispatchFailed >= 0L) { "byDispatchFailed must be non-negative, was $byDispatchFailed" }
        require(byCallbackThrew >= 0L) { "byCallbackThrew must be non-negative, was $byCallbackThrew" }
        require(bySnapshotSlotsExhausted >= 0L) { "bySnapshotSlotsExhausted must be non-negative, was $bySnapshotSlotsExhausted" }
        require(byStaleSession >= 0L) { "byStaleSession must be non-negative, was $byStaleSession" }
        val categoryTotal = try {
            Math.addExact(
                Math.addExact(bySubscriptionBusy, byDispatchFailed),
                Math.addExact(Math.addExact(bySnapshotSlotsExhausted, byCallbackThrew), byStaleSession),
            )
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("sum of delivery drop categories must not overflow Long", exception)
        }
        require(total == categoryTotal) {
            "total must equal the sum of delivery drop categories"
        }
    }

    public override fun equals(other: Any?): Boolean =
        other is ScreenCaptureDeliveryDropStats && total == other.total && bySubscriptionBusy == other.bySubscriptionBusy &&
                byDispatchFailed == other.byDispatchFailed && byCallbackThrew == other.byCallbackThrew &&
                bySnapshotSlotsExhausted == other.bySnapshotSlotsExhausted && byStaleSession == other.byStaleSession

    public override fun hashCode(): Int {
        var result = total.hashCode()
        result = 31 * result + bySubscriptionBusy.hashCode()
        result = 31 * result + byDispatchFailed.hashCode()
        result = 31 * result + byCallbackThrew.hashCode()
        result = 31 * result + bySnapshotSlotsExhausted.hashCode()
        result = 31 * result + byStaleSession.hashCode()
        return result
    }
}
