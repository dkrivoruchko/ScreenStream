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

internal class FreshSourceCandidate(
    internal val currentTarget: CurrentTarget,
)

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

/**
 * One aggregate-produced, turn-coherent view of every pacing candidate and both independent cadence histories.
 * This value is calculation input only: the aggregate still owns identity/currentness checks and grant commits.
 */
internal class PacingSnapshot(
    internal val context: PacingContext,
    internal val pendingFresh: FreshSourceCandidate?,
    internal val productionSlotAvailable: Boolean,
    internal val completedUnpublished: CompletedOutputCandidate?,
    internal val repeat: RepeatOutputCandidate?,
    internal val freshHistory: PacingHistory?,
    internal val outputHistory: PacingHistory?,
)

internal enum class PacingGrantKind {
    FreshSource,
    CompletedOutput,
    RepeatOutput,
}

internal sealed interface PacingCalculation {
    val snapshot: PacingSnapshot
}

internal class PacingGrant(
    override val snapshot: PacingSnapshot,
    internal val kind: PacingGrantKind,
    internal val nextPhase: Int?,
    internal val nextRequiredGapNanos: Long,
) : PacingCalculation

internal class PacingDeferred(
    override val snapshot: PacingSnapshot,
    internal val eligibleAtNanos: Long,
    internal val kind: PacingGrantKind,
) : PacingCalculation

internal class PacingNotEligible(
    override val snapshot: PacingSnapshot,
) : PacingCalculation

internal class PacingInvalid(
    override val snapshot: PacingSnapshot,
) : PacingCalculation

internal object PacingOwner {

