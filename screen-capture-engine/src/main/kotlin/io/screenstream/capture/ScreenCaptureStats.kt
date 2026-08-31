package io.screenstream.capture

import kotlin.time.Duration

/**
 * An immutable, cumulative statistics snapshot for one screen capture session.
 *
 * All fields start at zero. Counters and derived totals saturate instead of wrapping, and all averages remain
 * finite and nonnegative. Values use structural equality.
 *
 * A created session installs one all-zero snapshot; accepting start does not by itself assign another. Statistics
 * are not a periodic sampling service. Ordinary changed snapshots are eligible only while
 * [ScreenCaptureState.Active], at least 1,000 milliseconds after the previous ordinary assignment, and publish only
 * when other session activity gives the engine an opportunity. There is no statistics-only wake or catch-up;
 * changes remain pending during [ScreenCaptureState.Suspended]. Normal terminal publication assigns the final
 * statistics before terminal state, but [ScreenCaptureSession.stats] and [ScreenCaptureSession.state] are separate
 * conflated flows with no cross-flow atomicity, collector ordering, or collector-progress guarantee.
 *
 * @property encodedFrameCount successful fresh JPEG encodes whose exact results were consumed before terminal
 * freeze, including successful results later suppressed as stale. Repeat and cached-first output do not count.
 * @property producedFrameCount fresh and repeated output commits, whether or not a consumer exists. Cached-first
 * delivery does not count.
 * @property droppedFrames frame-production drops grouped by their exact membership.
 * @property droppedDeliveries delivery opportunities dropped for the reasons represented by the value.
 * @property averageProducedFps the finite nonnegative rate across the first-to-latest output-commit interval,
 * including repeats, suspension, and deep sleep; zero with fewer than two commits or no positive interval.
 * @property averageEncodingDuration the average duration of successful real encodes, including successful stale
 * work; zero when there is no eligible sample. Repeat and cached-first output add no sample.
 * @property averageReadbackDuration the average duration of successful real readbacks, including successful stale
 * work; zero when there is no eligible sample. Repeat and cached-first output add no sample.
 * @property lastEncodedByteCount the latest mechanically successful encoded-byte sample, including successful stale
 * work, or zero before the first successful encode.
 * @property averageEncodedByteCount the rounded nonnegative mean of mechanically successful encoded-byte samples,
 * capped at [Int.MAX_VALUE], or zero before the first successful encode.
 */
