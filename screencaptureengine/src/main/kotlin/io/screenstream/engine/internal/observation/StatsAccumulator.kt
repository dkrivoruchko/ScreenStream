package io.screenstream.engine.internal.observation

import io.screenstream.engine.ScreenCaptureDeliveryDropStats
import io.screenstream.engine.ScreenCaptureFrameDropStats
import io.screenstream.engine.ScreenCaptureStats
import kotlin.math.floor

/** Immutable authoritative accounting projection selected by Control. */
internal class StatsProjection internal constructor(
    internal val framesEncoded: Long,
    internal val framesProduced: Long,
    internal val byPipelineBusy: Long,
    internal val byStaleWork: Long,
    internal val byFailure: Long,
    internal val byConsumerBusy: Long,
    internal val byCallbackFailure: Long,
    internal val encodeSampleCount: Long,
    internal val meanEncodeNanos: Double,
    internal val readbackSampleCount: Long,
    internal val meanReadbackNanos: Double,
    internal val byteSampleCount: Long,
    internal val meanEncodedBytes: Double,
    internal val lastEncodedByteCount: Int,
    internal val firstProductionNanos: Long?,
    internal val lastProductionNanos: Long?,
    internal val frozenAverageProducedFps: Double?,
) {
    init {
        require(framesEncoded >= 0L && framesProduced >= 0L)
        require(byPipelineBusy >= 0L && byStaleWork >= 0L && byFailure >= 0L)
        require(byConsumerBusy >= 0L && byCallbackFailure >= 0L)
        require(encodeSampleCount >= 0L && readbackSampleCount >= 0L && byteSampleCount >= 0L)
        require(encodeSampleCount == framesEncoded)
        require(readbackSampleCount >= framesEncoded)
        require(meanEncodeNanos.isFinite() && meanEncodeNanos >= 0.0)
        require(meanReadbackNanos.isFinite() && meanReadbackNanos >= 0.0)
        require(meanEncodedBytes.isFinite() && meanEncodedBytes >= 0.0)
        require(lastEncodedByteCount >= 0)
        require(encodeSampleCount != 0L || meanEncodeNanos == 0.0)
        require(readbackSampleCount != 0L || meanReadbackNanos == 0.0)
        require(byteSampleCount == framesEncoded)
        require(byteSampleCount != 0L || meanEncodedBytes == 0.0)
        require((byteSampleCount == 0L) == (lastEncodedByteCount == 0))
        require(byteSampleCount == 0L || meanEncodedBytes > 0.0)
        require((firstProductionNanos == null) == (lastProductionNanos == null))
        require((framesProduced == 0L) == (firstProductionNanos == null))
        require(firstProductionNanos == null || firstProductionNanos >= 0L)
        require(lastProductionNanos == null || lastProductionNanos >= checkNotNull(firstProductionNanos))
        require(
            frozenAverageProducedFps == null ||
                    frozenAverageProducedFps.isFinite() && frozenAverageProducedFps >= 0.0
        )
        require(frozenAverageProducedFps == null || framesProduced == Long.MAX_VALUE)
    }

    internal companion object {
        internal val Zero = StatsProjection(
            framesEncoded = 0L,
            framesProduced = 0L,
            byPipelineBusy = 0L,
            byStaleWork = 0L,
            byFailure = 0L,
            byConsumerBusy = 0L,
            byCallbackFailure = 0L,
            encodeSampleCount = 0L,
            meanEncodeNanos = 0.0,
            readbackSampleCount = 0L,
            meanReadbackNanos = 0.0,
            byteSampleCount = 0L,
            meanEncodedBytes = 0.0,
            lastEncodedByteCount = 0,
            firstProductionNanos = null,
            lastProductionNanos = null,
            frozenAverageProducedFps = null,
        )
    }
}

/** Stateless application of Product §10 formulas to one already-authoritative projection. */
internal object StatsSnapshotBuilder {
    internal fun build(input: StatsProjection): ScreenCaptureStats {
        val averageFps = input.frozenAverageProducedFps ?: calculateFps(input)
        val averageBytes = if (input.byteSampleCount == 0L) {
            0
        } else {
            floor(input.meanEncodedBytes + 0.5)
                .coerceAtMost(Int.MAX_VALUE.toDouble())
                .toInt()
        }
        return ScreenCaptureStats.create(
            framesEncoded = input.framesEncoded,
            framesProduced = input.framesProduced,
            droppedFrames = ScreenCaptureFrameDropStats.create(
                input.byPipelineBusy,
                input.byStaleWork,
                input.byFailure,
            ),
            droppedDeliveries = ScreenCaptureDeliveryDropStats.create(
                input.byConsumerBusy,
                input.byCallbackFailure,
            ),
            averageProducedFps = averageFps,
            averageEncodeMs = if (input.encodeSampleCount == 0L) 0.0 else input.meanEncodeNanos / 1_000_000.0,
            averageReadbackMs = if (input.readbackSampleCount == 0L) 0.0 else input.meanReadbackNanos / 1_000_000.0,
            lastEncodedByteCount = input.lastEncodedByteCount,
            averageEncodedByteCount = averageBytes,
        )
    }

    private fun calculateFps(input: StatsProjection): Double {
        if (input.framesProduced < 2L) return 0.0
        val first = input.firstProductionNanos ?: return 0.0
        val last = input.lastProductionNanos ?: return 0.0
        val interval = last - first
        if (interval <= 0L) return 0.0
        val calculated = (input.framesProduced - 1L).toDouble() * 1_000_000_000.0 / interval.toDouble()
        return if (calculated.isFinite()) calculated else Double.MAX_VALUE
    }
}
