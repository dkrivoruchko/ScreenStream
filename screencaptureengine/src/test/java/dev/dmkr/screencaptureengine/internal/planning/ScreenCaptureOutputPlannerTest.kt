package dev.dmkr.screencaptureengine.internal.planning

import dev.dmkr.screencaptureengine.CaptureGeometry
import dev.dmkr.screencaptureengine.CaptureGeometrySource
import dev.dmkr.screencaptureengine.ContentMode
import dev.dmkr.screencaptureengine.CropInsetsPx
import dev.dmkr.screencaptureengine.EncodedImageFormats
import dev.dmkr.screencaptureengine.FrameRate
import dev.dmkr.screencaptureengine.ImageEncoderInfo
import dev.dmkr.screencaptureengine.OutputSize
import dev.dmkr.screencaptureengine.ReadbackMode
import dev.dmkr.screencaptureengine.Rotation
import dev.dmkr.screencaptureengine.ScreenCaptureParameters
import dev.dmkr.screencaptureengine.ScreenCaptureProblemKind
import dev.dmkr.screencaptureengine.SourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureOutputPlannerTest {
    @Test
    fun plan_resolvesOddRightHalfCropRotationAspectFitAndAutoFrameRate() {
        val result = planner().plan(
            geometry = CaptureGeometry(widthPx = 101, heightPx = 77, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
                rotation = Rotation.Degrees90,
                outputSize = OutputSize.TargetSize(width = 36, height = 20, contentMode = ContentMode.AspectFit),
                frameRate = FrameRate.Auto,
            ),
        )

        val plan = result.successPlan()
        assertEquals(51, plan.appliedSourceRect.left)
        assertEquals(2, plan.appliedSourceRect.top)
        assertEquals(98, plan.appliedSourceRect.right)
        assertEquals(73, plan.appliedSourceRect.bottom)
        assertEquals(71, plan.orientedContentSize.width)
        assertEquals(47, plan.orientedContentSize.height)
        assertEquals(30, plan.finalImageSize.width)
        assertEquals(20, plan.finalImageSize.height)
        assertEquals(43, plan.captureTarget.width)
        assertEquals(33, plan.captureTarget.height)
        assertEquals(FrameRate.MaxFps(30), plan.frameRate)
        assertEquals(120, plan.rowStrideBytes)
        assertEquals(2_400L, plan.rgbaByteCount)
        assertEquals(plan.finalImageSize.width, plan.encoderRequest.width)
        assertEquals(plan.finalImageSize.height, plan.encoderRequest.height)
    }

    @Test
    fun plan_downscalesCaptureTargetConservativelyForFinalDownscale() {
        val result = planner().plan(
            geometry = CaptureGeometry(widthPx = 101, heightPx = 51, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(width = 50, height = 26, contentMode = ContentMode.Stretch)),
        )

        val plan = result.successPlan()
        assertEquals(50, plan.finalImageSize.width)
        assertEquals(26, plan.finalImageSize.height)
        assertEquals(52, plan.captureTarget.width)
        assertEquals(26, plan.captureTarget.height)
        assertTrue(plan.captureTarget.isEarlyDownscaled)
        assertTrue(plan.captureTarget.scaleFromLogicalCapture >= (26.0 / 51.0))
    }

    @Test
    fun plan_doesNotUpscaleCaptureTargetForLargeFinalOutput() {
        val result = planner(maxOutputPixels = 2_000_000).plan(
            geometry = CaptureGeometry(widthPx = 640, heightPx = 480, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(2.0)),
        )

        val plan = result.successPlan()
        assertEquals(1_280, plan.finalImageSize.width)
        assertEquals(960, plan.finalImageSize.height)
        assertEquals(640, plan.captureTarget.width)
        assertEquals(480, plan.captureTarget.height)
        assertFalse(plan.captureTarget.isEarlyDownscaled)
    }

    @Test
    fun toEffectiveParameters_usesResolvedFrameRateAndRuntimeBackendInfo() {
        val encoderInfo = ImageEncoderInfo(
            providerId = "test-provider",
            outputFormat = EncodedImageFormats.Jpeg,
            backendName = "test-backend",
        )
        val result = planner(readbackMode = ReadbackMode.Es3Pbo).plan(
            geometry = CaptureGeometry(widthPx = 64, heightPx = 48, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(frameRate = FrameRate.Auto),
        )

        val effectiveParameters = result.successPlan().toEffectiveParameters(encoderInfo)
        assertFalse(effectiveParameters.frameRate === FrameRate.Auto)
        assertEquals(FrameRate.MaxFps(30), effectiveParameters.frameRate)
        assertEquals(ReadbackMode.Es3Pbo, effectiveParameters.readbackMode)
        assertSame(encoderInfo, effectiveParameters.encoderInfo)
    }

    @Test
    fun plan_propagatesMaxEncodedBytesToEncoderRequest() {
        val maxEncodedBytes = 64 * 1024
        val result = planner(maxEncodedBytes = maxEncodedBytes).plan(
            geometry = CaptureGeometry(widthPx = 80, heightPx = 60, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(40, 30, ContentMode.Stretch)),
        )

        val plan = result.successPlan()
        assertEquals(40, plan.encoderRequest.width)
        assertEquals(30, plan.encoderRequest.height)
        assertEquals(160, plan.encoderRequest.rowStrideBytes)
        assertEquals(maxEncodedBytes, plan.encoderRequest.maxEncodedBytes)
    }

    @Test
    fun plan_rejectsEmptyCropAsOutputPlanInvalid() {
        val result = planner().plan(
            geometry = CaptureGeometry(widthPx = 100, heightPx = 100, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
            parameters = ScreenCaptureParameters(crop = CropInsetsPx(left = 50, right = 50)),
        )

        val failure = result.failure()
        assertEquals(ScreenCaptureProblemKind.OutputPlanInvalid, failure.kind)
    }

    @Test
    fun plan_rejectsFinalImageDimensionCapsBeforeRuntimeAllocation() {
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxFinalImageWidth = 79).plan(
                geometry = CaptureGeometry(widthPx = 80, heightPx = 60, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(80, 60, ContentMode.Stretch)),
            ).failure().kind,
        )
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxFinalImageHeight = 59).plan(
                geometry = CaptureGeometry(widthPx = 80, heightPx = 60, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(80, 60, ContentMode.Stretch)),
            ).failure().kind,
        )
    }

    @Test
    fun plan_rejectsCaptureTargetDimensionCapsBeforeRuntimeAllocation() {
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxCaptureTargetWidth = 99).plan(
                geometry = CaptureGeometry(widthPx = 100, heightPx = 50, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(),
            ).failure().kind,
        )
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxCaptureTargetHeight = 49).plan(
                geometry = CaptureGeometry(widthPx = 100, heightPx = 50, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(),
            ).failure().kind,
        )
    }

    @Test
    fun plan_rejectsCaptureTargetPixelAndByteCapsBeforeRuntimeAllocation() {
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxCaptureTargetPixels = 99L).plan(
                geometry = CaptureGeometry(widthPx = 10, heightPx = 10, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(),
            ).failure().kind,
        )
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxCaptureTargetBytes = 399L).plan(
                geometry = CaptureGeometry(widthPx = 10, heightPx = 10, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(),
            ).failure().kind,
        )
    }

    @Test
    fun plan_rejectsOutputAndBufferLimitsBeforeRuntimeAllocation() {
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxOutputPixels = 100).plan(
                geometry = CaptureGeometry(widthPx = 20, heightPx = 20, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(20, 20, ContentMode.Stretch)),
            ).failure().kind,
        )
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxRowStrideBytes = 199).plan(
                geometry = CaptureGeometry(widthPx = 50, heightPx = 10, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(50, 10, ContentMode.Stretch)),
            ).failure().kind,
        )
        assertEquals(
            ScreenCaptureProblemKind.OutputLimitsExceeded,
            planner(maxRgbaBytes = 1_999L).plan(
                geometry = CaptureGeometry(widthPx = 50, heightPx = 10, densityDpi = 320, source = CaptureGeometrySource.MetricsProvider),
                parameters = ScreenCaptureParameters(outputSize = OutputSize.TargetSize(50, 10, ContentMode.Stretch)),
            ).failure().kind,
        )
    }

    private fun planner(
        maxOutputPixels: Int = 268_435_456,
        maxEncodedBytes: Int = 1_024,
        maxFinalImageWidth: Int = Int.MAX_VALUE,
        maxFinalImageHeight: Int = Int.MAX_VALUE,
        maxCaptureTargetWidth: Int = Int.MAX_VALUE,
        maxCaptureTargetHeight: Int = Int.MAX_VALUE,
        maxCaptureTargetPixels: Long = Long.MAX_VALUE,
        maxCaptureTargetBytes: Long = Long.MAX_VALUE,
        maxRowStrideBytes: Int = Int.MAX_VALUE,
        maxRgbaBytes: Long = Long.MAX_VALUE,
        readbackMode: ReadbackMode = ReadbackMode.Es2,
    ): ScreenCaptureOutputPlanner =
        ScreenCaptureOutputPlanner(
            OutputPlanningLimits(
                maxOutputPixels = maxOutputPixels,
                maxEncodedBytes = maxEncodedBytes,
                maxFinalImageWidth = maxFinalImageWidth,
                maxFinalImageHeight = maxFinalImageHeight,
                maxCaptureTargetWidth = maxCaptureTargetWidth,
                maxCaptureTargetHeight = maxCaptureTargetHeight,
                maxCaptureTargetPixels = maxCaptureTargetPixels,
                maxCaptureTargetBytes = maxCaptureTargetBytes,
                maxRowStrideBytes = maxRowStrideBytes,
                maxRgbaBytes = maxRgbaBytes,
                readbackMode = readbackMode,
            ),
        )

    private fun OutputPlanResult.successPlan(): ScreenCaptureOutputPlan =
        (this as OutputPlanResult.Success).plan

    private fun OutputPlanResult.failure(): OutputPlanResult.Failure =
        this as OutputPlanResult.Failure
}
