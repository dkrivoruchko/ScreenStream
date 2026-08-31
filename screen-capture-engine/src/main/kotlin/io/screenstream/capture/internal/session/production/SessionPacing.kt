package io.screenstream.capture.internal.session.production

import io.screenstream.capture.FrameRate
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import kotlin.time.Duration

internal sealed interface PacingDecision {
    class Eligible(internal val nextPhase: Int?, internal val nextRequiredGapNanos: Long) : PacingDecision
    class Deferred(internal val eligibleAtNanos: Long) : PacingDecision
    class RetainOpportunity(internal val eligibleAtNanos: Long) : PacingDecision
    data object InvalidEvidence : PacingDecision
}

internal class CadenceHistory(internal val lastGrantNanos: Long, internal val phase: Int, internal val requiredGapNanos: Long)

internal object PacingCalculator {
    private val unpacedEligibleDecision = PacingDecision.Eligible(null, 0L)

    internal fun freshCapture(frameRate: FrameRate, nowNanos: Long, lastFreshGrantNanos: Long?, history: CadenceHistory?): PacingDecision = when (frameRate) {
        FrameRate.Auto -> if (nowNanos >= 0L) unpacedEligibleDecision else PacingDecision.InvalidEvidence
        is FrameRate.MaxFps -> maxFps(
            fps = frameRate.fps,
            nowNanos = nowNanos,
            history = history,
            onEligible = { phase, gap -> PacingDecision.Eligible(phase, gap) },
        ) { PacingDecision.RetainOpportunity(it) }

        is FrameRate.SamplingInterval -> samplingIntervalFreshCapture(frameRate.interval, nowNanos, lastFreshGrantNanos)
    }

    internal fun freshOutput(frameRate: FrameRate, nowNanos: Long, history: CadenceHistory?): PacingDecision = when (frameRate) {
        is FrameRate.MaxFps -> maxFps(
            fps = frameRate.fps,
            nowNanos = nowNanos,
            history = history,
            onEligible = { phase, gap -> PacingDecision.Eligible(phase, gap) },
        ) { PacingDecision.Deferred(it) }

        FrameRate.Auto, is FrameRate.SamplingInterval ->
            if (nowNanos >= 0L) unpacedEligibleDecision else PacingDecision.InvalidEvidence
    }

    internal fun repeatOutput(
        frameRate: FrameRate,
        repeatInterval: Duration,
        nowNanos: Long,
        lastOutputGrantNanos: Long,
        outputHistory: CadenceHistory?,
    ): PacingDecision {
        if ((nowNanos < 0L) || (lastOutputGrantNanos !in (0L..nowNanos))) {
            return PacingDecision.InvalidEvidence
        }
        val repeatAt = addOrNull(left = lastOutputGrantNanos, right = repeatInterval.inWholeNanoseconds) ?: return PacingDecision.InvalidEvidence
        return when (frameRate) {
            is FrameRate.MaxFps -> maxFps(
                fps = frameRate.fps,
                nowNanos = nowNanos,
                history = outputHistory,
                onEligible = { phase, gap ->
                    if (nowNanos >= repeatAt) PacingDecision.Eligible(phase, gap)
                    else PacingDecision.Deferred(repeatAt)
                },
            ) { PacingDecision.Deferred(maxOf(repeatAt, it)) }

            FrameRate.Auto, is FrameRate.SamplingInterval ->
                if (nowNanos >= repeatAt) unpacedEligibleDecision else PacingDecision.Deferred(repeatAt)
        }
    }

    private fun samplingIntervalFreshCapture(interval: Duration, nowNanos: Long, lastFreshGrantNanos: Long?): PacingDecision {
        if (nowNanos < 0L) return PacingDecision.InvalidEvidence
        val intervalNanos = interval.inWholeNanoseconds
        if (lastFreshGrantNanos == null) return PacingDecision.Eligible(null, intervalNanos)
        if (lastFreshGrantNanos !in (0L..nowNanos)) return PacingDecision.InvalidEvidence
        val eligibleAt = addOrNull(lastFreshGrantNanos, intervalNanos) ?: return PacingDecision.InvalidEvidence
        return if (nowNanos >= eligibleAt) PacingDecision.Eligible(null, intervalNanos)
        else PacingDecision.RetainOpportunity(eligibleAt)
    }

    private inline fun maxFps(
        fps: Int,
        nowNanos: Long,
        history: CadenceHistory?,
        onEligible: (Int, Long) -> PacingDecision,
        onDeferred: (Long) -> PacingDecision,
    ): PacingDecision {
        if ((fps !in FrameRate.MAX_FPS_RANGE) || (nowNanos < 0L)) return PacingDecision.InvalidEvidence
        val quotient = ElapsedRealtimeClock.NANOS_PER_SECOND / fps.toLong()
        val remainder = (ElapsedRealtimeClock.NANOS_PER_SECOND % fps.toLong()).toInt()
        val phase = history?.phase ?: 0
        if (phase !in (0 until fps)) return PacingDecision.InvalidEvidence
        if (history != null) {
            if (history.lastGrantNanos !in (0L..nowNanos)) return PacingDecision.InvalidEvidence
            val carry = if ((remainder != 0) && (history.phase < remainder)) 1L else 0L
            if (history.requiredGapNanos != (quotient + carry)) return PacingDecision.InvalidEvidence
            val eligibleAt = addOrNull(left = history.lastGrantNanos, right = history.requiredGapNanos) ?: return PacingDecision.InvalidEvidence
            if (nowNanos < eligibleAt) return onDeferred(eligibleAt)
        }
        val sum = phase + remainder
        val carry = if (sum >= fps) 1 else 0
        return onEligible(sum - (carry * fps), quotient + carry.toLong())
    }

    private fun addOrNull(left: Long, right: Long): Long? = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        null
    }
}
