package io.screenstream.capture.internal.session.production

import io.screenstream.capture.ScreenCaptureDeliveryDropStats
import io.screenstream.capture.ScreenCaptureFrameDropStats
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal class SessionStatsAccumulator {
    private var encodedFrameCount = 0L
    private var producedFrameCount = 0L
    private var framesDroppedByStaleWork = 0L
    private var framesDroppedByFailure = 0L
    private var deliveriesDroppedByConsumerBusy = 0L
    private var deliveriesDroppedByCallbackFailure = 0L
    private var meanEncodingDurationNanos = 0.0
    private var readbackSampleCount = 0L
    private var meanReadbackDurationNanos = 0.0
    private var meanEncodedByteCount = 0.0
    private var lastEncodedByteCount = 0
    private var firstProducedFrameNanos: Long? = null
    private var lastProducedFrameNanos: Long? = null
    private var frozenAverageProducedFps: Double? = null

    internal var hasUnpublishedChanges: Boolean = false
        private set

    internal fun recordReadback(durationNanos: Long) {
        require(durationNanos >= 0L)
        if (readbackSampleCount == Long.MAX_VALUE) return
        readbackSampleCount += 1L
        val previousMean = meanReadbackDurationNanos
        meanReadbackDurationNanos = nextFiniteMean(meanReadbackDurationNanos, durationNanos.toDouble(), readbackSampleCount)
        if (meanReadbackDurationNanos != previousMean) hasUnpublishedChanges = true
    }

    internal fun recordEncodeSuccess(durationNanos: Long, encodedByteCount: Int) {
        require(durationNanos >= 0L)
        require(encodedByteCount > 0)
        if (encodedFrameCount == Long.MAX_VALUE) {
            if (lastEncodedByteCount != encodedByteCount) {
                lastEncodedByteCount = encodedByteCount
                hasUnpublishedChanges = true
            }
            return
        }
        encodedFrameCount += 1L
        meanEncodingDurationNanos = nextFiniteMean(meanEncodingDurationNanos, durationNanos.toDouble(), encodedFrameCount)
        meanEncodedByteCount = nextFiniteMean(meanEncodedByteCount, encodedByteCount.toDouble(), encodedFrameCount)
        lastEncodedByteCount = encodedByteCount
        hasUnpublishedChanges = true
    }

    internal fun recordProducedFrame(timestampNanos: Long) {
        require(timestampNanos >= 0L)
        if (producedFrameCount == Long.MAX_VALUE) {
            if (frozenAverageProducedFps == null) frozenAverageProducedFps = snapshot().averageProducedFps
            return
        }
        producedFrameCount += 1L
        if (firstProducedFrameNanos == null) firstProducedFrameNanos = timestampNanos
        lastProducedFrameNanos = timestampNanos
        hasUnpublishedChanges = true
    }

    internal fun recordStaleWork() {
        val incremented = incrementDropCount(framesDroppedByStaleWork) ?: return
        framesDroppedByStaleWork = incremented
    }

    internal fun recordProductionFailure() {
        val incremented = incrementDropCount(framesDroppedByFailure) ?: return
        framesDroppedByFailure = incremented
    }

    internal fun recordConsumerBusy() {
        val incremented = incrementDropCount(deliveriesDroppedByConsumerBusy) ?: return
        deliveriesDroppedByConsumerBusy = incremented
    }

    internal fun recordCallbackFailure() {
        val incremented = incrementDropCount(deliveriesDroppedByCallbackFailure) ?: return
        deliveriesDroppedByCallbackFailure = incremented
    }

    internal fun snapshot(): ScreenCaptureStats {
        val averageProducedFps = frozenAverageProducedFps ?: if (producedFrameCount < 2L) {
            0.0
        } else {
            val first = firstProducedFrameNanos
            val last = lastProducedFrameNanos
            val producedFrameSpanNanos = if ((first == null) || (last == null)) 0L else last - first
            if (producedFrameSpanNanos <= 0L) {
                0.0
            } else {
                val computedAverageProducedFps =
                    ((producedFrameCount - 1L).toDouble() * ElapsedRealtimeClock.NANOS_PER_SECOND.toDouble()) / producedFrameSpanNanos.toDouble()
                if (computedAverageProducedFps.isFinite()) computedAverageProducedFps else Double.MAX_VALUE
            }
        }
        val averageEncodedByteCount = if (encodedFrameCount == 0L) {
            0
        } else {
            floor(meanEncodedByteCount + 0.5).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
        }
        return ScreenCaptureStats.create(
            encodedFrameCount = encodedFrameCount,
            producedFrameCount = producedFrameCount,
            droppedFrames = ScreenCaptureFrameDropStats.create(framesDroppedByStaleWork, framesDroppedByFailure),
            droppedDeliveries = ScreenCaptureDeliveryDropStats.create(deliveriesDroppedByConsumerBusy, deliveriesDroppedByCallbackFailure),
            averageProducedFps = averageProducedFps,
            averageEncodingDuration = if (encodedFrameCount == 0L) Duration.ZERO else meanEncodingDurationNanos.nanoseconds,
            averageReadbackDuration = if (readbackSampleCount == 0L) Duration.ZERO else meanReadbackDurationNanos.nanoseconds,
            lastEncodedByteCount = lastEncodedByteCount,
            averageEncodedByteCount = averageEncodedByteCount,
        )
    }

    internal fun markPublished() {
        hasUnpublishedChanges = false
    }

    private fun nextFiniteMean(current: Double, sample: Double, count: Long): Double {
        val candidate = current + ((sample - current) / count.toDouble())
        if (candidate.isFinite() && (candidate >= 0.0)) return candidate
        return current
    }

    private fun incrementDropCount(value: Long): Long? {
        if (value == Long.MAX_VALUE) return null
        hasUnpublishedChanges = true
        return value + 1L
    }
}
