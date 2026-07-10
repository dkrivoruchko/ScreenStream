package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.Size
import dev.dmkr.screencaptureengine.SourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

class BaselineOutputPlannerTest {
    @Test
    fun oddRightHalfCropRotationAndAspectFitUseExactLogicalCoordinates() {
        val plan = targetPlan(
            logicalCaptureSize = Size(101, 77),
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
            rotation = Rotation.Degrees90,
            targetWidth = 36,
            targetHeight = 20,
            contentMode = ContentMode.AspectFit,
        )

        assertEquals(51, plan.appliedSourceRect.left)
        assertEquals(2, plan.appliedSourceRect.top)
        assertEquals(98, plan.appliedSourceRect.right)
        assertEquals(73, plan.appliedSourceRect.bottom)
        assertEquals(Size(71, 47), plan.orientedContentSize)
        assertEquals(Size(30, 20), plan.finalImageSize)
        assertEquals(Size(101, 77), plan.projectionTargetSize)
        assertEquals(PositiveRatio(20, 47), plan.samplingDemand.horizontal)
        assertEquals(PositiveRatio(30, 71), plan.samplingDemand.vertical)
    }

    @Test
    fun leftAndRightHalfSelectionIsNonemptyAwareAndGivesOddPixelToRight() {
        val left = targetPlan(logicalCaptureSize = Size(5, 1), sourceRegion = SourceRegion.LeftHalf)
        val right = targetPlan(logicalCaptureSize = Size(5, 1), sourceRegion = SourceRegion.RightHalf)

        assertEquals(2, left.appliedSourceRect.right - left.appliedSourceRect.left)
        assertEquals(2, right.appliedSourceRect.left)
        assertEquals(3, right.appliedSourceRect.right - right.appliedSourceRect.left)
        assertEquals(
            RequestNonrepresentability.EmptySource,
            (targetFact(logicalCaptureSize = Size(1, 1), sourceRegion = SourceRegion.LeftHalf) as
                BaselineOutputPlanFact.RequestNotRepresentable).reason,
        )
    }

    @Test
    fun emptyCropAndInvalidSizingInputsRemainTypedRequestFacts() {
        val empty = targetFact(
            logicalCaptureSize = Size(5, 3),
            crop = CropInsetsPx(left = 3, top = 0, right = 2, bottom = 0),
        ) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.EmptySource, empty.reason)

