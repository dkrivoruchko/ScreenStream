package io.screenstream.capture.internal.session.topology

import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.capture.CaptureTargetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionPlanResolutionGeometryTest {
    // Verification: SES-04
    @Test
    fun earlyDownscaleStartsAtApi32AndRemainsEligibleThroughApi37() {
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5))

        val api31 = resolvePlan(parameters, widthPx = 10, heightPx = 6, platformSdkInt = 31)
        assertTarget(api31, CaptureTargetMode.Full, widthPx = 10, heightPx = 6)

        listOf(32, 37).forEach { platformSdkInt ->
            val resolved = resolvePlan(parameters, widthPx = 10, heightPx = 6, platformSdkInt = platformSdkInt)
            assertTarget(resolved, CaptureTargetMode.Downscaled, widthPx = 5, heightPx = 3)
        }
    }

    // Verification: SES-04
    @Test
    fun api34ProvisionalDimensionsUseFullUntilTheyBecomeAuthoritative() {
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5))

        val provisional = resolvePlan(
            parameters,
            widthPx = 10,
            heightPx = 6,
            platformSdkInt = 34,
            sourceDimensionsAreAuthoritative = false,
        )
        assertTarget(provisional, CaptureTargetMode.Full, widthPx = 10, heightPx = 6)

        val authoritative = resolvePlan(
            parameters,
            widthPx = 10,
            heightPx = 6,
            platformSdkInt = 34,
            sourceDimensionsAreAuthoritative = true,
        )
        assertTarget(authoritative, CaptureTargetMode.Downscaled, widthPx = 5, heightPx = 3)
    }

    // Verification: SES-04
    @Test
    fun rotatedOddDimensionsUseRotationAwareSourceRequirements() {
        val resolved = resolvePlan(
            parameters = ScreenCaptureParameters(
                rotation = Rotation.Degrees90,
                outputSize = OutputSize.ScaleFactor(0.5),
            ),
            widthPx = 15,
            heightPx = 9,
            platformSdkInt = 32,
        )

        assertEquals(5, resolved.effectiveParameters.finalImageSize.widthPx)
        assertEquals(8, resolved.effectiveParameters.finalImageSize.heightPx)
        assertTarget(resolved, CaptureTargetMode.Downscaled, widthPx = 10, heightPx = 6)
    }

    // Verification: SES-04
    @Test
    fun scaleRequiringTheFullAspectMultipleUsesFullTarget() {
        val resolved = resolvePlan(
            parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.9)),
            widthPx = 6,
            heightPx = 4,
            platformSdkInt = 32,
        )

        assertTarget(resolved, CaptureTargetMode.Full, widthPx = 6, heightPx = 4)
    }

    // Verification: SES-04
    @Test
    fun regionCropAndTargetSizeEachCloseEarlyDownscaleEligibility() {
        val ineligibleParameters = listOf(
            ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                outputSize = OutputSize.ScaleFactor(0.5),
            ),
            ScreenCaptureParameters(
                crop = CropInsetsPx(left = 1, top = 0, right = 0, bottom = 0),
                outputSize = OutputSize.ScaleFactor(0.5),
            ),
            ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(5, 3, OutputSize.ContentMode.Stretch),
            ),
        )

        ineligibleParameters.forEach { parameters ->
            val resolved = resolvePlan(parameters, widthPx = 10, heightPx = 6, platformSdkInt = 32)
            assertTarget(resolved, CaptureTargetMode.Full, widthPx = 10, heightPx = 6)
        }
    }

    // Verification: SES-04
    @Test
    fun oddRightHalfCropRotationAndScaleUseProductRoundingOrder() {
        val parameters = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(left = 0, top = 1, right = 1, bottom = 0),
            rotation = Rotation.Degrees90,
            outputSize = OutputSize.ScaleFactor(1.5),
        )

        val result = SessionPlanResolution.resolve(
            parameters = parameters,
            widthPx = 5,
            heightPx = 4,
            densityDpi = 320,
            platformSdkInt = 30,
            sourceDimensionsAreAuthoritative = true,
        )
        assertTrue(result is SessionPlanResolution.Resolved)
        val resolved = result as SessionPlanResolution.Resolved

        assertEquals(2, resolved.effectiveParameters.appliedSourceRect.leftPx)
        assertEquals(1, resolved.effectiveParameters.appliedSourceRect.topPx)
        assertEquals(4, resolved.effectiveParameters.appliedSourceRect.rightPx)
        assertEquals(4, resolved.effectiveParameters.appliedSourceRect.bottomPx)
        assertEquals(5, resolved.effectiveParameters.finalImageSize.widthPx)
        assertEquals(3, resolved.effectiveParameters.finalImageSize.heightPx)
        assertSame(CaptureTargetMode.Full, resolved.capturePlan.targetMode)
        assertSame(resolved.capturePlan.appliedSourceRect, resolved.effectiveParameters.appliedSourceRect)
        assertSame(resolved.capturePlan.rgbaLayout, resolved.encoderPlan)
    }

    // Verification: SES-04
    @Test
    fun independentlyResolvedEquivalentPlansHaveTheSameCaptureConfiguration() {
        val parameters = ScreenCaptureParameters(
            crop = CropInsetsPx(left = 1, top = 1, right = 2, bottom = 0),
            outputSize = OutputSize.TargetSize(8, 5, OutputSize.ContentMode.Stretch),
        )
        val first = resolvePlan(parameters = parameters, widthPx = 9, heightPx = 6)
        val second = resolvePlan(parameters = parameters, widthPx = 9, heightPx = 6)

        assertNotSame(first.capturePlan, second.capturePlan)
        assertNotSame(first.capturePlan.appliedSourceRect, second.capturePlan.appliedSourceRect)
        assertTrue(first.capturePlan.hasSameCaptureConfigurationAs(second.capturePlan))
    }

    // Verification: SES-04
    @Test
    fun equalResolvedRectFromDifferentRawRequestsIsNotTheSameCaptureConfiguration() {
        val fullCropped = resolvePlan(
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.Full,
                crop = CropInsetsPx(left = 3, top = 0, right = 0, bottom = 0),
                outputSize = OutputSize.TargetSize(3, 4, OutputSize.ContentMode.Stretch),
            ),
        )
        val rightHalf = resolvePlan(
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                outputSize = OutputSize.TargetSize(3, 4, OutputSize.ContentMode.Stretch),
            ),
        )

        assertEquals(fullCropped.capturePlan.appliedSourceRect, rightHalf.capturePlan.appliedSourceRect)
        assertFalse(fullCropped.capturePlan.hasSameCaptureConfigurationAs(rightHalf.capturePlan))
    }

    // Verification: SES-04
    @Test
    fun captureConfigurationComparisonRejectsRepresentativeFieldAndBoundsChanges() {
        val outputSize = OutputSize.TargetSize(3, 2, OutputSize.ContentMode.Stretch)
        val baseline = resolvePlan(parameters = ScreenCaptureParameters(outputSize = outputSize))
        val mirrored = resolvePlan(parameters = ScreenCaptureParameters(mirror = Mirror.Horizontal, outputSize = outputSize))
        val differentDensity = resolvePlan(
            parameters = ScreenCaptureParameters(outputSize = outputSize),
            densityDpi = 321,
        )
        val differentBounds = resolvePlan(
            parameters = ScreenCaptureParameters(
                crop = CropInsetsPx(left = 1, top = 0, right = 0, bottom = 0),
                outputSize = outputSize,
            ),
        )

        assertFalse(baseline.capturePlan.hasSameCaptureConfigurationAs(mirrored.capturePlan))
        assertFalse(baseline.capturePlan.hasSameCaptureConfigurationAs(differentDensity.capturePlan))
        assertFalse(baseline.capturePlan.hasSameCaptureConfigurationAs(differentBounds.capturePlan))
    }

    // Verification: SES-04
    @Test
    fun aspectFitRoundsWithoutPadding() {
        val result = SessionPlanResolution.resolve(
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(8, 8, OutputSize.ContentMode.AspectFit),
            ),
            widthPx = 5,
            heightPx = 3,
            densityDpi = 320,
            platformSdkInt = 30,
            sourceDimensionsAreAuthoritative = true,
        )
        assertTrue(result is SessionPlanResolution.Resolved)
        val size = (result as SessionPlanResolution.Resolved).effectiveParameters.finalImageSize

        assertEquals(8, size.widthPx)
        assertEquals(5, size.heightPx)
    }

    // Verification: SES-04
    @Test
    fun tinyPositiveScaleClampsFinalImageAndEncoderLayoutToOnePixel() {
        val resolved = resolvePlan(
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.ScaleFactor(Double.MIN_VALUE),
            ),
            widthPx = 6,
            heightPx = 4,
        )

        assertEquals(1, resolved.effectiveParameters.finalImageSize.widthPx)
        assertEquals(1, resolved.effectiveParameters.finalImageSize.heightPx)
        assertSame(resolved.capturePlan.rgbaLayout, resolved.encoderPlan)
        assertEquals(1, resolved.encoderPlan.widthPx)
        assertEquals(1, resolved.encoderPlan.heightPx)
        assertEquals(4, resolved.encoderPlan.rowByteCount)
        assertEquals(4, resolved.encoderPlan.byteCount)
    }

    // Verification: SES-04
    @Test
    fun emptyCropIsInvalidButPositiveUnaddressableCarrierIsResourceExhausted() {
        val invalid = SessionPlanResolution.resolve(
            parameters = ScreenCaptureParameters(crop = CropInsetsPx(left = 2, top = 0, right = 1, bottom = 0)),
            widthPx = 3,
            heightPx = 2,
            densityDpi = 320,
            platformSdkInt = 30,
            sourceDimensionsAreAuthoritative = true,
        )
        assertTrue(invalid is SessionPlanResolution.Rejected)
        assertSame(ScreenCaptureProblem.InvalidRequest, (invalid as SessionPlanResolution.Rejected).problem)

        val exhausted = SessionPlanResolution.resolve(
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(
                    widthPx = Int.MAX_VALUE,
                    heightPx = 1,
                    contentMode = OutputSize.ContentMode.Stretch,
                ),
            ),
            widthPx = 1,
            heightPx = 1,
            densityDpi = 320,
            platformSdkInt = 30,
            sourceDimensionsAreAuthoritative = true,
        )
        assertTrue(exhausted is SessionPlanResolution.Rejected)
        assertSame(ScreenCaptureProblem.ResourceExhausted, (exhausted as SessionPlanResolution.Rejected).problem)
    }

    private fun resolvePlan(
        parameters: ScreenCaptureParameters,
        widthPx: Int = 6,
        heightPx: Int = 4,
        densityDpi: Int = 320,
        platformSdkInt: Int = 30,
        sourceDimensionsAreAuthoritative: Boolean = true,
    ): SessionPlanResolution.Resolved {
        val result = SessionPlanResolution.resolve(
            parameters = parameters,
            widthPx = widthPx,
            heightPx = heightPx,
            densityDpi = densityDpi,
            platformSdkInt = platformSdkInt,
            sourceDimensionsAreAuthoritative = sourceDimensionsAreAuthoritative,
        )
        assertTrue(result is SessionPlanResolution.Resolved)
        return result as SessionPlanResolution.Resolved
    }

    private fun assertTarget(
        resolved: SessionPlanResolution.Resolved,
        targetMode: CaptureTargetMode,
        widthPx: Int,
        heightPx: Int,
    ) {
        assertSame(targetMode, resolved.capturePlan.targetMode)
        assertEquals(widthPx, resolved.capturePlan.targetWidthPx)
        assertEquals(heightPx, resolved.capturePlan.targetHeightPx)
    }
}