    /**
     * Returns one action when any candidate is grantable, otherwise the earliest future wake. Simultaneously
     * grantable candidates are deliberately ordered CompletedOutput -> FreshSource -> RepeatOutput: unpublished
     * bytes vacate the occupied production slot first, a real fresh source supersedes a synthetic repeat, and
     * completed output therefore always wins a repeat tie.
     */
    internal fun calculate(snapshot: PacingSnapshot): PacingCalculation {
        return try {
            val context = snapshot.context
            if (!context.hasValidIdentities() || context.sampledNowNanos < 0L ||
                snapshot.completedUnpublished != null && snapshot.productionSlotAvailable
            ) {
                return PacingInvalid(snapshot)
            }

            val nowNanos = context.sampledNowNanos
            val frameRate = context.parameters.frameRate

            var outputHasDeadline = false
            var outputEligibleAtNanos = 0L
            var outputHasNextPhase = false
            var outputNextPhase = 0
            var outputNextRequiredGapNanos = 0L
            if (snapshot.completedUnpublished != null || snapshot.repeat != null) {
                when (frameRate) {
                    FrameRate.Auto -> {
                        if (!snapshot.outputHistory.isValid(nowNanos, expectedPhase = null, expectedGapNanos = 0L)) {
                            return PacingInvalid(snapshot)
                        }
                    }

                    is FrameRate.SampleEvery -> {
                        if (!snapshot.outputHistory.isValid(nowNanos, expectedPhase = null, expectedGapNanos = 0L)) {
                            return PacingInvalid(snapshot)
                        }
                        val history = snapshot.outputHistory
                        if (history != null) {
                            outputHasDeadline = true
                            outputEligibleAtNanos = history.lastGrantNanos
                        }
                    }

                    is FrameRate.MaxFps -> {
                        val fps = frameRate.fps
                        if (fps <= 0) return PacingInvalid(snapshot)
                        val quotient = NANOS_PER_SECOND / fps
                        val remainder = (NANOS_PER_SECOND % fps).toInt()
                        val history = snapshot.outputHistory
                        val priorPhase = if (history == null) {
                            0
                        } else {
                            val phase = history.phase ?: return PacingInvalid(snapshot)
                            if (history.lastGrantNanos < 0L || history.lastGrantNanos > nowNanos ||
                                phase < 0 || phase >= fps
                            ) {
                                return PacingInvalid(snapshot)
                            }
                            val expectedGap = quotient + if (remainder != 0 && phase < remainder) 1L else 0L
                            if (history.requiredGapNanos != expectedGap) return PacingInvalid(snapshot)
                            outputHasDeadline = true
                            outputEligibleAtNanos = Math.addExact(history.lastGrantNanos, history.requiredGapNanos)
                            phase
                        }
                        val sum = priorPhase + remainder
                        val carry = if (sum >= fps) 1 else 0
                        outputHasNextPhase = true
                        outputNextPhase = sum - carry * fps
                        outputNextRequiredGapNanos = quotient + carry
                    }
                }
            }

            var freshHasDeadline = false
            var freshEligibleAtNanos = 0L
            var freshHasNextPhase = false
            var freshNextPhase = 0
            var freshNextRequiredGapNanos = 0L
            val hasFreshCandidate = snapshot.pendingFresh != null && snapshot.productionSlotAvailable
            if (hasFreshCandidate) {
                when (frameRate) {
                    FrameRate.Auto -> {
                        if (!snapshot.freshHistory.isValid(nowNanos, expectedPhase = null, expectedGapNanos = 0L)) {
                            return PacingInvalid(snapshot)
                        }
                    }

                    is FrameRate.SampleEvery -> {
                        val requiredGap = Math.multiplyExact(frameRate.intervalMillis, NANOS_PER_MILLISECOND)
                        if (!snapshot.freshHistory.isValid(
                                nowNanos,
                                expectedPhase = null,
                                expectedGapNanos = requiredGap,
                            )
                        ) {
                            return PacingInvalid(snapshot)
                        }
                        val history = snapshot.freshHistory
                        if (history != null) {
                            freshHasDeadline = true
                            freshEligibleAtNanos = Math.addExact(history.lastGrantNanos, requiredGap)
                        }
                        freshNextRequiredGapNanos = requiredGap
                    }

                    is FrameRate.MaxFps -> {
                        val fps = frameRate.fps
                        if (fps <= 0) return PacingInvalid(snapshot)
                        val quotient = NANOS_PER_SECOND / fps
                        val remainder = (NANOS_PER_SECOND % fps).toInt()
                        val history = snapshot.freshHistory
                        val priorPhase = if (history == null) {
                            0
                        } else {
                            val phase = history.phase ?: return PacingInvalid(snapshot)
                            if (history.lastGrantNanos < 0L || history.lastGrantNanos > nowNanos ||
                                phase < 0 || phase >= fps
                            ) {
                                return PacingInvalid(snapshot)
                            }
                            val expectedGap = quotient + if (remainder != 0 && phase < remainder) 1L else 0L
                            if (history.requiredGapNanos != expectedGap) return PacingInvalid(snapshot)
                            freshHasDeadline = true
                            freshEligibleAtNanos = Math.addExact(history.lastGrantNanos, history.requiredGapNanos)
                            phase
                        }
                        val sum = priorPhase + remainder
                        val carry = if (sum >= fps) 1 else 0
                        freshHasNextPhase = true
                        freshNextPhase = sum - carry * fps
                        freshNextRequiredGapNanos = quotient + carry
                    }
                }
            }

            var grantKind: PacingGrantKind? = null
            var grantHasNextPhase = false
            var grantNextPhase = 0
            var grantNextRequiredGapNanos = 0L
            var deferredKind: PacingGrantKind? = null
            var deferredEligibleAtNanos = Long.MAX_VALUE

            if (snapshot.completedUnpublished != null) {
                val eligibleAt = if (outputHasDeadline) outputEligibleAtNanos else nowNanos
                if (eligibleAt <= nowNanos) {
                    grantKind = PacingGrantKind.CompletedOutput
                    grantHasNextPhase = outputHasNextPhase
                    grantNextPhase = outputNextPhase
                    grantNextRequiredGapNanos = outputNextRequiredGapNanos
                } else {
                    deferredKind = PacingGrantKind.CompletedOutput
                    deferredEligibleAtNanos = eligibleAt
                }
            }

            if (hasFreshCandidate) {
                val eligibleAt = if (freshHasDeadline) freshEligibleAtNanos else nowNanos
                if (eligibleAt <= nowNanos) {
                    if (grantKind == null) {
                        grantKind = PacingGrantKind.FreshSource
                        grantHasNextPhase = freshHasNextPhase
                        grantNextPhase = freshNextPhase
                        grantNextRequiredGapNanos = freshNextRequiredGapNanos
                    }
                } else if (eligibleAt < deferredEligibleAtNanos) {
                    deferredKind = PacingGrantKind.FreshSource
                    deferredEligibleAtNanos = eligibleAt
                }
            }

            val repeat = snapshot.repeat
            if (repeat != null) {
                val intervalMillis = context.parameters.frameRepeatIntervalMillis
                if (intervalMillis != null) {
                    if (intervalMillis <= 0L || !repeat.hasValidFingerprints() ||
                        nowNanos < repeat.publishedPayload.timestampElapsedRealtimeNanos
                    ) {
                        return PacingInvalid(snapshot)
                    }
                    if (repeat.publishedFingerprint.matches(repeat.currentFingerprint)) {
                        val intervalNanos = Math.multiplyExact(intervalMillis, NANOS_PER_MILLISECOND)
                        val repeatEligibleAt = Math.addExact(
                            repeat.publishedPayload.timestampElapsedRealtimeNanos,
                            intervalNanos,
                        )
                        val eligibleAt = maxOf(
                            if (outputHasDeadline) outputEligibleAtNanos else 0L,
                            repeatEligibleAt,
                        )
                        if (eligibleAt <= nowNanos) {
                            if (grantKind == null) {
                                grantKind = PacingGrantKind.RepeatOutput
                                grantHasNextPhase = outputHasNextPhase
                                grantNextPhase = outputNextPhase
                                grantNextRequiredGapNanos = outputNextRequiredGapNanos
                            }
                        } else if (eligibleAt < deferredEligibleAtNanos) {
                            deferredKind = PacingGrantKind.RepeatOutput
                            deferredEligibleAtNanos = eligibleAt
                        }
                    }
                }
            }

            val exactGrantKind = grantKind
            if (exactGrantKind != null) {
                PacingGrant(
                    snapshot,
                    exactGrantKind,
                    if (grantHasNextPhase) grantNextPhase else null,
                    grantNextRequiredGapNanos,
                )
            } else {
                val exactDeferredKind = deferredKind ?: return PacingNotEligible(snapshot)
                PacingDeferred(snapshot, deferredEligibleAtNanos, exactDeferredKind)
            }
        } catch (_: ArithmeticException) {
            PacingInvalid(snapshot)
        }
    }

