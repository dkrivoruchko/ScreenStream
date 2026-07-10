@file:Suppress("unused") // Dormant until the v41 clean-spine cutover.

package dev.dmkr.screencaptureengine.internal.planning

import kotlin.math.abs
import kotlin.math.nextDown
import kotlin.math.nextUp

internal data class CoordinatePrecisionEvidence(
    val intervalError: Double,
    val maxAbsIntermediate: Double,
    val base: Double,
    val deltaX: Double,
    val deltaY: Double,
)

internal data class ShaderPrecisionEvidence(
    val precisionBits: Int,
    val roundedOperationCount: Int,
    val textureU: CoordinatePrecisionEvidence,
    val textureV: CoordinatePrecisionEvidence,
    val outputU: CoordinatePrecisionEvidence,
    val outputV: CoordinatePrecisionEvidence,
    val targetWidth: Int,
    val targetHeight: Int,
    val finalWidth: Int,
    val finalHeight: Int,
)

internal data class ShaderPrecisionErrorBounds(
    val targetXTexels: Double,
    val targetYTexels: Double,
    val outputXPixels: Double,
    val outputYPixels: Double,
)

internal sealed interface ShaderPrecisionFact {
    data class Accepted(
        val unitRoundoff: Double,
        val gamma: Double,
        val bounds: ShaderPrecisionErrorBounds,
    ) : ShaderPrecisionFact

    data class Insufficient(
        val bounds: ShaderPrecisionErrorBounds?,
    ) : ShaderPrecisionFact

    data object InvalidEvidence : ShaderPrecisionFact
}

internal object ShaderPrecisionBounds {
    internal fun evaluate(evidence: ShaderPrecisionEvidence): ShaderPrecisionFact {
        if (!evidence.isStructurallyValid()) return ShaderPrecisionFact.InvalidEvidence
        if (evidence.precisionBits > MAX_REPRESENTABLE_UNIT_ROUNDOFF_EXPONENT) {
            return ShaderPrecisionFact.Insufficient(bounds = null)
        }

        val unitRoundoff = Math.scalb(1.0, -evidence.precisionBits)
        if (!unitRoundoff.isFinite() || unitRoundoff <= 0.0) {
            return ShaderPrecisionFact.Insufficient(bounds = null)
        }
        val accumulatedUnitRoundoff = multiplyUp(evidence.roundedOperationCount.toDouble(), unitRoundoff)
        if (accumulatedUnitRoundoff >= 1.0) return ShaderPrecisionFact.Insufficient(bounds = null)
        val gammaDenominator = oneMinusDown(accumulatedUnitRoundoff)
        if (gammaDenominator <= 0.0) return ShaderPrecisionFact.Insufficient(bounds = null)
        val gamma = divideUp(accumulatedUnitRoundoff, gammaDenominator)
        if (!gamma.isFinite()) return ShaderPrecisionFact.Insufficient(bounds = null)

        val targetU = normalizedError(evidence.textureU, unitRoundoff, gamma)
        val targetV = normalizedError(evidence.textureV, unitRoundoff, gamma)
        val outputU = normalizedError(evidence.outputU, unitRoundoff, gamma)
        val outputV = normalizedError(evidence.outputV, unitRoundoff, gamma)
        val bounds = ShaderPrecisionErrorBounds(
            targetXTexels = multiplyUp(targetU, evidence.targetWidth.toDouble()),
            targetYTexels = multiplyUp(targetV, evidence.targetHeight.toDouble()),
            outputXPixels = multiplyUp(outputU, evidence.finalWidth.toDouble()),
            outputYPixels = multiplyUp(outputV, evidence.finalHeight.toDouble()),
        )
        val values = listOf(bounds.targetXTexels, bounds.targetYTexels, bounds.outputXPixels, bounds.outputYPixels)
        if (values.any { !it.isFinite() || it < 0.0 }) return ShaderPrecisionFact.Insufficient(bounds)
        return if (values.all { it < STRICT_HALF_PIXEL }) {
            ShaderPrecisionFact.Accepted(unitRoundoff, gamma, bounds)
        } else {
            ShaderPrecisionFact.Insufficient(bounds)
        }
    }

    private fun normalizedError(
        evidence: CoordinatePrecisionEvidence,
        unitRoundoff: Double,
        gamma: Double,
    ): Double {
        val arithmetic = multiplyUp(gamma, evidence.maxAbsIntermediate)
        val interpolationMagnitude = addUp(
            addUp(abs(evidence.base), abs(evidence.deltaX)),
            abs(evidence.deltaY),
        )
        val interpolation = multiplyUp(multiplyUp(2.0, unitRoundoff), interpolationMagnitude)
        return addUp(addUp(evidence.intervalError, arithmetic), interpolation)
    }

    private fun ShaderPrecisionEvidence.isStructurallyValid(): Boolean =
        precisionBits >= 0 && roundedOperationCount >= 0 &&
            targetWidth > 0 && targetHeight > 0 && finalWidth > 0 && finalHeight > 0 &&
            listOf(textureU, textureV, outputU, outputV).all { coordinate -> coordinate.isValid() }

    private fun CoordinatePrecisionEvidence.isValid(): Boolean =
        intervalError.isFinite() && intervalError >= 0.0 &&
            maxAbsIntermediate.isFinite() && maxAbsIntermediate >= 0.0 &&
            base.isFinite() && deltaX.isFinite() && deltaY.isFinite()
}

private fun addUp(left: Double, right: Double): Double = when {
    left == 0.0 -> right
    right == 0.0 -> left
    else -> (left + right).nextUp()
}

private fun multiplyUp(left: Double, right: Double): Double = when {
    left == 0.0 || right == 0.0 -> 0.0
    left == 1.0 -> right
    right == 1.0 -> left
    else -> (left * right).nextUp()
}

private fun divideUp(numerator: Double, denominator: Double): Double = when {
    numerator == 0.0 -> 0.0
    denominator == 1.0 -> numerator
    else -> (numerator / denominator).nextUp()
}

private fun oneMinusDown(value: Double): Double = when {
    value == 0.0 -> 1.0
    else -> (1.0 - value).nextDown()
}

private const val MAX_REPRESENTABLE_UNIT_ROUNDOFF_EXPONENT: Int = 1074
private const val STRICT_HALF_PIXEL: Double = 0.5