        val invalidTarget = targetFact(targetWidth = 0) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.InvalidScalar, invalidTarget.reason)

        val invalidFactor = BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = Size(1, 1),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            rotation = Rotation.Degrees0,
            factor = Double.NaN,
        ) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.InvalidScalar, invalidFactor.reason)
    }

    @Test
    fun scaleFactorUsesHalfUpMinimumOneAndRejectsUnrepresentableFiniteProducts() {
        listOf(0.25, 0.5, 1.0, 1.5).forEach { factor ->
            val size = Size(3, 5)
            assertEquals(
                Size(scaleOracle(size.width, factor), scaleOracle(size.height, factor)),
                scalePlan(size, factor).finalImageSize,
            )
        }
        assertEquals(Size(1, 1), scalePlan(Size(1, 1), factor = Double.MIN_VALUE).finalImageSize)
        assertEquals(
            Size(331_838_376, 1),
            scalePlan(Size(536_870_911, 1), factor = 0.6180971434676966).finalImageSize,
        )

        val huge = BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = Size(Int.MAX_VALUE, 1),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            rotation = Rotation.Degrees0,
            factor = Double.MAX_VALUE,
        ) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.FinalDimension, huge.reason)
    }

    @Test
    fun aspectFitMatchesIndependentIntegerOracleIncludingExactTies() {
        val vectors = listOf(
            AspectVector(3, 2, 2, 2),
            AspectVector(7, 5, 4, 4),
            AspectVector(16, 9, 1920, 1080),
            AspectVector(9, 16, 1000, 1000),
            AspectVector(1, 7, 3, 2),
        )

        vectors.forEach { vector ->
            val plan = targetPlan(
                logicalCaptureSize = Size(vector.sourceWidth, vector.sourceHeight),
                targetWidth = vector.targetWidth,
                targetHeight = vector.targetHeight,
                contentMode = ContentMode.AspectFit,
            )
            assertEquals(vector.oracle(), plan.finalImageSize)
            assertTrue(plan.finalImageSize.width <= vector.targetWidth)
            assertTrue(plan.finalImageSize.height <= vector.targetHeight)
        }

        assertEquals(Size(1920, 1080), vectors[2].oracle())
    }

    @Test
    fun rotationChangesOrientedDimensionsAndSamplingAxes() {
        Rotation.entries.forEach { rotation ->
            val plan = targetPlan(
                logicalCaptureSize = Size(8, 6),
                crop = CropInsetsPx(left = 1, top = 1, right = 2, bottom = 1),
                rotation = rotation,
                targetWidth = 8,
                targetHeight = 10,
                contentMode = ContentMode.Stretch,
            )
            val quarterTurn = rotation == Rotation.Degrees90 || rotation == Rotation.Degrees270
            assertEquals(if (quarterTurn) Size(4, 5) else Size(5, 4), plan.orientedContentSize)
            assertEquals(Size(8, 6), plan.projectionTargetSize)
            assertEquals(
                if (quarterTurn) PositiveRatio(10, 5) else PositiveRatio(8, 5),
                plan.samplingDemand.horizontal,
            )
            assertEquals(
                if (quarterTurn) PositiveRatio(8, 4) else PositiveRatio(10, 4),
                plan.samplingDemand.vertical,
            )
        }
    }

    @Test
    fun tightRgbaUsesFinalRowAddressabilityWithoutAFixedPixelCap() {
        val large = scalePlan(Size(100_000, 1), factor = 1.0)
        assertEquals(400_000, large.rowStrideBytes)
        assertEquals(400_000, large.requiredRgbaBytes)

        val strideOverflow = BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = Size(536_870_912, 1),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            rotation = Rotation.Degrees0,
            factor = 1.0,
        ) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.RgbaAddressability, strideOverflow.reason)

        val finalRowOverflow = BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = Size(536_870_911, 2),
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            rotation = Rotation.Degrees0,
            factor = 1.0,
        ) as BaselineOutputPlanFact.RequestNotRepresentable
        assertEquals(RequestNonrepresentability.RgbaAddressability, finalRowOverflow.reason)
    }

    @Test
    fun capabilityEvidenceIsSeparateFromRequestRepresentability() {
        val plan = targetPlan(logicalCaptureSize = Size(80, 60), targetWidth = 40, targetHeight = 30)
        assertSame(BaselineCapabilityFact.Supported, BaselineOutputPlanner.validateCapabilities(plan, limits()))
        val failures = listOf(
            limits(maxProjectionTargetWidth = 79) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.ProjectionTargetWidth, 80, 79),
            limits(maxProjectionTargetHeight = 59) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.ProjectionTargetHeight, 60, 59),
            limits(maxTextureSize = 79) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.TextureSize, 80, 79),
            limits(maxRenderbufferSize = 39) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.RenderbufferSize, 40, 39),
            limits(maxViewportWidth = 39) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.ViewportWidth, 40, 39),
            limits(maxViewportHeight = 29) to
                BaselineCapabilityFact.DeviceLimitExceeded(BaselineDeviceLimit.ViewportHeight, 30, 29),
        )
        failures.forEach { (evidence, expected) ->
            assertEquals(expected, BaselineOutputPlanner.validateCapabilities(plan, evidence))
        }
        assertSame(
            BaselineCapabilityFact.InvalidEvidence,
            BaselineOutputPlanner.validateCapabilities(plan, limits(maxViewportHeight = 0)),
        )
    }

    private fun scalePlan(size: Size, factor: Double): BaselineOutputPlan =
        (BaselineOutputPlanner.planScaleFactor(
            logicalCaptureSize = size,
            sourceRegion = SourceRegion.Full,
            crop = CropInsetsPx.Zero,
            rotation = Rotation.Degrees0,
            factor = factor,
        ) as BaselineOutputPlanFact.Planned).plan

    private fun targetPlan(
        logicalCaptureSize: Size = Size(5, 3),
        sourceRegion: SourceRegion = SourceRegion.Full,
        crop: CropInsetsPx = CropInsetsPx.Zero,
        rotation: Rotation = Rotation.Degrees0,
        targetWidth: Int = 5,
        targetHeight: Int = 3,
        contentMode: ContentMode = ContentMode.Stretch,
    ): BaselineOutputPlan = (targetFact(
        logicalCaptureSize,
        sourceRegion,
        crop,
        rotation,
        targetWidth,
        targetHeight,
        contentMode,
    ) as BaselineOutputPlanFact.Planned).plan

    private fun targetFact(
        logicalCaptureSize: Size = Size(5, 3),
        sourceRegion: SourceRegion = SourceRegion.Full,
        crop: CropInsetsPx = CropInsetsPx.Zero,
        rotation: Rotation = Rotation.Degrees0,
        targetWidth: Int = 5,
        targetHeight: Int = 3,
        contentMode: ContentMode = ContentMode.Stretch,
    ): BaselineOutputPlanFact = BaselineOutputPlanner.planTargetSize(
        logicalCaptureSize = logicalCaptureSize,
        sourceRegion = sourceRegion,
        crop = crop,
        rotation = rotation,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        contentMode = contentMode,
    )

    private fun limits(
        maxProjectionTargetWidth: Int = Int.MAX_VALUE,
        maxProjectionTargetHeight: Int = Int.MAX_VALUE,
        maxTextureSize: Int = Int.MAX_VALUE,
        maxRenderbufferSize: Int = Int.MAX_VALUE,
        maxViewportWidth: Int = Int.MAX_VALUE,
        maxViewportHeight: Int = Int.MAX_VALUE,
    ): BaselineDeviceLimits = BaselineDeviceLimits(
        maxProjectionTargetWidth,
        maxProjectionTargetHeight,
        maxTextureSize,
        maxRenderbufferSize,
        maxViewportWidth,
        maxViewportHeight,
    )

    private fun scaleOracle(dimension: Int, factor: Double): Int =
        BigDecimal.valueOf(dimension.toLong())
            .multiply(BigDecimal(factor))
            .add(BigDecimal("0.5"))
            .setScale(0, RoundingMode.FLOOR)
            .max(BigDecimal.ONE)
            .intValueExact()

    private data class AspectVector(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
    ) {
        fun oracle(): Size {
            val sourceWidthBig = sourceWidth.toBigInteger()
            val sourceHeightBig = sourceHeight.toBigInteger()
            val targetWidthBig = targetWidth.toBigInteger()
            val targetHeightBig = targetHeight.toBigInteger()
            return if (targetWidthBig * sourceHeightBig <= targetHeightBig * sourceWidthBig) {
                Size(targetWidth, minOf(targetHeight, roundHalfUp(sourceHeightBig * targetWidthBig, sourceWidthBig)))
            } else {
                Size(minOf(targetWidth, roundHalfUp(sourceWidthBig * targetHeightBig, sourceHeightBig)), targetHeight)
            }
        }

        private fun roundHalfUp(numerator: BigInteger, denominator: BigInteger): Int =
            ((numerator * BigInteger.TWO + denominator) / (denominator * BigInteger.TWO))
                .max(BigInteger.ONE)
                .intValueExact()
    }
}
