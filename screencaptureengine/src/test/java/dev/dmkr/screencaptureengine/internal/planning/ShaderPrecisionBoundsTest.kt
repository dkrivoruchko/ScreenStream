package dev.dmkr.screencaptureengine.internal.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.nextDown
import kotlin.math.nextUp

class ShaderPrecisionBoundsTest {
    @Test
    fun unitRoundoffGammaIntervalAndInterpolationMatchBigDecimalOracle() {
        val path = CoordinatePrecisionEvidence(
            intervalError = 1.0e-9,
            maxAbsIntermediate = 1.75,
            base = -0.25,
            deltaX = 0.5,
            deltaY = -0.125,
        )
        val result = ShaderPrecisionBounds.evaluate(
            evidence(path = path, precisionBits = 24, operations = 11, targetWidth = 4096, targetHeight = 2048),
        ) as ShaderPrecisionFact.Accepted
        val expectedNormalized = oracleNormalizedError(path)

        assertEquals(
            expectedNormalized.multiply(BigDecimal.valueOf(4096L)).toDouble(),
            result.bounds.targetXTexels,
            1.0e-15,
        )
        assertEquals(
            expectedNormalized.multiply(BigDecimal.valueOf(2048L)).toDouble(),
            result.bounds.targetYTexels,
            1.0e-15,
        )
        assertTrue(result.bounds.outputXPixels < 0.5)
        assertTrue(result.bounds.outputYPixels < 0.5)
    }

    @Test
    fun allFourConvertedBoundsMustBeStrictlyBelowHalf() {
        val exactlyHalfAtTargetX = CoordinatePrecisionEvidence(
            intervalError = 0.5,
            maxAbsIntermediate = 0.0,
            base = 0.0,
            deltaX = 0.0,
            deltaY = 0.0,
        )
        val fact = ShaderPrecisionBounds.evaluate(
            evidence(
                path = zeroPath(),
                textureU = exactlyHalfAtTargetX,
                precisionBits = 24,
                operations = 0,
                targetWidth = 1,
            ),
        ) as ShaderPrecisionFact.Insufficient

        assertEquals(0.5, fact.bounds!!.targetXTexels, 0.0)
        val below = exactlyHalfAtTargetX.copy(intervalError = exactlyHalfAtTargetX.intervalError.nextDown())
        val above = exactlyHalfAtTargetX.copy(intervalError = exactlyHalfAtTargetX.intervalError.nextUp())
        listOf(below, exactlyHalfAtTargetX, above).forEachIndexed { boundaryIndex, boundary ->
            val facts = listOf(
                evidence(path = zeroPath(), textureU = boundary, precisionBits = 24, operations = 0),
                evidence(path = zeroPath(), textureV = boundary, precisionBits = 24, operations = 0),
                evidence(path = zeroPath(), outputU = boundary, precisionBits = 24, operations = 0),
                evidence(path = zeroPath(), outputV = boundary, precisionBits = 24, operations = 0),
            ).map(ShaderPrecisionBounds::evaluate)
            if (boundaryIndex == 0) {
                assertTrue(facts.all { it is ShaderPrecisionFact.Accepted })
            } else {
                assertTrue(facts.all { it is ShaderPrecisionFact.Insufficient })
            }
        }
    }

    @Test
    fun onePixelDimensionsAndZeroRoundedOperationsAreSupported() {
        val result = ShaderPrecisionBounds.evaluate(
            evidence(path = zeroPath(), precisionBits = 24, operations = 0),
        ) as ShaderPrecisionFact.Accepted

        assertEquals(0.0, result.gamma, 0.0)
        assertEquals(ShaderPrecisionErrorBounds(0.0, 0.0, 0.0, 0.0), result.bounds)
    }

    @Test
    fun unitRoundoffDomainFailureAndMalformedEvidenceAreTypedSeparately() {
        assertTrue(
            ShaderPrecisionBounds.evaluate(
                evidence(path = zeroPath(), precisionBits = 2, operations = 4),
            ) is ShaderPrecisionFact.Insufficient,
        )
        assertEquals(
            ShaderPrecisionFact.InvalidEvidence,
            ShaderPrecisionBounds.evaluate(
                evidence(path = zeroPath().copy(intervalError = Double.NaN)),
            ),
        )
        assertEquals(
            ShaderPrecisionFact.InvalidEvidence,
            ShaderPrecisionBounds.evaluate(
                evidence(path = zeroPath(), targetWidth = 0),
            ),
        )
    }

    @Test
    fun eachAxisCanIndependentlyRejectThePreparedEvidence() {
        val failing = CoordinatePrecisionEvidence(0.5, 0.0, 0.0, 0.0, 0.0)
        val facts = listOf(
            evidence(path = zeroPath(), textureU = failing),
            evidence(path = zeroPath(), textureV = failing),
            evidence(path = zeroPath(), outputU = failing),
            evidence(path = zeroPath(), outputV = failing),
        ).map(ShaderPrecisionBounds::evaluate)

        assertTrue(facts.all { it is ShaderPrecisionFact.Insufficient })
    }

