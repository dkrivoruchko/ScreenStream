package io.screenstream.engine.internal.delivery

import io.screenstream.engine.CaptureGeometry
import io.screenstream.engine.FrameRate
import io.screenstream.engine.OutputSize
import io.screenstream.engine.ScreenCaptureParameters
import io.screenstream.engine.internal.EncodedStorageOwner
import io.screenstream.engine.internal.target.CurrentTarget

internal class PacingContext(
    internal val policyIdentity: Long,
    internal val candidateIdentity: Long,
    internal val wakeIdentity: Long?,
    internal val parameters: ScreenCaptureParameters,
    internal val sampledNowNanos: Long,
)

internal class PacingHistory(
    internal val lastGrantNanos: Long,
    internal val phase: Int?,
    internal val requiredGapNanos: Long,
)

internal sealed interface PacingInput {
    val context: PacingContext
    val history: PacingHistory?
}

internal class FreshSourceCandidate(
    internal val currentTarget: CurrentTarget,
)

internal class FreshPacingInput(
    override val context: PacingContext,
    internal val candidate: FreshSourceCandidate?,
    internal val productionSlotAvailable: Boolean,
    override val history: PacingHistory?,
) : PacingInput

internal class CompletedOutputCandidate(
    internal val unpublishedPayload: EncodedStorageOwner.UnpublishedEncodedPayload,
)

internal class PacingCacheFingerprint(
    internal val currentTarget: CurrentTarget,
    internal val captureGeometry: CaptureGeometry,
    internal val parameters: ScreenCaptureParameters,
    internal val renderGeneration: Long,
    internal val jpegGeneration: Long,
    internal val cacheValidityGeneration: Long,
)

internal class RepeatOutputCandidate(
    internal val publishedPayload: EncodedStorageOwner.PublishedEncodedPayload,
    internal val publishedFingerprint: PacingCacheFingerprint,
    internal val currentFingerprint: PacingCacheFingerprint,
)

internal class OutputPacingInput(
    override val context: PacingContext,
    internal val completedCandidate: CompletedOutputCandidate?,
    internal val repeatCandidate: RepeatOutputCandidate?,
    override val history: PacingHistory?,
) : PacingInput

internal enum class PacingGrantKind {
    FreshSource,
    CompletedOutput,
    RepeatOutput,
}

internal sealed interface PacingCalculation {
    val input: PacingInput
}

internal class PacingGrant(
    override val input: PacingInput,
    internal val kind: PacingGrantKind,
    internal val nextPhase: Int?,
    internal val nextRequiredGapNanos: Long,
) : PacingCalculation

internal class PacingDeferred(
    override val input: PacingInput,
    internal val eligibleAtNanos: Long,
) : PacingCalculation

internal class PacingNotEligible(
    override val input: PacingInput,
) : PacingCalculation

internal class PacingInvalid(
    override val input: PacingInput,
) : PacingCalculation

internal object PacingOwner {

    internal fun calculate(input: PacingInput): PacingCalculation = try {
        if (!input.context.hasValidIdentities() || input.context.sampledNowNanos < 0L) {
            PacingInvalid(input)
        } else {
            when (input) {
                is FreshPacingInput -> calculateFresh(input)
                is OutputPacingInput -> calculateOutput(input)
            }
        }
    } catch (_: ArithmeticException) {
        PacingInvalid(input)
    }

    private fun calculateFresh(input: FreshPacingInput): PacingCalculation {
        val cadence = calculateCadence(input, appliesSampleEvery = true) ?: return PacingInvalid(input)
        input.candidate ?: return PacingNotEligible(input)
        if (!input.productionSlotAvailable) return PacingNotEligible(input)

        return cadence.toCalculation(input, PacingGrantKind.FreshSource)
    }

    private fun calculateOutput(input: OutputPacingInput): PacingCalculation {
        val cadence = calculateCadence(input, appliesSampleEvery = false) ?: return PacingInvalid(input)
        if (input.completedCandidate != null) {
            return cadence.toCalculation(input, PacingGrantKind.CompletedOutput)
        }

        val repeat = input.repeatCandidate ?: return PacingNotEligible(input)
        val intervalMillis = input.context.parameters.frameRepeatIntervalMillis ?: return PacingNotEligible(input)
        if (intervalMillis <= 0L || !repeat.hasValidFingerprints() || input.context.sampledNowNanos < repeat.publishedPayload.timestampElapsedRealtimeNanos) {
            return PacingInvalid(input)
        }
        if (!repeat.publishedFingerprint.matches(repeat.currentFingerprint)) {
            return PacingNotEligible(input)
        }

        val repeatIntervalNanos = Math.multiplyExact(intervalMillis, NANOS_PER_MILLISECOND)
        val repeatEligibleAt = Math.addExact(repeat.publishedPayload.timestampElapsedRealtimeNanos, repeatIntervalNanos)
        val eligibleAt = cadence.eligibleAtNanos?.let { maxOf(it, repeatEligibleAt) } ?: repeatEligibleAt
        return if (input.context.sampledNowNanos < eligibleAt) {
            PacingDeferred(input, eligibleAt)
        } else {
            cadence.grant(input, PacingGrantKind.RepeatOutput)
        }
    }