public class ScreenCaptureStats private constructor(
    public val encodedFrameCount: Long,
    public val producedFrameCount: Long,
    public val droppedFrames: ScreenCaptureFrameDropStats,
    public val droppedDeliveries: ScreenCaptureDeliveryDropStats,
    public val averageProducedFps: Double,
    public val averageEncodingDuration: Duration,
    public val averageReadbackDuration: Duration,
    public val lastEncodedByteCount: Int,
    public val averageEncodedByteCount: Int,
) {
    init {
        require(encodedFrameCount >= 0L)
        require(producedFrameCount >= 0L)
        require(averageProducedFps.isFinite() && (averageProducedFps >= 0.0))
        require(averageEncodingDuration.isFinite() && (averageEncodingDuration >= Duration.ZERO))
        require(averageReadbackDuration.isFinite() && (averageReadbackDuration >= Duration.ZERO))
        require(lastEncodedByteCount >= 0)
        require(averageEncodedByteCount >= 0)
        require((encodedFrameCount == 0L) == (lastEncodedByteCount == 0))
        require((encodedFrameCount == 0L) == (averageEncodedByteCount == 0))
    }

    /** Returns whether [other] is a statistics value with structurally equal public fields. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureStats) return false

        return (encodedFrameCount == other.encodedFrameCount) &&
                (producedFrameCount == other.producedFrameCount) &&
                (droppedFrames == other.droppedFrames) &&
                (droppedDeliveries == other.droppedDeliveries) &&
                (averageProducedFps.compareTo(other.averageProducedFps) == 0) &&
                (averageEncodingDuration == other.averageEncodingDuration) &&
                (averageReadbackDuration == other.averageReadbackDuration) &&
                (lastEncodedByteCount == other.lastEncodedByteCount) &&
                (averageEncodedByteCount == other.averageEncodedByteCount)
    }

    /** Returns a hash code consistent with structural equality. */
    public override fun hashCode(): Int {
        var result: Int = encodedFrameCount.hashCode()
        result = (31 * result) + producedFrameCount.hashCode()
        result = (31 * result) + droppedFrames.hashCode()
        result = (31 * result) + droppedDeliveries.hashCode()
        result = (31 * result) + averageProducedFps.hashCode()
        result = (31 * result) + averageEncodingDuration.hashCode()
        result = (31 * result) + averageReadbackDuration.hashCode()
        result = (31 * result) + lastEncodedByteCount.hashCode()
        result = (31 * result) + averageEncodedByteCount.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
    public override fun toString(): String =
        "ScreenCaptureStats(" +
                "encodedFrameCount=$encodedFrameCount, " +
                "producedFrameCount=$producedFrameCount, " +
                "droppedFrames=$droppedFrames, " +
                "droppedDeliveries=$droppedDeliveries, " +
                "averageProducedFps=$averageProducedFps, " +
                "averageEncodingDuration=$averageEncodingDuration, " +
                "averageReadbackDuration=$averageReadbackDuration, " +
                "lastEncodedByteCount=$lastEncodedByteCount, " +
                "averageEncodedByteCount=$averageEncodedByteCount)"

    internal companion object {
        @get:JvmSynthetic
        internal val EMPTY: ScreenCaptureStats = ScreenCaptureStats(
            encodedFrameCount = 0L,
            producedFrameCount = 0L,
            droppedFrames = ScreenCaptureFrameDropStats.create(byStaleWork = 0L, byFailure = 0L),
            droppedDeliveries = ScreenCaptureDeliveryDropStats.create(byConsumerBusy = 0L, byCallbackFailure = 0L),
            averageProducedFps = 0.0,
            averageEncodingDuration = Duration.ZERO,
            averageReadbackDuration = Duration.ZERO,
            lastEncodedByteCount = 0,
            averageEncodedByteCount = 0,
        )

        @JvmSynthetic
        internal fun create(
            encodedFrameCount: Long,
            producedFrameCount: Long,
            droppedFrames: ScreenCaptureFrameDropStats,
            droppedDeliveries: ScreenCaptureDeliveryDropStats,
            averageProducedFps: Double,
            averageEncodingDuration: Duration,
            averageReadbackDuration: Duration,
            lastEncodedByteCount: Int,
            averageEncodedByteCount: Int,
        ): ScreenCaptureStats = ScreenCaptureStats(
            encodedFrameCount = encodedFrameCount,
            producedFrameCount = producedFrameCount,
            droppedFrames = droppedFrames,
            droppedDeliveries = droppedDeliveries,
            averageProducedFps = averageProducedFps,
            averageEncodingDuration = averageEncodingDuration,
            averageReadbackDuration = averageReadbackDuration,
            lastEncodedByteCount = lastEncodedByteCount,
            averageEncodedByteCount = averageEncodedByteCount,
        )
    }
}

/**
 * Cumulative frame-production drop counts.
 *
 * Each component and [total] saturates at [Long.MAX_VALUE]. Producing output when no consumer is registered is not
 * a frame drop. Terminal retirement of unclassified, unpublished, or transferred work adds no frame drop.
 *
 * @property byStaleWork otherwise-successful work suppressed solely because its identity was stale.
 * @property byFailure a mechanically returned production failure consumed before terminal freeze, even if its
 * identity later became stale.
 */
public class ScreenCaptureFrameDropStats private constructor(
    public val byStaleWork: Long,
    public val byFailure: Long,
) {
    init {
        require(byStaleWork >= 0L)
        require(byFailure >= 0L)
    }

    /** The saturating sum of [byStaleWork] and [byFailure]. */
    public val total: Long
        get() = saturatingNonNegativeSum(byStaleWork, byFailure)

    /** Returns whether [other] is a frame-drop value with structurally equal public fields. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureFrameDropStats) return false

        return (byStaleWork == other.byStaleWork) && (byFailure == other.byFailure)
    }

    /** Returns a hash code consistent with structural equality. */
    public override fun hashCode(): Int {
        var result: Int = byStaleWork.hashCode()
        result = (31 * result) + byFailure.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
    public override fun toString(): String =
        "ScreenCaptureFrameDropStats(byStaleWork=$byStaleWork, byFailure=$byFailure)"

    internal companion object {
        @JvmSynthetic
        internal fun create(byStaleWork: Long, byFailure: Long): ScreenCaptureFrameDropStats =
            ScreenCaptureFrameDropStats(byStaleWork, byFailure)
    }
}

/**
 * Cumulative delivery drop counts.
 *
 * Each component and [total] saturates at [Long.MAX_VALUE]. Producing without a consumer is not a delivery drop,
 * and a delivery scheduling failure is a session failure rather than a drop.
 *
 * @property byConsumerBusy a delivery opportunity that occurred while the prior handoff was still occupied.
 * @property byCallbackFailure an entered consumer callback that threw an [Exception] and whose exact failure was
 * accepted for accounting before terminal freeze.
 */
public class ScreenCaptureDeliveryDropStats private constructor(
    public val byConsumerBusy: Long,
    public val byCallbackFailure: Long,
) {
    init {
        require(byConsumerBusy >= 0L)
        require(byCallbackFailure >= 0L)
    }

    /** The saturating sum of [byConsumerBusy] and [byCallbackFailure]. */
    public val total: Long
        get() = saturatingNonNegativeSum(byConsumerBusy, byCallbackFailure)

    /** Returns whether [other] is a delivery-drop value with structurally equal public fields. */
    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureDeliveryDropStats) return false

        return (byConsumerBusy == other.byConsumerBusy) && (byCallbackFailure == other.byCallbackFailure)
    }

    /** Returns a hash code consistent with structural equality. */
    public override fun hashCode(): Int {
        var result: Int = byConsumerBusy.hashCode()
        result = (31 * result) + byCallbackFailure.hashCode()
        return result
    }

    /** Returns a bounded, non-sensitive debug string whose exact format is not an API contract. */
    public override fun toString(): String =
        "ScreenCaptureDeliveryDropStats(byConsumerBusy=$byConsumerBusy, byCallbackFailure=$byCallbackFailure)"

    internal companion object {
        @JvmSynthetic
        internal fun create(byConsumerBusy: Long, byCallbackFailure: Long): ScreenCaptureDeliveryDropStats =
            ScreenCaptureDeliveryDropStats(byConsumerBusy, byCallbackFailure)
    }
}

private fun saturatingNonNegativeSum(left: Long, right: Long): Long =
    if ((Long.MAX_VALUE - left) < right) Long.MAX_VALUE else left + right
