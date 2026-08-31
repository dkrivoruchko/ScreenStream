package io.screenstream.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class PublicApiValuesContractTest {
    // Verification: API-01
    @Test
    fun parameterDefaultsCopyAndEqualityIncludeEveryField() {
        val defaults = ScreenCaptureParameters.DEFAULT

        assertEquals(ScreenCaptureParameters(), defaults)
        assertEquals(SourceRegion.Full, defaults.sourceRegion)
        assertEquals(CropInsetsPx.ZERO, defaults.crop)
        assertEquals(OutputSize.ScaleFactor(0.5), defaults.outputSize)
        assertEquals(Rotation.Degrees0, defaults.rotation)
        assertEquals(Mirror.None, defaults.mirror)
        assertEquals(ColorMode.Color, defaults.colorMode)
        assertEquals(FrameRate.Auto, defaults.frameRate)
        assertEquals(null, defaults.frameRepeatInterval)
        assertEquals(80, defaults.jpegQuality)

        val custom = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
            outputSize = OutputSize.TargetSize(
                widthPx = 1920,
                heightPx = 1080,
                contentMode = OutputSize.ContentMode.Stretch,
            ),
            rotation = Rotation.Degrees270,
            mirror = Mirror.Vertical,
            colorMode = ColorMode.Grayscale,
            frameRate = FrameRate.SamplingInterval(1_001.milliseconds),
            frameRepeatInterval = 1_000.milliseconds,
            jpegQuality = 0,
        )
        val copied = custom.copy()

        assertNotSame(custom, copied)
        assertEquals(custom, copied)
        assertEquals(custom.hashCode(), copied.hashCode())
        assertFalse(custom == defaults)
        assertEquals(100, custom.copy(jpegQuality = 100).jpegQuality)
        assertFalse(custom == custom.copy(sourceRegion = SourceRegion.LeftHalf))
        assertFalse(custom == custom.copy(crop = CropInsetsPx.ZERO))
        assertFalse(custom == custom.copy(outputSize = OutputSize.ScaleFactor(1.0)))
        assertFalse(custom == custom.copy(rotation = Rotation.Degrees180))
        assertFalse(custom == custom.copy(mirror = Mirror.Horizontal))
        assertFalse(custom == custom.copy(colorMode = ColorMode.Color))
        assertFalse(custom == custom.copy(frameRate = FrameRate.MaxFps(60)))
        assertFalse(custom == custom.copy(frameRepeatInterval = null))
        assertFalse(custom == custom.copy(jpegQuality = 1))
    }

    // Verification: API-01
    @Test
    fun publicSemanticConstantsMatchProductContract() {
        assertEquals(0..100, ScreenCaptureParameters.JPEG_QUALITY_RANGE)
        assertEquals(
            1_000.milliseconds..3_600_000.milliseconds,
            ScreenCaptureParameters.FRAME_REPEAT_INTERVAL_RANGE,
        )
        assertEquals(1..120, FrameRate.MAX_FPS_RANGE)
        assertEquals(1_001.milliseconds..3_600_000.milliseconds, FrameRate.SAMPLING_INTERVAL_RANGE)
        assertEquals("image/jpeg", EncodedImageFrame.JPEG_MIME_TYPE)
    }

    // Verification: API-01
    @Test
    fun structuralValuesHaveConsistentEqualityAndHash() {
        assertStructuralValue { CaptureMetrics(widthPx = 1920, heightPx = 1080, densityDpi = 420) }
        assertStructuralValue { createStructuralParameters() }
        assertStructuralValue { CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4) }
        assertStructuralValue { OutputSize.ScaleFactor(factor = 0.75) }
        assertStructuralValue {
            OutputSize.TargetSize(
                widthPx = 1280,
                heightPx = 720,
                contentMode = OutputSize.ContentMode.Stretch,
            )
        }
        assertStructuralValue { FrameRate.MaxFps(fps = 60) }
        assertStructuralValue { FrameRate.SamplingInterval(interval = 1_500.milliseconds) }
        assertStructuralValue { ImageRect.create(leftPx = 1, topPx = 2, rightPx = 1279, bottomPx = 718) }
        assertStructuralValue { CaptureGeometry.create(widthPx = 1280, heightPx = 720, densityDpi = 320) }
        assertStructuralValue { ImageSize.create(widthPx = 640, heightPx = 360) }
        assertStructuralValue { createStructuralEffectiveParameters() }
        assertStructuralValue {
            ScreenCaptureState.Active.create(
                effectiveParameters = createStructuralEffectiveParameters(),
                isCapturedContentVisible = true,
            )
        }
        assertStructuralValue {
            ScreenCaptureState.Reconfiguring.create(
                requestedParameters = createStructuralParameters(),
                lastEffectiveParameters = createStructuralEffectiveParameters(),
                isCapturedContentVisible = false,
            )
        }
        assertStructuralValue {
            ScreenCaptureState.Suspended.create(
                requestedParameters = createStructuralParameters(),
                problem = ScreenCaptureProblem.ResourceExhausted,
                lastEffectiveParameters = createStructuralEffectiveParameters(),
                isCapturedContentVisible = null,
            )
        }
        assertStructuralValue {
            ScreenCaptureState.Stopped.create(
                reason = ScreenCaptureStopReason.ProjectionStopped,
                requestedParameters = createStructuralParameters(),
                lastEffectiveParameters = createStructuralEffectiveParameters(),
            )
        }
        assertStructuralValue {
            ScreenCaptureState.Failed.create(
                problem = ScreenCaptureProblem.InternalFailure,
                requestedParameters = createStructuralParameters(),
                lastEffectiveParameters = createStructuralEffectiveParameters(),
            )
        }
        assertStructuralValue {
            createStats(
                encodedFrameCount = 3L,
                producedFrameCount = 5L,
                averageProducedFps = 29.5,
                averageEncodingDuration = 12.milliseconds,
                averageReadbackDuration = 4.milliseconds,
                lastEncodedByteCount = 1_024,
                averageEncodedByteCount = 768,
            )
        }
        assertStructuralValue { ScreenCaptureFrameDropStats.create(byStaleWork = 2L, byFailure = 1L) }
        assertStructuralValue {
            ScreenCaptureDeliveryDropStats.create(byConsumerBusy = 4L, byCallbackFailure = 3L)
        }
    }

    // Verification: API-01
    @Test
    fun parameterNumericBoundariesAreInclusiveAndRejectAdjacentValues() {
        listOf(0, 100).forEach { quality ->
            assertEquals(quality, ScreenCaptureParameters(jpegQuality = quality).jpegQuality)
        }
        listOf(-1, 101).forEach { quality ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenCaptureParameters(jpegQuality = quality)
            }
        }

        listOf(1_000L, 3_600_000L).forEach { millis ->
            val actual = ScreenCaptureParameters(frameRepeatInterval = millis.milliseconds).frameRepeatInterval
            assertTrue(actual == millis.milliseconds)
        }
        listOf(999L, 3_600_001L).forEach { millis ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenCaptureParameters(frameRepeatInterval = millis.milliseconds)
            }
        }
        listOf(Duration.ZERO, Duration.INFINITE).forEach { interval ->
            assertThrows(IllegalArgumentException::class.java) {
                ScreenCaptureParameters(frameRepeatInterval = interval)
            }
        }

        listOf(1, 120).forEach { fps ->
            assertEquals(fps, FrameRate.MaxFps(fps).fps)
        }
        listOf(0, 121).forEach { fps ->
            assertThrows(IllegalArgumentException::class.java) { FrameRate.MaxFps(fps) }
        }

        listOf(1_001L, 3_600_000L).forEach { millis ->
            assertEquals(millis.milliseconds, FrameRate.SamplingInterval(millis.milliseconds).interval)
        }
        listOf(1_000L, 3_600_001L).forEach { millis ->
            assertThrows(IllegalArgumentException::class.java) {
                FrameRate.SamplingInterval(millis.milliseconds)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameRate.SamplingInterval(Duration.INFINITE)
        }
    }

    // Verification: API-01
    @Test
    fun scaleAndTargetSizesRequireFinitePositiveGeometry() {
        listOf(Double.MIN_VALUE, 1.0, Double.MAX_VALUE).forEach { factor ->
            assertEquals(factor, OutputSize.ScaleFactor(factor).factor, 0.0)
        }
        listOf(0.0, -0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { factor ->
            assertThrows(IllegalArgumentException::class.java) { OutputSize.ScaleFactor(factor) }
        }

        assertEquals(
            OutputSize.TargetSize(widthPx = 1, heightPx = Int.MAX_VALUE),
            OutputSize.TargetSize(widthPx = 1, heightPx = Int.MAX_VALUE),
        )
        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                OutputSize.TargetSize(widthPx = invalid, heightPx = 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                OutputSize.TargetSize(widthPx = 1, heightPx = invalid)
            }
        }
    }

    // Verification: API-01
    @Test
    fun cropAndOutputGeometryEnforcePositiveOrderedBounds() {
        assertEquals(CropInsetsPx.ZERO, CropInsetsPx(left = 0, top = 0, right = 0, bottom = 0))
        listOf(
            { CropInsetsPx(left = -1, top = 0, right = 0, bottom = 0) },
            { CropInsetsPx(left = 0, top = -1, right = 0, bottom = 0) },
            { CropInsetsPx(left = 0, top = 0, right = -1, bottom = 0) },
            { CropInsetsPx(left = 0, top = 0, right = 0, bottom = -1) },
        ).forEach { create ->
            assertThrows(IllegalArgumentException::class.java) { create() }
        }

        assertEquals(
            CaptureMetrics(widthPx = 1, heightPx = Int.MAX_VALUE, densityDpi = 1),
            CaptureMetrics(widthPx = 1, heightPx = Int.MAX_VALUE, densityDpi = 1),
        )
        assertPositiveGeometryTriplet { width, height, density -> CaptureMetrics(width, height, density) }
        assertPositiveGeometryTriplet { width, height, density -> CaptureGeometry.create(width, height, density) }

        assertEquals(ImageSize.create(1, Int.MAX_VALUE), ImageSize.create(1, Int.MAX_VALUE))
        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ImageSize.create(invalid, 1) }
            assertThrows(IllegalArgumentException::class.java) { ImageSize.create(1, invalid) }
        }

        assertEquals(ImageRect.create(0, 0, 1, Int.MAX_VALUE), ImageRect.create(0, 0, 1, Int.MAX_VALUE))
        listOf(
            { ImageRect.create(-1, 0, 1, 1) },
            { ImageRect.create(0, -1, 1, 1) },
            { ImageRect.create(0, 0, 0, 1) },
            { ImageRect.create(1, 0, 1, 1) },
            { ImageRect.create(0, 0, 1, 0) },
            { ImageRect.create(0, 1, 1, 1) },
        ).forEach { create ->
            assertThrows(IllegalArgumentException::class.java) { create() }
        }

        val geometry = CaptureGeometry.create(widthPx = 4, heightPx = 3, densityDpi = 320)
        val finalSize = ImageSize.create(widthPx = 2, heightPx = 2)
        ScreenCaptureEffectiveParameters.create(
            appliedParameters = ScreenCaptureParameters.DEFAULT,
            captureGeometry = geometry,
            appliedSourceRect = ImageRect.create(leftPx = 0, topPx = 0, rightPx = 4, bottomPx = 3),
            finalImageSize = finalSize,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureEffectiveParameters.create(
                ScreenCaptureParameters.DEFAULT,
                geometry,
                ImageRect.create(leftPx = 0, topPx = 0, rightPx = 5, bottomPx = 3),
                finalSize,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureEffectiveParameters.create(
                ScreenCaptureParameters.DEFAULT,
                geometry,
                ImageRect.create(leftPx = 0, topPx = 0, rightPx = 4, bottomPx = 4),
                finalSize,
            )
        }
    }

    // Verification: API-01
    @Test
    fun screenCaptureProblemValuesRemainComplete() {
        assertEquals(
            setOf(
                ScreenCaptureProblem.InvalidRequest,
                ScreenCaptureProblem.CaptureUnavailable,
                ScreenCaptureProblem.ResourceExhausted,
                ScreenCaptureProblem.InternalFailure,
                ScreenCaptureProblem.UnsupportedColorSpace,
            ),
            ScreenCaptureProblem.entries.toSet(),
        )
    }

    // Verification: API-01
    @Test
    fun statsRequireFiniteNonNegativeMeasurementsAndCoherentByteCounts() {
        val empty = createStats()
        assertEquals(empty, createStats())
        assertEquals(empty.hashCode(), createStats().hashCode())

        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                createStats(averageProducedFps = invalid)
            }
        }
        listOf((-1).milliseconds, Duration.INFINITE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                createStats(averageEncodingDuration = invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                createStats(averageReadbackDuration = invalid)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { createStats(encodedFrameCount = -1L) }
        assertThrows(IllegalArgumentException::class.java) { createStats(producedFrameCount = -1L) }
        assertThrows(IllegalArgumentException::class.java) { createStats(lastEncodedByteCount = -1) }
        assertThrows(IllegalArgumentException::class.java) { createStats(averageEncodedByteCount = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            createStats(encodedFrameCount = 0L, lastEncodedByteCount = 1, averageEncodedByteCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createStats(encodedFrameCount = 1L, lastEncodedByteCount = 0, averageEncodedByteCount = 0)
        }
    }

    // Verification: API-01
    @Test
    fun dropTotalsSaturateAndRejectNegativeComponents() {
        val frameDrops = ScreenCaptureFrameDropStats.create(
            byStaleWork = Long.MAX_VALUE,
            byFailure = Long.MAX_VALUE,
        )
        val deliveryDrops = ScreenCaptureDeliveryDropStats.create(
            byConsumerBusy = Long.MAX_VALUE,
            byCallbackFailure = 1L,
        )

        assertEquals(Long.MAX_VALUE, frameDrops.total)
        assertEquals(Long.MAX_VALUE, deliveryDrops.total)
        assertEquals(3L, ScreenCaptureFrameDropStats.create(1L, 2L).total)
        assertEquals(7L, ScreenCaptureDeliveryDropStats.create(3L, 4L).total)
        assertEquals(frameDrops, ScreenCaptureFrameDropStats.create(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(frameDrops.hashCode(), ScreenCaptureFrameDropStats.create(Long.MAX_VALUE, Long.MAX_VALUE).hashCode())
        assertEquals(deliveryDrops, ScreenCaptureDeliveryDropStats.create(Long.MAX_VALUE, 1L))
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureFrameDropStats.create(byStaleWork = -1L, byFailure = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenCaptureDeliveryDropStats.create(byConsumerBusy = 0L, byCallbackFailure = -1L)
        }
    }

    private fun assertPositiveGeometryTriplet(create: (Int, Int, Int) -> Any) {
        listOf(
            intArrayOf(0, 1, 1),
            intArrayOf(-1, 1, 1),
            intArrayOf(1, 0, 1),
            intArrayOf(1, -1, 1),
            intArrayOf(1, 1, 0),
            intArrayOf(1, 1, -1),
        ).forEach { values ->
            assertThrows(IllegalArgumentException::class.java) {
                create(values[0], values[1], values[2])
            }
        }
    }

    private fun assertStructuralValue(create: () -> Any) {
        val first = create()
        val second = create()

        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    private fun createStructuralParameters(): ScreenCaptureParameters = ScreenCaptureParameters(
        sourceRegion = SourceRegion.RightHalf,
        crop = CropInsetsPx(left = 1, top = 2, right = 3, bottom = 4),
        outputSize = OutputSize.TargetSize(
            widthPx = 1280,
            heightPx = 720,
            contentMode = OutputSize.ContentMode.Stretch,
        ),
        rotation = Rotation.Degrees270,
        mirror = Mirror.Vertical,
        colorMode = ColorMode.Grayscale,
        frameRate = FrameRate.SamplingInterval(interval = 1_500.milliseconds),
        frameRepeatInterval = 2_000.milliseconds,
        jpegQuality = 90,
    )

    private fun createStructuralEffectiveParameters(): ScreenCaptureEffectiveParameters =
        ScreenCaptureEffectiveParameters.create(
            appliedParameters = createStructuralParameters(),
            captureGeometry = CaptureGeometry.create(widthPx = 1280, heightPx = 720, densityDpi = 320),
            appliedSourceRect = ImageRect.create(leftPx = 1, topPx = 2, rightPx = 1279, bottomPx = 718),
            finalImageSize = ImageSize.create(widthPx = 640, heightPx = 360),
        )

    private fun createStats(
        encodedFrameCount: Long = 0L,
        producedFrameCount: Long = 0L,
        averageProducedFps: Double = 0.0,
        averageEncodingDuration: Duration = Duration.ZERO,
        averageReadbackDuration: Duration = Duration.ZERO,
        lastEncodedByteCount: Int = 0,
        averageEncodedByteCount: Int = 0,
    ): ScreenCaptureStats = ScreenCaptureStats.create(
        encodedFrameCount = encodedFrameCount,
        producedFrameCount = producedFrameCount,
        droppedFrames = ScreenCaptureFrameDropStats.create(byStaleWork = 0L, byFailure = 0L),
        droppedDeliveries = ScreenCaptureDeliveryDropStats.create(byConsumerBusy = 0L, byCallbackFailure = 0L),
        averageProducedFps = averageProducedFps,
        averageEncodingDuration = averageEncodingDuration,
        averageReadbackDuration = averageReadbackDuration,
        lastEncodedByteCount = lastEncodedByteCount,
        averageEncodedByteCount = averageEncodedByteCount,
    )
}
