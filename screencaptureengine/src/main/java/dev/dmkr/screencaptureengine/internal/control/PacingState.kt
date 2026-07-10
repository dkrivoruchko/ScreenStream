@file:Suppress("unused") // Intentionally dormant until controller integration.

package dev.dmkr.screencaptureengine.internal.control

internal sealed interface MonotonicDeadline {
    data class Finite(
        val elapsedRealtimeNanos: Long,
    ) : MonotonicDeadline {
        init {
            require(elapsedRealtimeNanos >= 0L) { "A finite monotonic deadline must be non-negative." }
        }
    }

    data object InfiniteFuture : MonotonicDeadline
}

internal sealed interface MaxFpsPacingFact {
    val effectiveNowNanos: Long
    val clockMovedBackward: Boolean

    data class Granted(
        override val effectiveNowNanos: Long,
        override val clockMovedBackward: Boolean,
    ) : MaxFpsPacingFact

    data class RateLimited(
        override val effectiveNowNanos: Long,
        override val clockMovedBackward: Boolean,
    ) : MaxFpsPacingFact
}

/** Controller-confined integer quotient/remainder pacing for one `MaxFps` policy epoch. */
internal class MaxFpsPacingState(
    fps: Int,
    commitSampleNanos: Long,
) {
    private val denominator = fps.toLong()
    private val quotientNanos: Long
    private val remainder: Long
    private val clock = MonotonicClockNormalizer(commitSampleNanos)

    internal var phase: Long = 0L
        private set

    internal var nextEligible: MonotonicDeadline = MonotonicDeadline.Finite(commitSampleNanos)
        private set

    internal val lastClockSampleNanos: Long
        get() = clock.lastSampleNanos

    init {
        require(fps > 0) { "FPS must be positive." }
        quotientNanos = NANOS_PER_SECOND / denominator
        remainder = NANOS_PER_SECOND % denominator
    }

    internal fun select(rawNowNanos: Long): MaxFpsPacingFact {
        val sample = clock.normalize(rawNowNanos)
        val isEligible = when (val deadline = nextEligible) {
            is MonotonicDeadline.Finite -> sample.effectiveNowNanos >= deadline.elapsedRealtimeNanos
            MonotonicDeadline.InfiniteFuture -> false
        }
        if (!isEligible) {
            return MaxFpsPacingFact.RateLimited(
                effectiveNowNanos = sample.effectiveNowNanos,
                clockMovedBackward = sample.movedBackward,
            )
        }

        val phaseSum = Math.addExact(phase, remainder)
        val carry = if (phaseSum >= denominator) 1L else 0L
        phase = phaseSum - carry * denominator
        nextEligible = deadlineAfter(
            anchorNanos = sample.effectiveNowNanos,
            deltaNanos = Math.addExact(quotientNanos, carry),
        )
        return MaxFpsPacingFact.Granted(
            effectiveNowNanos = sample.effectiveNowNanos,
            clockMovedBackward = sample.movedBackward,
        )
    }
}

internal sealed interface PeriodicRefreshInterval {
    data class Finite(
        val nanos: Long,
    ) : PeriodicRefreshInterval {
        init {
            require(nanos > 0L) { "A finite refresh interval must be positive." }
        }
    }

    data object InfiniteFuture : PeriodicRefreshInterval
}

internal sealed interface PeriodicRefreshClockFact {
    val effectiveNowNanos: Long
    val clockMovedBackward: Boolean

    data class NotDue(
        override val effectiveNowNanos: Long,
        override val clockMovedBackward: Boolean,
    ) : PeriodicRefreshClockFact

    data class BecamePending(
        override val effectiveNowNanos: Long,
        override val clockMovedBackward: Boolean,
    ) : PeriodicRefreshClockFact

    data class CoalescedWhilePending(
        override val effectiveNowNanos: Long,
        override val clockMovedBackward: Boolean,
    ) : PeriodicRefreshClockFact
}

