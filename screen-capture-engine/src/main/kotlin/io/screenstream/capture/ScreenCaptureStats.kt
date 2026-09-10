package io.screenstream.capture

import kotlin.time.Duration

/**
 * An immutable, cumulative statistics snapshot for one screen capture session.
 *
 * All fields start at zero. Counters and derived totals saturate instead of wrapping, and all averages remain
 * finite and nonnegative. Values use structural equality.
 *
 * A created session installs one all-zero snapshot; accepting start does not by itself assign another. Ordinary
 * snapshot publication is considered when activity is processed while [ScreenCaptureState.Active]. Results processed
 * before final statistics freeze can still contribute to counters and averages outside Active. A changed snapshot
 * becomes eligible when its elapsed-realtime sample is at least 1,000 milliseconds after the previous ordinary
 * snapshot sample, initially the session-creation sample. Assignment and collection may occur later or close together.
 * There is no sampling timer or catch-up, and changes remain pending while [ScreenCaptureState.Suspended].
 * Final statistics freeze and are assigned before the terminal state; results processed later cannot change them.
 * [ScreenCaptureSession.stats] and [ScreenCaptureSession.state] are separate conflated flows with no atomic snapshot,
 * observed ordering, or collector progress guarantee.
 *
 * @property encodedFrameCount successful JPEG encodes processed before final statistics freeze, including successful
 *     results later suppressed as stale. Cached-first output does not count.
 * @property producedFrameCount fresh output commits, whether or not a consumer exists. Cached-first
 *     delivery does not count.
 * @property frameProductionDrops returned production results dropped as stale or failed before final freeze.
 * @property droppedDeliveries consumer-busy opportunities and callback failures processed before final freeze.
 * @property averageProducedFps `(producedFrameCount - 1) / elapsedSeconds` across the first-to-latest output-commit
 *     interval, including suspension and deep sleep. It is finite and nonnegative, is zero with fewer than two commits
 *     or no positive interval, and does not decay merely because no later output commits.
 * @property averageEncodingDuration the average duration of successful fresh encodes processed before final freeze,
 *     including stale results; zero when there is no eligible sample. Cached-first output adds no sample.
 * @property averageReadbackDuration the average duration of successful readbacks processed before final freeze,
 *     including stale results; zero when there is no eligible sample. Cached-first output adds no sample.
 * @property lastEncodedByteCount the latest successful encoded byte count, including successful stale work, or zero
 *     before the first successful encode.
 * @property averageEncodedByteCount the rounded nonnegative mean of successful encoded byte counts,
 *     capped at [Int.MAX_VALUE], or zero before the first successful encode.
 */
public class ScreenCaptureStats private constructor(
    public val encodedFrameCount: Long,
    public val producedFrameCount: Long,
    public val frameProductionDrops: ScreenCaptureFrameProductionDropStats,
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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureStats) return false

        return (encodedFrameCount == other.encodedFrameCount) &&
                (producedFrameCount == other.producedFrameCount) &&
                (frameProductionDrops == other.frameProductionDrops) &&
                (droppedDeliveries == other.droppedDeliveries) &&
                (averageProducedFps.compareTo(other.averageProducedFps) == 0) &&
                (averageEncodingDuration == other.averageEncodingDuration) &&
                (averageReadbackDuration == other.averageReadbackDuration) &&
                (lastEncodedByteCount == other.lastEncodedByteCount) &&
                (averageEncodedByteCount == other.averageEncodedByteCount)
    }

    public override fun hashCode(): Int {
        var result: Int = encodedFrameCount.hashCode()
        result = (31 * result) + producedFrameCount.hashCode()
        result = (31 * result) + frameProductionDrops.hashCode()
        result = (31 * result) + droppedDeliveries.hashCode()
        result = (31 * result) + averageProducedFps.hashCode()
        result = (31 * result) + averageEncodingDuration.hashCode()
        result = (31 * result) + averageReadbackDuration.hashCode()
        result = (31 * result) + lastEncodedByteCount.hashCode()
        result = (31 * result) + averageEncodedByteCount.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureStats(" +
                "encodedFrameCount=$encodedFrameCount, " +
                "producedFrameCount=$producedFrameCount, " +
                "frameProductionDrops=$frameProductionDrops, " +
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
            frameProductionDrops = ScreenCaptureFrameProductionDropStats.create(byStaleWork = 0L, byFailure = 0L),
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
            frameProductionDrops: ScreenCaptureFrameProductionDropStats,
            droppedDeliveries: ScreenCaptureDeliveryDropStats,
            averageProducedFps: Double,
            averageEncodingDuration: Duration,
            averageReadbackDuration: Duration,
            lastEncodedByteCount: Int,
            averageEncodedByteCount: Int,
        ): ScreenCaptureStats = ScreenCaptureStats(
            encodedFrameCount = encodedFrameCount,
            producedFrameCount = producedFrameCount,
            frameProductionDrops = frameProductionDrops,
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
 * Immutable structural cumulative frame-production drop counts.
 *
 * Each component and [total] saturates at [Long.MAX_VALUE]. These fields count production results processed before
 * final statistics freeze. Producing output when no consumer is registered is not a frame-production drop.
 *
 * @property byStaleWork otherwise-successful work suppressed solely because its identity was stale.
 * @property byFailure a returned production failure processed before final freeze, even if its identity was stale.
 */
public class ScreenCaptureFrameProductionDropStats private constructor(
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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureFrameProductionDropStats) return false

        return (byStaleWork == other.byStaleWork) && (byFailure == other.byFailure)
    }

    public override fun hashCode(): Int {
        var result: Int = byStaleWork.hashCode()
        result = (31 * result) + byFailure.hashCode()
        return result
    }

    public override fun toString(): String =
        "ScreenCaptureFrameProductionDropStats(byStaleWork=$byStaleWork, byFailure=$byFailure)"

    internal companion object {
        @JvmSynthetic
        internal fun create(byStaleWork: Long, byFailure: Long): ScreenCaptureFrameProductionDropStats =
            ScreenCaptureFrameProductionDropStats(byStaleWork, byFailure)
    }
}

/**
 * Immutable structural cumulative delivery drop counts.
 *
 * Each component and [total] saturates at [Long.MAX_VALUE]. Producing without a consumer is not a delivery drop,
 * and a delivery scheduling failure is a session failure rather than a drop.
 *
 * @property byConsumerBusy a delivery opportunity that occurred while the prior handoff was still occupied.
 * @property byCallbackFailure an entered consumer callback that threw an [Exception] and whose exact failure was
 *     accepted for accounting before terminal freeze.
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

    public override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScreenCaptureDeliveryDropStats) return false

        return (byConsumerBusy == other.byConsumerBusy) && (byCallbackFailure == other.byCallbackFailure)
    }

    public override fun hashCode(): Int {
        var result: Int = byConsumerBusy.hashCode()
        result = (31 * result) + byCallbackFailure.hashCode()
        return result
    }

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