    @Test
    fun exactGammaCounterexamplesCannotRoundDownIntoAcceptance() {
        val realistic = CoordinatePrecisionEvidence(
            intervalError = 7.63280604183259e-17,
            maxAbsIntermediate = 167_771.65999999997,
            base = 0.0,
            deltaX = 0.0,
            deltaY = 0.0,
        )
        assertTrue(
            ShaderPrecisionBounds.evaluate(
                evidence(path = zeroPath(), textureU = realistic, precisionBits = 24, operations = 50),
            ) is ShaderPrecisionFact.Insufficient,
        )

        val lowPrecision = CoordinatePrecisionEvidence(
            intervalError = 7.401486830834378e-17,
            maxAbsIntermediate = 1.5.nextDown(),
            base = 0.0,
            deltaX = 0.0,
            deltaY = 0.0,
        )
        assertTrue(
            ShaderPrecisionBounds.evaluate(
                evidence(path = zeroPath(), textureU = lowPrecision, precisionBits = 2, operations = 1),
            ) is ShaderPrecisionFact.Insufficient,
        )

        val highOperationCount = CoordinatePrecisionEvidence(
            intervalError = 0.0,
            maxAbsIntermediate = 1.8904289466005253,
            base = 0.0,
            deltaX = 0.0,
            deltaY = 0.0,
        )
        val unitRoundoff = Math.scalb(1.0, -21)
        val roundedGamma = (438_656.0 * unitRoundoff) / (1.0 - 438_656.0 * unitRoundoff)
        assertTrue(roundedGamma * highOperationCount.maxAbsIntermediate < 0.5)
        assertTrue(oracleNormalizedError(highOperationCount, precisionBits = 21, operations = 438_656) >= HALF)
        assertTrue(
            ShaderPrecisionBounds.evaluate(
                evidence(
                    path = zeroPath(),
                    textureU = highOperationCount,
                    precisionBits = 21,
                    operations = 438_656,
                ),
            ) is ShaderPrecisionFact.Insufficient,
        )
    }

    @Test
    fun everyPublishedAxisBoundIsAtLeastTheIndependentHighPrecisionOracle() {
        val coordinates = listOf(
            CoordinatePrecisionEvidence(1.0e-17, 17.25, -0.125, 0.75, -0.375),
            CoordinatePrecisionEvidence(2.0e-15, 2_048.5, 0.5, -0.25, 0.125),
            CoordinatePrecisionEvidence(Double.MIN_VALUE, 0.25, Double.MIN_VALUE, -0.0, 0.0),
            CoordinatePrecisionEvidence(0.0, 1.0e100, 1.0e-100, -1.0e-100, 2.0e-100),
        )
        val evidence = evidence(
            path = zeroPath(),
            textureU = coordinates[0],
            textureV = coordinates[1],
            outputU = coordinates[2],
            outputV = coordinates[3],
            precisionBits = 24,
            operations = 37,
            targetWidth = 4_093,
            targetHeight = 2_047,
            finalWidth = 3_001,
            finalHeight = 1_999,
        )
        val actual = when (val fact = ShaderPrecisionBounds.evaluate(evidence)) {
            is ShaderPrecisionFact.Accepted -> fact.bounds
            is ShaderPrecisionFact.Insufficient -> requireNotNull(fact.bounds)
            ShaderPrecisionFact.InvalidEvidence -> error("Valid precision evidence was rejected.")
        }
        val actualAxes = listOf(
            actual.targetXTexels,
            actual.targetYTexels,
            actual.outputXPixels,
            actual.outputYPixels,
        )
        val dimensions = listOf(4_093, 2_047, 3_001, 1_999)

        coordinates.indices.forEach { index ->
            val oracle = oracleNormalizedError(coordinates[index], precisionBits = 24, operations = 37)
                .multiply(BigDecimal.valueOf(dimensions[index].toLong()))
            assertTrue(
                "axis=$index actual=${actualAxes[index]} oracle=$oracle",
                BigDecimal(actualAxes[index]) >= oracle,
            )
        }
    }

    private fun evidence(
        path: CoordinatePrecisionEvidence,
        textureU: CoordinatePrecisionEvidence = path,
        textureV: CoordinatePrecisionEvidence = path,
        outputU: CoordinatePrecisionEvidence = path,
        outputV: CoordinatePrecisionEvidence = path,
        precisionBits: Int = 24,
        operations: Int = 1,
        targetWidth: Int = 1,
        targetHeight: Int = 1,
        finalWidth: Int = 1,
        finalHeight: Int = 1,
    ): ShaderPrecisionEvidence = ShaderPrecisionEvidence(
        precisionBits,
        operations,
        textureU,
        textureV,
        outputU,
        outputV,
        targetWidth,
        targetHeight,
        finalWidth,
        finalHeight,
    )

    private fun zeroPath(): CoordinatePrecisionEvidence = CoordinatePrecisionEvidence(0.0, 0.0, 0.0, 0.0, 0.0)

    private fun oracleNormalizedError(
        path: CoordinatePrecisionEvidence,
        precisionBits: Int = 24,
        operations: Int = 11,
    ): BigDecimal {
        val context = MathContext(100, RoundingMode.CEILING)
        val two = BigDecimal.valueOf(2L)
        val unit = BigDecimal.ONE.divide(two.pow(precisionBits))
        val nu = unit.multiply(BigDecimal.valueOf(operations.toLong()))
        val gamma = nu.divide(BigDecimal.ONE.subtract(nu, context), context)
        val arithmetic = gamma.multiply(BigDecimal(path.maxAbsIntermediate))
        val interpolationMagnitude = listOf(path.base, path.deltaX, path.deltaY)
            .map { BigDecimal(kotlin.math.abs(it)) }
            .fold(BigDecimal.ZERO) { total, value -> total.add(value) }
        val interpolation = two.multiply(unit).multiply(interpolationMagnitude)
        return BigDecimal(path.intervalError).add(arithmetic).add(interpolation)
    }

    private companion object {
        val HALF: BigDecimal = BigDecimal("0.5")
    }
}