    private fun PacingContext.hasValidIdentities(): Boolean =
        policyIdentity > 0L && candidateIdentity > 0L && (wakeIdentity == null || wakeIdentity > 0L)

    private fun PacingHistory?.isValid(nowNanos: Long, expectedPhase: Int?, expectedGapNanos: Long): Boolean =
        this == null || lastGrantNanos >= 0L && lastGrantNanos <= nowNanos && phase == expectedPhase &&
                requiredGapNanos == expectedGapNanos

    private fun RepeatOutputCandidate.hasValidFingerprints(): Boolean =
        publishedPayload.timestampElapsedRealtimeNanos >= 0L &&
                publishedFingerprint.hasValidIdentities() && currentFingerprint.hasValidIdentities()

    private fun PacingCacheFingerprint.hasValidIdentities(): Boolean =
        currentTarget.generation > 0L && captureGeometry.widthPx > 0 && captureGeometry.heightPx > 0 &&
                captureGeometry.densityDpi > 0 && renderGeneration > 0L && jpegGeneration > 0L &&
                cacheValidityGeneration > 0L

    private fun PacingCacheFingerprint.matches(current: PacingCacheFingerprint): Boolean =
        currentTarget === current.currentTarget &&
                renderGeneration == current.renderGeneration && jpegGeneration == current.jpegGeneration &&
                cacheValidityGeneration == current.cacheValidityGeneration &&
                captureGeometry.widthPx == current.captureGeometry.widthPx &&
                captureGeometry.heightPx == current.captureGeometry.heightPx &&
                captureGeometry.densityDpi == current.captureGeometry.densityDpi &&
                parameters.sourceRegion == current.parameters.sourceRegion &&
                parameters.crop.left == current.parameters.crop.left && parameters.crop.top == current.parameters.crop.top &&
                parameters.crop.right == current.parameters.crop.right && parameters.crop.bottom == current.parameters.crop.bottom &&
                parameters.outputSize.matches(current.parameters.outputSize) && parameters.rotation == current.parameters.rotation &&
                parameters.mirror == current.parameters.mirror && parameters.colorMode == current.parameters.colorMode &&
                parameters.jpegQuality == current.parameters.jpegQuality

    private fun OutputSize.matches(other: OutputSize): Boolean = when (this) {
        is OutputSize.ScaleFactor -> other is OutputSize.ScaleFactor && factor == other.factor
        is OutputSize.TargetSize -> other is OutputSize.TargetSize && width == other.width && height == other.height &&
                contentMode == other.contentMode
    }

    private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
    private const val NANOS_PER_SECOND: Long = 1_000_000_000L
}
