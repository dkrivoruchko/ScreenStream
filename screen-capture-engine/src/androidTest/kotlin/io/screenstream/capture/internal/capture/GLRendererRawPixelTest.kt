package io.screenstream.capture.internal.capture

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.session.topology.SessionPlanResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Judges real renderer output with independent CPU geometry and every pixel from [RawPixelOracle]. */
@RunWith(AndroidJUnit4::class)
internal class GLRendererRawPixelTest {
    // Verification: IMG-01
    @Test
    fun fullTargetCasesMatchIndependentCpuOracle() {
        val cases = mutableListOf<RenderCase>()
        val rotationCases = listOf(
            RotationCase(Rotation.Degrees0, outputWidthPx = 5, outputHeightPx = 3),
            RotationCase(Rotation.Degrees90, outputWidthPx = 3, outputHeightPx = 5),
            RotationCase(Rotation.Degrees180, outputWidthPx = 5, outputHeightPx = 3),
            RotationCase(Rotation.Degrees270, outputWidthPx = 3, outputHeightPx = 5),
        )
        for (rotationCase in rotationCases) {
            for (mirror in Mirror.entries) {
                cases += fullCase(
                    name = "rotation=${rotationCase.rotation},mirror=$mirror",
                    parameters = ScreenCaptureParameters(
                        outputSize = OutputSize.ScaleFactor(1.0),
                        rotation = rotationCase.rotation,
                        mirror = mirror,
                    ),
                    outputWidthPx = rotationCase.outputWidthPx,
                    outputHeightPx = rotationCase.outputHeightPx,
                )
            }
        }
        cases += fullCase(
            name = "left-half",
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                outputSize = OutputSize.ScaleFactor(1.0),
            ),
            outputWidthPx = 2,
            outputHeightPx = 3,
        )
        cases += fullCase(
            name = "right-half",
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.RightHalf,
                outputSize = OutputSize.ScaleFactor(1.0),
            ),
            outputWidthPx = 3,
            outputHeightPx = 3,
        )
        cases += fullCase(
            name = "crop-1-0-1-1",
            parameters = ScreenCaptureParameters(
                crop = REQUIRED_CROP,
                outputSize = OutputSize.ScaleFactor(1.0),
            ),
            outputWidthPx = 3,
            outputHeightPx = 2,
        )
        cases += fullCase(
            name = "scale-factor-2",
            parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(2.0)),
            outputWidthPx = 10,
            outputHeightPx = 6,
        )
        cases += fullCase(
            name = "stretch-8x8",
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(8, 8, OutputSize.ContentMode.Stretch),
            ),
            outputWidthPx = 8,
            outputHeightPx = 8,
        )
        cases += fullCase(
            name = "aspect-fit-8x8",
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(8, 8, OutputSize.ContentMode.AspectFit),
            ),
            outputWidthPx = 8,
            outputHeightPx = 5,
        )
        cases += fullCase(
            name = "grayscale-fractional-stretch",
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(8, 8, OutputSize.ContentMode.Stretch),
                colorMode = ColorMode.Grayscale,
            ),
            outputWidthPx = 8,
            outputHeightPx = 8,
        )
        cases += fullCase(
            name = "left-half-scale-2-retained-edge",
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                outputSize = OutputSize.ScaleFactor(2.0),
            ),
            outputWidthPx = 4,
            outputHeightPx = 6,
            anchors = listOf(
                PixelAnchor(x = 3, y = 0, red = 179, green = 77, blue = 38),
                PixelAnchor(x = 1, y = 0, red = 236, green = 19, blue = 10),
            ),
        )
        cases += fullCase(
            name = "crop-1-0-1-1-scale-2-retained-edges",
            parameters = ScreenCaptureParameters(
                crop = REQUIRED_CROP,
                outputSize = OutputSize.ScaleFactor(2.0),
            ),
            outputWidthPx = 6,
            outputHeightPx = 4,
            anchors = listOf(
                PixelAnchor(x = 0, y = 0, red = 179, green = 77, blue = 38),
                PixelAnchor(x = 5, y = 0, red = 0, green = 255, blue = 255),
                PixelAnchor(x = 0, y = 3, red = 64, green = 64, blue = 64),
                PixelAnchor(x = 2, y = 1, red = 62, green = 42, blue = 35),
            ),
        )
        cases += fullCase(
            name = "crop-scale-2-rotated-mirrored-retained-edges",
            parameters = ScreenCaptureParameters(
                crop = REQUIRED_CROP,
                outputSize = OutputSize.ScaleFactor(2.0),
                rotation = Rotation.Degrees90,
                mirror = Mirror.Horizontal,
            ),
            outputWidthPx = 4,
            outputHeightPx = 6,
            anchors = listOf(
                PixelAnchor(x = 0, y = 0, red = 179, green = 77, blue = 38),
                PixelAnchor(x = 0, y = 5, red = 0, green = 255, blue = 255),
                PixelAnchor(x = 3, y = 0, red = 64, green = 64, blue = 64),
                PixelAnchor(x = 1, y = 2, red = 62, green = 42, blue = 35),
            ),
        )
        cases += fullCase(
            name = "single-retained-pixel-scale-2",
            parameters = ScreenCaptureParameters(
                crop = CropInsetsPx(left = 2, top = 1, right = 2, bottom = 1),
                outputSize = OutputSize.ScaleFactor(2.0),
            ),
            outputWidthPx = 2,
            outputHeightPx = 2,
            anchors = listOf(
                PixelAnchor(x = 0, y = 0, red = 128, green = 128, blue = 128),
                PixelAnchor(x = 1, y = 0, red = 128, green = 128, blue = 128),
                PixelAnchor(x = 0, y = 1, red = 128, green = 128, blue = 128),
                PixelAnchor(x = 1, y = 1, red = 128, green = 128, blue = 128),
            ),
        )
        cases += expandedFullCase(
            name = "full-closure-left-half-subscale",
            parameters = ScreenCaptureParameters(
                sourceRegion = SourceRegion.LeftHalf,
                outputSize = OutputSize.ScaleFactor(0.5),
            ),
            outputWidthPx = 3,
            outputHeightPx = 3,
        )
        cases += expandedFullCase(
            name = "full-closure-crop-subscale",
            parameters = ScreenCaptureParameters(
                crop = REQUIRED_CROP,
                outputSize = OutputSize.ScaleFactor(0.5),
            ),
            outputWidthPx = 4,
            outputHeightPx = 3,
        )
        cases += expandedFullCase(
            name = "full-closure-target-size",
            parameters = ScreenCaptureParameters(
                outputSize = OutputSize.TargetSize(5, 3, OutputSize.ContentMode.Stretch),
            ),
            outputWidthPx = 5,
            outputHeightPx = 3,
        )
        cases += expandedFullCase(
            name = "full-closure-required-aspect-multiple",
            parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.9)),
            outputWidthPx = 9,
            outputHeightPx = 5,
        )

        verifyCases(cases)
    }

    // Verification: IMG-01
    @Test
    fun downscaledAndProvisionalFullMatchIndependentCpuOracle() {
        val requestedParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(0.5),
            rotation = Rotation.Degrees90,
            mirror = Mirror.Horizontal,
        )
        val neutralProvisionalParameters = ScreenCaptureParameters(
            outputSize = OutputSize.TargetSize(1, 1, OutputSize.ContentMode.Stretch),
        )
        verifyCases(
            listOf(
                RenderCase(
                    resolverSdkInt = Build.VERSION_CODES.S_V2,
                    sourceDimensionsAreAuthoritative = true,
                    oracleCase = RawPixelOracle.Case(
                        name = "authoritative-downscaled-rotated-mirrored",
                        parameters = requestedParameters,
                        logicalWidthPx = 10,
                        logicalHeightPx = 6,
                        targetImage = RawPixelOracle.fiveByThreeTarget,
                        expectedTargetMode = RawPixelOracle.TargetMode.Downscaled,
                        expectedTargetWidthPx = 5,
                        expectedTargetHeightPx = 3,
                        expectedOutputWidthPx = 3,
                        expectedOutputHeightPx = 5,
                    ),
                ),
                RenderCase(
                    resolverSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                    sourceDimensionsAreAuthoritative = false,
                    oracleCase = RawPixelOracle.Case(
                        name = "provisional-forced-full-leaf",
                        // Models API 34 before dimensions are authoritative: the provisional plan keeps a Full 10x6
                        // Target and a separate neutral 1x1 output. It does not test Session admission or make
                        // requested parameters effective.
                        parameters = neutralProvisionalParameters,
                        logicalWidthPx = 10,
                        logicalHeightPx = 6,
                        targetImage = RawPixelOracle.expandedTenBySixTarget,
                        expectedTargetMode = RawPixelOracle.TargetMode.Full,
                        expectedTargetWidthPx = 10,
                        expectedTargetHeightPx = 6,
                        expectedOutputWidthPx = 1,
                        expectedOutputHeightPx = 1,
                    ),
                ),
            ),
        )
    }

    private fun fullCase(
        name: String,
        parameters: ScreenCaptureParameters,
        outputWidthPx: Int,
        outputHeightPx: Int,
        anchors: List<PixelAnchor> = emptyList(),
    ): RenderCase = RenderCase(
        resolverSdkInt = Build.VERSION_CODES.S_V2,
        sourceDimensionsAreAuthoritative = true,
        oracleCase = RawPixelOracle.Case(
            name = name,
            parameters = parameters,
            logicalWidthPx = 5,
            logicalHeightPx = 3,
            targetImage = RawPixelOracle.fiveByThreeTarget,
            expectedTargetMode = RawPixelOracle.TargetMode.Full,
            expectedTargetWidthPx = 5,
            expectedTargetHeightPx = 3,
            expectedOutputWidthPx = outputWidthPx,
            expectedOutputHeightPx = outputHeightPx,
        ),
        anchors = anchors,
    )

    private fun expandedFullCase(
        name: String,
        parameters: ScreenCaptureParameters,
        outputWidthPx: Int,
        outputHeightPx: Int,
    ): RenderCase = RenderCase(
        resolverSdkInt = Build.VERSION_CODES.S_V2,
        sourceDimensionsAreAuthoritative = true,
        oracleCase = RawPixelOracle.Case(
            name = name,
            parameters = parameters,
            logicalWidthPx = 10,
            logicalHeightPx = 6,
            targetImage = RawPixelOracle.expandedTenBySixTarget,
            expectedTargetMode = RawPixelOracle.TargetMode.Full,
            expectedTargetWidthPx = 10,
            expectedTargetHeightPx = 6,
            expectedOutputWidthPx = outputWidthPx,
            expectedOutputHeightPx = outputHeightPx,
        ),
    )

    private fun verifyCases(cases: List<RenderCase>) {
        val fixture = CanvasRendererFixture()
        var primaryFailure: Throwable? = null
        try {
            for (renderCase in cases) {
                val oracleCase = renderCase.oracleCase
                val expected = RawPixelOracle.expected(oracleCase)
                val resolved = SessionPlanResolution.resolve(
                    parameters = oracleCase.parameters,
                    widthPx = oracleCase.logicalWidthPx,
                    heightPx = oracleCase.logicalHeightPx,
                    densityDpi = DENSITY_DPI,
                    platformSdkInt = renderCase.resolverSdkInt,
                    sourceDimensionsAreAuthoritative = renderCase.sourceDimensionsAreAuthoritative,
                )
                assertTrue("${oracleCase.name}: plan was rejected", resolved is SessionPlanResolution.Resolved)
                val plan = (resolved as SessionPlanResolution.Resolved).capturePlan
                assertPlan(oracleCase, expected, plan)
                val rendered = fixture.render(plan, oracleCase.targetImage, oracleCase.name)
                assertPixels(renderCase, expected, rendered)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                fixture.close()
            } catch (cleanupFailure: Throwable) {
                val primary = primaryFailure
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun assertPlan(
        oracleCase: RawPixelOracle.Case,
        expected: RawPixelOracle.ExpectedImage,
        plan: CapturePlan,
    ) {
        val expectedMode = when (oracleCase.expectedTargetMode) {
            RawPixelOracle.TargetMode.Full -> CaptureTargetMode.Full
            RawPixelOracle.TargetMode.Downscaled -> CaptureTargetMode.Downscaled
        }
        assertSame("${oracleCase.name}: target mode", expectedMode, plan.targetMode)
        assertEquals("${oracleCase.name}: target width", oracleCase.expectedTargetWidthPx, plan.targetWidthPx)
        assertEquals("${oracleCase.name}: target height", oracleCase.expectedTargetHeightPx, plan.targetHeightPx)
        assertEquals("${oracleCase.name}: output width", expected.widthPx, plan.outputWidthPx)
        assertEquals("${oracleCase.name}: output height", expected.heightPx, plan.outputHeightPx)
        assertEquals("${oracleCase.name}: source left", expected.sourceLeftPx, plan.appliedSourceRect.leftPx)
        assertEquals("${oracleCase.name}: source top", expected.sourceTopPx, plan.appliedSourceRect.topPx)
        assertEquals("${oracleCase.name}: source right", expected.sourceRightPx, plan.appliedSourceRect.rightPx)
        assertEquals("${oracleCase.name}: source bottom", expected.sourceBottomPx, plan.appliedSourceRect.bottomPx)
    }

    private fun assertPixels(
        renderCase: RenderCase,
        expected: RawPixelOracle.ExpectedImage,
        rendered: CanvasRendererFixture.Frame,
    ) {
        val oracleCase = renderCase.oracleCase
        val tolerance = when (rendered.precision) {
            EglOwner.FragmentPrecision.High -> HIGH_PRECISION_TOLERANCE
            EglOwner.FragmentPrecision.Medium -> MEDIUM_PRECISION_TOLERANCE
        }
        val carrier = rendered.carrier
        assertTrue("${oracleCase.name}: carrier must be direct", carrier.isDirect)
        assertFalse("${oracleCase.name}: carrier must be writable", carrier.isReadOnly)
        assertEquals("${oracleCase.name}: carrier position", 0, carrier.position())
        assertEquals("${oracleCase.name}: carrier limit", expected.widthPx * expected.heightPx * 4, carrier.limit())
        assertEquals("${oracleCase.name}: carrier capacity", carrier.limit(), carrier.capacity())
        for (y in 0 until expected.heightPx) {
            for (x in 0 until expected.widthPx) {
                val offset = ((y * expected.widthPx) + x) * 4
                val red = carrier.get(offset).toInt() and 0xFF
                val green = carrier.get(offset + 1).toInt() and 0xFF
                val blue = carrier.get(offset + 2).toInt() and 0xFF
                val alpha = carrier.get(offset + 3).toInt() and 0xFF
                val actualChannels = intArrayOf(red, green, blue)
                for (channel in 0..2) {
                    val expectedChannel = expected.channelAt(x, y, channel)
                    val error = abs(actualChannels[channel] - expectedChannel)
                    assertTrue(
                        "${oracleCase.name}: pixel=($x,$y), channel=$channel, expected=$expectedChannel, " +
                                "actual=${actualChannels[channel]}, tolerance=$tolerance",
                        error <= tolerance,
                    )
                }
                assertEquals("${oracleCase.name}: pixel=($x,$y), alpha", 255, alpha)
                if (oracleCase.parameters.colorMode == ColorMode.Grayscale) {
                    assertEquals("${oracleCase.name}: pixel=($x,$y), grayscale R/G", red, green)
                    assertEquals("${oracleCase.name}: pixel=($x,$y), grayscale G/B", green, blue)
                }
            }
        }
        for (anchor in renderCase.anchors) {
            val offset = ((anchor.y * expected.widthPx) + anchor.x) * 4
            val literalChannels = intArrayOf(anchor.red, anchor.green, anchor.blue)
            for (channel in 0..2) {
                assertEquals(
                    "${oracleCase.name}: literal CPU anchor=(${anchor.x},${anchor.y}), channel=$channel",
                    literalChannels[channel],
                    expected.channelAt(anchor.x, anchor.y, channel),
                )
                val actual = carrier.get(offset + channel).toInt() and 0xFF
                val error = abs(actual - literalChannels[channel])
                assertTrue(
                    "${oracleCase.name}: literal actual anchor=(${anchor.x},${anchor.y}), channel=$channel, " +
                            "expected=${literalChannels[channel]}, actual=$actual, tolerance=$tolerance",
                    error <= tolerance,
                )
            }
            assertEquals(
                "${oracleCase.name}: literal CPU anchor=(${anchor.x},${anchor.y}), alpha",
                255,
                expected.channelAt(anchor.x, anchor.y, 3),
            )
            assertEquals(
                "${oracleCase.name}: literal actual anchor=(${anchor.x},${anchor.y}), alpha",
                255,
                carrier.get(offset + 3).toInt() and 0xFF,
            )
        }
    }

    private class RenderCase(
        val resolverSdkInt: Int,
        val sourceDimensionsAreAuthoritative: Boolean,
        val oracleCase: RawPixelOracle.Case,
        val anchors: List<PixelAnchor> = emptyList(),
    )

    private class PixelAnchor(
        val x: Int,
        val y: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private class RotationCase(
        val rotation: Rotation,
        val outputWidthPx: Int,
        val outputHeightPx: Int,
    )

    private companion object {
        private const val DENSITY_DPI = 320
        private const val HIGH_PRECISION_TOLERANCE = 2
        private const val MEDIUM_PRECISION_TOLERANCE = 6
        private val REQUIRED_CROP = CropInsetsPx(left = 1, top = 0, right = 1, bottom = 1)
    }
}
