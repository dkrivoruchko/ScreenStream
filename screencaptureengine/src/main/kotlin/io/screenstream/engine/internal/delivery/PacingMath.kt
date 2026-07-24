package io.screenstream.engine.internal.delivery

/** Immutable committed state of one MaxFps gate. Fresh and output gates use distinct instances. */
internal class RationalCadenceHistory internal constructor(
    internal val lastGrantNanos: Long,
    internal val phase: Int,
    internal val requiredGapNanos: Long,
)

internal sealed interface CadenceCalculation {
    class Eligible internal constructor(
        internal val nextPhase: Int?,
        internal val nextRequiredGapNanos: Long,
    ) : CadenceCalculation

    class Deferred internal constructor(
        internal val eligibleAtNanos: Long,
    ) : CadenceCalculation

    data object Invalid : CadenceCalculation
}

internal enum class OutputOpportunitySelection {
    Fresh,
    Repeat,
    None,
}

/** Stateless checked pacing arithmetic. A caller commits phase/history only on an actual grant. */
internal object PacingMath {
    /** Encodes the authority-owned equality rule without selecting lifecycle, cache validity, or admission. */
    internal fun selectOutputOpportunity(
        freshEligible: Boolean,
        repeatEligible: Boolean,
    ): OutputOpportunitySelection = when {
        freshEligible -> OutputOpportunitySelection.Fresh
        repeatEligible -> OutputOpportunitySelection.Repeat
        else -> OutputOpportunitySelection.None
    }

    internal fun auto(nowNanos: Long): CadenceCalculation =
        if (nowNanos >= 0L) CadenceCalculation.Eligible(null, 0L) else CadenceCalculation.Invalid

    internal fun maxFps(
        fps: Int,
        nowNanos: Long,
        history: RationalCadenceHistory?,
    ): CadenceCalculation {
        if (fps !in 1..120 || nowNanos < 0L) return CadenceCalculation.Invalid
        val quotient = NANOS_PER_SECOND / fps.toLong()
        val remainder = (NANOS_PER_SECOND % fps.toLong()).toInt()
        val phase = history?.phase ?: 0
        if (phase !in 0 until fps) return CadenceCalculation.Invalid

        if (history != null) {
            if (history.lastGrantNanos < 0L || history.lastGrantNanos > nowNanos) {
                return CadenceCalculation.Invalid
            }
            val committedCarry = if (remainder != 0 && history.phase < remainder) 1L else 0L
            if (history.requiredGapNanos != quotient + committedCarry) {
                return CadenceCalculation.Invalid
            }
            val eligibleAt = addOrNull(history.lastGrantNanos, history.requiredGapNanos)
                ?: return CadenceCalculation.Invalid
            if (nowNanos < eligibleAt) return CadenceCalculation.Deferred(eligibleAt)
        }

        val sum = phase + remainder
        val carry = if (sum >= fps) 1 else 0
        return CadenceCalculation.Eligible(
            nextPhase = sum - carry * fps,
            nextRequiredGapNanos = quotient + carry.toLong(),
        )
    }

    internal fun sampleEvery(
        intervalMillis: Long,
        nowNanos: Long,
        lastFreshGrantNanos: Long?,
    ): CadenceCalculation {
        if (intervalMillis <= 0L || nowNanos < 0L) return CadenceCalculation.Invalid
        val intervalNanos = multiplyOrNull(intervalMillis, NANOS_PER_MILLISECOND)
            ?: return CadenceCalculation.Invalid
        if (lastFreshGrantNanos == null) return CadenceCalculation.Eligible(null, intervalNanos)
        if (lastFreshGrantNanos < 0L || lastFreshGrantNanos > nowNanos) return CadenceCalculation.Invalid
        val eligibleAt = addOrNull(lastFreshGrantNanos, intervalNanos) ?: return CadenceCalculation.Invalid
        return if (nowNanos >= eligibleAt) {
            CadenceCalculation.Eligible(null, intervalNanos)
        } else {
            CadenceCalculation.Deferred(eligibleAt)
        }
    }

    internal fun repeat(
        repeatIntervalMillis: Long,
        nowNanos: Long,
        lastOutputGrantNanos: Long,
    ): CadenceCalculation {
        if (repeatIntervalMillis <= 0L || nowNanos < 0L ||
            lastOutputGrantNanos < 0L || lastOutputGrantNanos > nowNanos
        ) {
            return CadenceCalculation.Invalid
        }
        val intervalNanos = multiplyOrNull(repeatIntervalMillis, NANOS_PER_MILLISECOND)
            ?: return CadenceCalculation.Invalid
        val eligibleAt = addOrNull(lastOutputGrantNanos, intervalNanos)
            ?: return CadenceCalculation.Invalid
        return if (nowNanos >= eligibleAt) {
            CadenceCalculation.Eligible(null, intervalNanos)
        } else {
            CadenceCalculation.Deferred(eligibleAt)
        }
    }

    private fun addOrNull(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private fun multiplyOrNull(left: Long, right: Long): Long? = try {
        Math.multiplyExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }

    private const val NANOS_PER_SECOND: Long = 1_000_000_000L
    private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
}
