package io.screenstream.engine.internal.session

import io.screenstream.engine.ScreenCaptureStats
import io.screenstream.engine.internal.observation.StatsProjection
import io.screenstream.engine.internal.observation.StatsSnapshotBuilder

/** Control-confined public accounting for the first fresh-output path. */
internal class SessionMetrics internal constructor() {
    private var framesEncoded = 0L
    private var framesProduced = 0L
    private var byPipelineBusy = 0L
    private var byStaleWork = 0L
    private var byFailure = 0L
    private var byConsumerBusy = 0L
    private var byCallbackFailure = 0L
    private var encodeSamples = 0L
    private var meanEncodeNanos = 0.0
    private var readbackSamples = 0L
    private var meanReadbackNanos = 0.0
    private var byteSamples = 0L
    private var meanEncodedBytes = 0.0
    private var lastEncodedByteCount = 0
    private var firstProductionNanos: Long? = null
    private var lastProductionNanos: Long? = null

    internal var dirty: Boolean = false
        private set

    internal fun recordReadback(durationNanos: Long) {
        require(durationNanos >= 0L)
        if (readbackSamples == Long.MAX_VALUE) return
        readbackSamples += 1L
        meanReadbackNanos = nextFiniteMean(meanReadbackNanos, durationNanos.toDouble(), readbackSamples)
        dirty = true
    }

    internal fun recordEncodeSuccess(durationNanos: Long, encodedByteCount: Int) {
        require(durationNanos >= 0L)
        require(encodedByteCount > 0)
        if (framesEncoded == Long.MAX_VALUE) return
        framesEncoded += 1L
        encodeSamples = framesEncoded
        byteSamples = framesEncoded
        meanEncodeNanos = nextFiniteMean(meanEncodeNanos, durationNanos.toDouble(), encodeSamples)
        meanEncodedBytes = nextFiniteMean(meanEncodedBytes, encodedByteCount.toDouble(), byteSamples)
        lastEncodedByteCount = encodedByteCount
        dirty = true
    }

    internal fun recordFreshOutput(timestampNanos: Long) {
        require(timestampNanos >= 0L)
        if (framesProduced == Long.MAX_VALUE) return
        framesProduced += 1L
        if (firstProductionNanos == null) firstProductionNanos = timestampNanos
        lastProductionNanos = timestampNanos
        dirty = true
    }

    internal fun recordPipelineBusy() {
        incrementIfRepresentable(byPipelineBusy) {
            byPipelineBusy = it
        }
    }

    internal fun recordStaleWork() {
        incrementIfRepresentable(byStaleWork) {
            byStaleWork = it
        }
    }

    internal fun recordProductionFailure() {
        incrementIfRepresentable(byFailure) {
            byFailure = it
        }
    }

    internal fun recordConsumerBusy() {
        incrementIfRepresentable(byConsumerBusy) {
            byConsumerBusy = it
        }
    }

    internal fun recordCallbackFailure() {
        incrementIfRepresentable(byCallbackFailure) {
            byCallbackFailure = it
        }
    }

    internal fun snapshot(): ScreenCaptureStats = StatsSnapshotBuilder.build(
        StatsProjection(
            framesEncoded = framesEncoded,
            framesProduced = framesProduced,
            byPipelineBusy = byPipelineBusy,
            byStaleWork = byStaleWork,
            byFailure = byFailure,
            byConsumerBusy = byConsumerBusy,
            byCallbackFailure = byCallbackFailure,
            encodeSampleCount = encodeSamples,
            meanEncodeNanos = meanEncodeNanos,
            readbackSampleCount = readbackSamples,
            meanReadbackNanos = meanReadbackNanos,
            byteSampleCount = byteSamples,
            meanEncodedBytes = meanEncodedBytes,
            lastEncodedByteCount = lastEncodedByteCount,
            firstProductionNanos = firstProductionNanos,
            lastProductionNanos = lastProductionNanos,
            frozenAverageProducedFps = null,
        ),
    )

    internal fun markPublished() {
        dirty = false
    }

    private fun nextFiniteMean(current: Double, sample: Double, count: Long): Double {
        val candidate = current + (sample - current) / count.toDouble()
        return if (candidate.isFinite() && candidate >= 0.0) candidate else current
    }

    private inline fun incrementIfRepresentable(value: Long, install: (Long) -> Unit) {
        if (value == Long.MAX_VALUE) return
        install(value + 1L)
        dirty = true
    }
}