    private fun calculateCadence(input: PacingInput, appliesSampleEvery: Boolean): CadenceCalculation? {
        val nowNanos = input.context.sampledNowNanos
        val history = input.history

        return when (val frameRate = input.context.parameters.frameRate) {
            FrameRate.Auto -> {
                if (!history.isValid(nowNanos, expectedPhase = null, expectedGapNanos = 0L)) return null
                CadenceCalculation(eligibleAtNanos = null, nextPhase = null, nextRequiredGapNanos = 0L)
            }

            is FrameRate.SampleEvery -> {
                val requiredGap = if (appliesSampleEvery) {
                    Math.multiplyExact(frameRate.intervalMillis, NANOS_PER_MILLISECOND)
                } else {
                    0L
                }
                if (!history.isValid(nowNanos, expectedPhase = null, expectedGapNanos = requiredGap)) return null
                CadenceCalculation(
                    eligibleAtNanos = history?.let { Math.addExact(it.lastGrantNanos, requiredGap) },
                    nextPhase = null,
                    nextRequiredGapNanos = requiredGap,
                )
            }

            is FrameRate.MaxFps -> calculateMaxFpsCadence(nowNanos = nowNanos, history = history, fps = frameRate.fps)
        }
    }

    private fun calculateMaxFpsCadence(nowNanos: Long, history: PacingHistory?, fps: Int): CadenceCalculation? {
        if (fps <= 0) return null

        val quotient = NANOS_PER_SECOND / fps
        val remainder = (NANOS_PER_SECOND % fps).toInt()
        val priorPhase = if (history == null) {
            0
        } else {
            val phase = history.phase ?: return null
            if (history.lastGrantNanos !in 0L..nowNanos || phase !in 0 until fps) {
                return null
            }

            val expectedGap = quotient + if (remainder != 0 && phase < remainder) 1L else 0L
            if (history.requiredGapNanos != expectedGap) return null
            phase
        }

        val sum = priorPhase + remainder
        val carry = if (sum >= fps) 1 else 0
        val nextPhase = sum - carry * fps
        val nextRequiredGap = quotient + carry
        return CadenceCalculation(
            eligibleAtNanos = history?.let { Math.addExact(it.lastGrantNanos, it.requiredGapNanos) },
            nextPhase = nextPhase,
            nextRequiredGapNanos = nextRequiredGap,
        )
    }

    private fun PacingContext.hasValidIdentities(): Boolean =
        policyIdentity > 0L && candidateIdentity > 0L && (wakeIdentity == null || wakeIdentity > 0L)

    private fun PacingHistory?.isValid(nowNanos: Long, expectedPhase: Int?, expectedGapNanos: Long): Boolean =
        this == null || lastGrantNanos in 0L..nowNanos && phase == expectedPhase && requiredGapNanos == expectedGapNanos

    private fun RepeatOutputCandidate.hasValidFingerprints(): Boolean =
        publishedPayload.timestampElapsedRealtimeNanos >= 0L && publishedFingerprint.hasValidIdentities() && currentFingerprint.hasValidIdentities()

    private fun PacingCacheFingerprint.hasValidIdentities(): Boolean =
        currentTarget.generation > 0L && captureGeometry.widthPx > 0 && captureGeometry.heightPx > 0 && captureGeometry.densityDpi > 0 &&
                renderGeneration > 0L && jpegGeneration > 0L && cacheValidityGeneration > 0L

    private fun PacingCacheFingerprint.matches(current: PacingCacheFingerprint): Boolean =
        currentTarget === current.currentTarget &&
                renderGeneration == current.renderGeneration &&
                jpegGeneration == current.jpegGeneration &&
                cacheValidityGeneration == current.cacheValidityGeneration &&
                captureGeometry.widthPx == current.captureGeometry.widthPx &&
                captureGeometry.heightPx == current.captureGeometry.heightPx &&
                captureGeometry.densityDpi == current.captureGeometry.densityDpi &&
                parameters.sourceRegion == current.parameters.sourceRegion &&
                parameters.crop.left == current.parameters.crop.left &&
                parameters.crop.top == current.parameters.crop.top &&
                parameters.crop.right == current.parameters.crop.right &&
                parameters.crop.bottom == current.parameters.crop.bottom &&
                parameters.outputSize.matches(current.parameters.outputSize) &&
                parameters.rotation == current.parameters.rotation &&
                parameters.mirror == current.parameters.mirror &&
                parameters.colorMode == current.parameters.colorMode &&
                parameters.jpegQuality == current.parameters.jpegQuality

    private fun OutputSize.matches(other: OutputSize): Boolean = when (this) {
        is OutputSize.ScaleFactor -> other is OutputSize.ScaleFactor && factor == other.factor
        is OutputSize.TargetSize -> other is OutputSize.TargetSize && width == other.width && height == other.height && contentMode == other.contentMode
    }

    private class CadenceCalculation(val eligibleAtNanos: Long?, val nextPhase: Int?, val nextRequiredGapNanos: Long) {
        fun toCalculation(input: PacingInput, kind: PacingGrantKind): PacingCalculation {
            val eligibleAt = eligibleAtNanos
            return if (eligibleAt != null && input.context.sampledNowNanos < eligibleAt) {
                PacingDeferred(input, eligibleAt)
            } else {
                grant(input, kind)
            }
        }

        fun grant(input: PacingInput, kind: PacingGrantKind): PacingGrant =
            PacingGrant(input = input, kind = kind, nextPhase = nextPhase, nextRequiredGapNanos = nextRequiredGapNanos)
    }

    private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    private const val NANOS_PER_SECOND: Long = 1_000_000_000L
}
