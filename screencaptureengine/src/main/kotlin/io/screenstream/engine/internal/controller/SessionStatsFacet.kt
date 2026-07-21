package io.screenstream.engine.internal.controller

import io.screenstream.engine.ScreenCaptureProblem
import io.screenstream.engine.internal.delivery.ObservationStatsSnapshot

internal class SessionStatsFacet internal constructor() {
    internal var framesEncoded: Long = 0L
    internal var framesProduced: Long = 0L
    internal var byPipelineBusy: Long = 0L
    internal var byStaleWork: Long = 0L
    internal var byFailure: Long = 0L
    internal var byConsumerBusy: Long = 0L
    internal var byCallbackFailure: Long = 0L
    internal var encodeSamples: Long = 0L
    internal var encodeMeanNanos: Double = 0.0
    internal var readbackSamples: Long = 0L
    internal var readbackMeanNanos: Double = 0.0
    internal var encodedByteSamples: Long = 0L
    internal var encodedByteMean: Double = 0.0
    internal var lastEncodedByteCount: Int = 0
    internal var firstProducedNanos: Long = Long.MIN_VALUE
    internal var lastProducedNanos: Long = Long.MIN_VALUE
    internal var cutoff: Boolean = false

    internal var runningPublicationPending: Boolean = false
    internal var runningPublicationProblem: ScreenCaptureProblem? = null
    internal var nextDiagnosticSequence: Long = 1L
    internal var publicStatePublicationIdentity: Long = 0L
    internal var publicStatsPublicationIdentity: Long = 0L

    internal fun snapshot(): ObservationStatsSnapshot {
        val fps = if (framesProduced < 2L || firstProducedNanos == Long.MIN_VALUE ||
            lastProducedNanos <= firstProducedNanos
        ) {
            0.0
        } else {
            val value = (framesProduced - 1L).toDouble() * NANOS_PER_SECOND.toDouble() /
                    (lastProducedNanos - firstProducedNanos).toDouble()
            if (value.isFinite()) value else Double.MAX_VALUE
        }
        val encodedAverage = if (encodedByteSamples == 0L) {
            0
        } else {
            minOf(Int.MAX_VALUE.toDouble(), kotlin.math.floor(encodedByteMean + 0.5)).toInt()
        }
        return ObservationStatsSnapshot(
            framesEncoded = framesEncoded,
            framesProduced = framesProduced,
            frameDropsByRateLimit = 0L,
            frameDropsByPipelineBusy = byPipelineBusy,
            frameDropsByStaleWork = byStaleWork,
            frameDropsByFailure = byFailure,
            deliveryDropsByConsumerBusy = byConsumerBusy,
            deliveryDropsByCallbackFailure = byCallbackFailure,
            averageProducedFps = fps,
            averageEncodeMs = finiteOrZero(encodeMeanNanos / NANOS_PER_MILLISECOND),
            averageReadbackMs = finiteOrZero(readbackMeanNanos / NANOS_PER_MILLISECOND),
            lastEncodedByteCount = lastEncodedByteCount,
            averageEncodedByteCount = encodedAverage,
        )
    }

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private companion object {
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L
        private const val NANOS_PER_MILLISECOND: Double = 1_000_000.0
    }
}