/** Controller-confined deadline and one-bit state for one `PeriodicRefresh` policy epoch. */
internal class PeriodicRefreshPacingState(
    intervalMillis: Long,
    commitSampleNanos: Long,
    hasCurrentSource: Boolean,
) {
    internal val interval: PeriodicRefreshInterval = intervalFromMillis(intervalMillis)
    private val clock = MonotonicClockNormalizer(commitSampleNanos)

    internal var deadline: MonotonicDeadline? = if (hasCurrentSource) {
        scheduleFrom(commitSampleNanos)
    } else {
        null
    }
        private set

    internal var hasCurrentSource: Boolean = hasCurrentSource
        private set

    internal var refreshPending: Boolean = false
        private set

    internal val lastClockSampleNanos: Long
        get() = clock.lastSampleNanos

    internal fun onFirstCurrentSourceAcquired(rawNowNanos: Long): PeriodicRefreshClockFact {
        val sample = clock.normalize(rawNowNanos)
        hasCurrentSource = true
        refreshPending = false
        deadline = scheduleFrom(sample.effectiveNowNanos)
        return PeriodicRefreshClockFact.NotDue(
            effectiveNowNanos = sample.effectiveNowNanos,
            clockMovedBackward = sample.movedBackward,
        )
    }

    internal fun observeClock(rawNowNanos: Long): PeriodicRefreshClockFact {
        val sample = clock.normalize(rawNowNanos)
        val currentDeadline = deadline
        if (currentDeadline !is MonotonicDeadline.Finite ||
            sample.effectiveNowNanos < currentDeadline.elapsedRealtimeNanos
        ) {
            return PeriodicRefreshClockFact.NotDue(
                effectiveNowNanos = sample.effectiveNowNanos,
                clockMovedBackward = sample.movedBackward,
            )
        }

        deadline = scheduleFrom(sample.effectiveNowNanos)
        val fact = if (refreshPending) {
            PeriodicRefreshClockFact.CoalescedWhilePending(
                effectiveNowNanos = sample.effectiveNowNanos,
                clockMovedBackward = sample.movedBackward,
            )
        } else {
            refreshPending = true
            PeriodicRefreshClockFact.BecamePending(
                effectiveNowNanos = sample.effectiveNowNanos,
                clockMovedBackward = sample.movedBackward,
            )
        }
        return fact
    }

    internal fun takePendingRefresh(): Boolean {
        if (!refreshPending) return false
        refreshPending = false
        return true
    }

    internal fun clearForFreshSourceFence() {
        hasCurrentSource = false
        refreshPending = false
        deadline = null
    }

    private fun scheduleFrom(anchorNanos: Long): MonotonicDeadline = when (val value = interval) {
        is PeriodicRefreshInterval.Finite -> deadlineAfter(anchorNanos, value.nanos)
        PeriodicRefreshInterval.InfiniteFuture -> MonotonicDeadline.InfiniteFuture
    }
}

private class MonotonicClockNormalizer(initialSampleNanos: Long) {
    var lastSampleNanos: Long = initialSampleNanos
        private set

    init {
        require(initialSampleNanos >= 0L) { "The initial monotonic clock sample must be non-negative." }
    }

    fun normalize(rawNowNanos: Long): NormalizedClockSample {
        require(rawNowNanos >= 0L) { "The monotonic clock sample must be non-negative." }
        val movedBackward = rawNowNanos < lastSampleNanos
        val effectiveNowNanos = if (movedBackward) lastSampleNanos else rawNowNanos
        lastSampleNanos = effectiveNowNanos
        return NormalizedClockSample(effectiveNowNanos, movedBackward)
    }
}

private data class NormalizedClockSample(
    val effectiveNowNanos: Long,
    val movedBackward: Boolean,
)

private fun intervalFromMillis(intervalMillis: Long): PeriodicRefreshInterval {
    require(intervalMillis > 0L) { "Periodic refresh interval must be positive." }
    return if (intervalMillis <= Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
        PeriodicRefreshInterval.Finite(nanos = intervalMillis * NANOS_PER_MILLISECOND)
    } else {
        PeriodicRefreshInterval.InfiniteFuture
    }
}

internal fun deadlineAfter(anchorNanos: Long, deltaNanos: Long): MonotonicDeadline {
    require(anchorNanos >= 0L) { "The monotonic anchor must be non-negative." }
    require(deltaNanos >= 0L) { "The monotonic delta must be non-negative." }
    return if (anchorNanos > Long.MAX_VALUE - deltaNanos) {
        MonotonicDeadline.InfiniteFuture
    } else {
        MonotonicDeadline.Finite(elapsedRealtimeNanos = anchorNanos + deltaNanos)
    }
}

private const val NANOS_PER_SECOND: Long = 1_000_000_000L
private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
