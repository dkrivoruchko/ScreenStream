package io.screenstream.capture.internal.capture

import android.graphics.Bitmap
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.screenstream.capture.ColorMode
import io.screenstream.capture.CropInsetsPx
import io.screenstream.capture.Mirror
import io.screenstream.capture.OutputSize
import io.screenstream.capture.Rotation
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.SourceRegion
import io.screenstream.capture.internal.runtime.ElapsedRealtimeClock
import io.screenstream.capture.internal.session.topology.SessionPlanResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The HandlerThread, frame listener, Canvas producer, finite waits, synthetic resolver SDK, and authority flag only
 * arrange a real production-renderer read. Callback counts, queue observations, and timeouts are not verdicts. The
 * maintained verdict is the literal plan geometry plus every pixel from [RawPixelOracle]. Provisional API 34-37
 * cases force a Full plan through the leaf renderer and do not claim that Session admitted a provisional frame.
 */
@RunWith(AndroidJUnit4::class)
internal class GLRendererRawPixelTest {
    // Verification: IMG-01
    @Test
    fun fullContractMatrixMatchesIndependentCpuOracle() {
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
    fun preApi32FullHalfScaleMatchesIndependentCpuOracle() {
        verifyCases(
            listOf(
                RenderCase(
                    resolverSdkInt = 31,
                    sourceDimensionsAreAuthoritative = true,
                    oracleCase = RawPixelOracle.Case(
                        name = "api31-full-half-scale",
                        parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(0.5)),
                        logicalWidthPx = 10,
                        logicalHeightPx = 6,
                        targetImage = RawPixelOracle.expandedTenBySixTarget,
                        expectedTargetMode = RawPixelOracle.TargetMode.Full,
                        expectedTargetWidthPx = 10,
                        expectedTargetHeightPx = 6,
                        expectedOutputWidthPx = 5,
                        expectedOutputHeightPx = 3,
                    ),
                ),
            ),
        )
    }

    // Verification: IMG-01
    @Test
    fun api32Through37DownscaledAndProvisionalFullMatchIndependentCpuOracle() {
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(0.5),
            rotation = Rotation.Degrees90,
            mirror = Mirror.Horizontal,
        )
        val cases = mutableListOf<RenderCase>()
        for (sdkInt in 32..37) {
            cases += RenderCase(
                resolverSdkInt = sdkInt,
                sourceDimensionsAreAuthoritative = true,
                oracleCase = RawPixelOracle.Case(
                    name = "api$sdkInt-authoritative-downscaled-rotated-mirrored",
                    parameters = parameters,
                    logicalWidthPx = 10,
                    logicalHeightPx = 6,
                    targetImage = RawPixelOracle.fiveByThreeTarget,
                    expectedTargetMode = RawPixelOracle.TargetMode.Downscaled,
                    expectedTargetWidthPx = 5,
                    expectedTargetHeightPx = 3,
                    expectedOutputWidthPx = 3,
                    expectedOutputHeightPx = 5,
                ),
            )
        }
        for (sdkInt in 34..37) {
            cases += RenderCase(
                resolverSdkInt = sdkInt,
                sourceDimensionsAreAuthoritative = false,
                oracleCase = RawPixelOracle.Case(
                    name = "api$sdkInt-provisional-forced-full-leaf",
                    parameters = parameters,
                    logicalWidthPx = 10,
                    logicalHeightPx = 6,
                    targetImage = RawPixelOracle.expandedTenBySixTarget,
                    expectedTargetMode = RawPixelOracle.TargetMode.Full,
                    expectedTargetWidthPx = 10,
                    expectedTargetHeightPx = 6,
                    expectedOutputWidthPx = 3,
                    expectedOutputHeightPx = 5,
                ),
            )
        }

        verifyCases(cases)
    }

    private fun fullCase(
        name: String,
        parameters: ScreenCaptureParameters,
        outputWidthPx: Int,
        outputHeightPx: Int,
    ): RenderCase = RenderCase(
        resolverSdkInt = 32,
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
    )

    private fun expandedFullCase(
        name: String,
        parameters: ScreenCaptureParameters,
        outputWidthPx: Int,
        outputHeightPx: Int,
    ): RenderCase = RenderCase(
        resolverSdkInt = 32,
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
        val harness = RealRendererHarness()
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
                val rendered = harness.render(plan, oracleCase.targetImage, oracleCase.name)
                assertPixels(oracleCase, expected, rendered)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                harness.close()
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
        oracleCase: RawPixelOracle.Case,
        expected: RawPixelOracle.ExpectedImage,
        rendered: RenderedFrame,
    ) {
        val tolerance = when (oracleCase.expectedTargetMode) {
            RawPixelOracle.TargetMode.Downscaled -> DOWNSCALED_TOLERANCE
            RawPixelOracle.TargetMode.Full -> when (rendered.precision) {
                EglOwner.FragmentPrecision.High -> HIGH_PRECISION_TOLERANCE
                EglOwner.FragmentPrecision.Medium -> MEDIUM_PRECISION_TOLERANCE
            }
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
    }

    private class RealRendererHarness : TargetOwner.SourceSink, CaptureCallbackBoundary {
        private val captureThread = HandlerThread("sce-img01-renderer").apply { start() }
        private val captureHandler = Handler(captureThread.looper)
        private val pendingTicket = AtomicReference<ReadTicket?>()
        private val closed = AtomicBoolean(false)

        internal fun render(
            plan: CapturePlan,
            targetImage: RawPixelOracle.TargetImage,
            caseName: String,
        ): RenderedFrame {
            check(!closed.get())
            val resources = runOnCaptureThread("$caseName setup") { openResources(plan) }
            var primaryFailure: Throwable? = null
            try {
                val carrier = ByteBuffer.allocateDirect(plan.rgbaCarrierByteCount)
                val ticket = ReadTicket(caseName, resources.renderer, carrier)
                check(pendingTicket.compareAndSet(null, ticket)) { "$caseName: another frame ticket is pending" }
                postTargetImage(resources.target.producerSurface, targetImage, ticket)
                ticket.await()
                return RenderedFrame(carrier, resources.precision)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    closeResources(resources, caseName)
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

        override fun onSourceAvailable(candidate: SourceCandidate) {
            val ticket = pendingTicket.getAndSet(null) ?: return
            val accepted = try {
                captureHandler.post { ticket.read() }
            } catch (failure: Throwable) {
                ticket.fail(failure)
                return
            }
            if (!accepted) ticket.fail(AssertionError("${ticket.caseName}: readback post was rejected"))
        }

        override fun onCallbackException(identity: CaptureCallbackIdentity, failure: Exception) {
            pendingTicket.getAndSet(null)?.fail(failure)
        }

        internal fun close() {
            if (!closed.compareAndSet(false, true)) return
            pendingTicket.getAndSet(null)?.fail(AssertionError("renderer harness closed with a pending frame ticket"))
            val quitAccepted = captureThread.quitSafely()
            captureThread.join(TIMEOUT_MILLIS)
            check(quitAccepted) { "Capture HandlerThread rejected quitSafely" }
            check(!captureThread.isAlive) { "Capture HandlerThread did not terminate within $TIMEOUT_MILLIS ms" }
        }

        private fun openResources(plan: CapturePlan): OpenedResources {
            val eglOwner = EglOwner()
            var targetOwner: TargetOwner? = null
            var renderer: GLRenderer? = null
            try {
                val precision = eglOwner.open()
                val openedTarget = TargetOwner(
                    captureHandler = captureHandler,
                    eglOwner = eglOwner,
                    sourceSink = this,
                    callbackBoundary = this,
                    platformSdkInt = Build.VERSION.SDK_INT,
                )
                targetOwner = openedTarget
                openedTarget.open(plan)
                openedTarget.installListener()
                val openedRenderer = GLRenderer(
                    eglOwner = eglOwner,
                    targetOwner = openedTarget,
                    precision = precision,
                    clock = ElapsedRealtimeClock { 0L },
                    platformSdkInt = Build.VERSION.SDK_INT,
                )
                renderer = openedRenderer
                openedRenderer.open(plan)
                return OpenedResources(eglOwner, openedTarget, openedRenderer, precision)
            } catch (failure: Throwable) {
                val cleanup = retireOnCaptureThread(eglOwner, targetOwner, renderer)
                cleanup.addTo(failure)
                throw failure
            }
        }

        private fun postTargetImage(
            surface: Surface,
            targetImage: RawPixelOracle.TargetImage,
            ticket: ReadTicket,
        ) {
            val bitmap = Bitmap.createBitmap(targetImage.widthPx, targetImage.heightPx, Bitmap.Config.ARGB_8888)
            bitmap.density = Bitmap.DENSITY_NONE
            bitmap.setPixels(
                targetImage.copyTopDownArgbPixels(),
                0,
                targetImage.widthPx,
                0,
                0,
                targetImage.widthPx,
                targetImage.heightPx,
            )
            var primaryFailure: Throwable? = null
            var canvasLocked = false
            var unlockEntered = false
            var canvas: android.graphics.Canvas? = null
            try {
                canvas = surface.lockCanvas(null)
                canvasLocked = true
                assertEquals("${ticket.caseName}: Canvas width", targetImage.widthPx, canvas.width)
                assertEquals("${ticket.caseName}: Canvas height", targetImage.heightPx, canvas.height)
                val paint = Paint().apply {
                    isAntiAlias = false
                    isDither = false
                    isFilterBitmap = false
                }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                unlockEntered = true
                surface.unlockCanvasAndPost(canvas)
                canvasLocked = false
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                if (canvasLocked && !unlockEntered) {
                    try {
                        surface.unlockCanvasAndPost(checkNotNull(canvas))
                    } catch (unlockFailure: Throwable) {
                        val primary = primaryFailure
                        if (primary != null) primary.addSuppressed(unlockFailure) else throw unlockFailure
                    }
                }
                bitmap.recycle()
                if (primaryFailure != null) {
                    pendingTicket.compareAndSet(ticket, null)
                    ticket.fail(primaryFailure)
                }
            }
        }

        private fun closeResources(resources: OpenedResources, caseName: String) {
            val completion = BlockingCommand<Unit>("$caseName cleanup")
            val fenceAccepted = captureHandler.post {
                val listenerRemoval = try {
                    resources.target.fenceAndRemoveListener()
                } catch (failure: Throwable) {
                    TargetOwner.ListenerRemovalOutcome(proof = null, failure = failure)
                }
                pendingTicket.getAndSet(null)?.fail(AssertionError("$caseName: source did not settle before listener fence"))
                val disposeAccepted = try {
                    captureHandler.post {
                        val report = retireOnCaptureThread(
                            eglOwner = resources.eglOwner,
                            targetOwner = resources.target,
                            renderer = resources.renderer,
                            listenerRemoval = listenerRemoval,
                        )
                        try {
                            report.throwIfAny("$caseName: owner cleanup was not exact")
                            completion.succeed(Unit)
                        } catch (failure: Throwable) {
                            completion.fail(failure)
                        }
                    }
                } catch (failure: Throwable) {
                    completion.fail(failure)
                    false
                }
                if (!disposeAccepted) completion.fail(AssertionError("$caseName: cleanup tail post was rejected"))
            }
            if (!fenceAccepted) completion.fail(AssertionError("$caseName: listener-fence post was rejected"))
            completion.await()
        }

        private fun retireOnCaptureThread(
            eglOwner: EglOwner,
            targetOwner: TargetOwner?,
            renderer: GLRenderer?,
            listenerRemoval: TargetOwner.ListenerRemovalOutcome? = null,
        ): FailureReport {
            val failures = FailureReport()
            val removal = listenerRemoval ?: targetOwner?.let { target ->
                try {
                    target.fenceAndRemoveListener()
                } catch (failure: Throwable) {
                    failures.add(failure)
                    null
                }
            }
            failures.add(removal?.failure)

            val rendererOutcome = renderer?.let { owner ->
                try {
                    owner.close()
                } catch (failure: Throwable) {
                    failures.add(failure)
                    null
                }
            }
            failures.add(rendererOutcome?.cleanupFailure)

            val targetOutcome = targetOwner?.let { owner ->
                try {
                    owner.releaseKnownUnattached(removal?.proof)
                } catch (failure: Throwable) {
                    failures.add(failure)
                    null
                }
            }
            failures.add(targetOutcome?.cleanupFailure)
            if (targetOwner?.blocksEglTeardown == true) {
                failures.add(CapturePhysicalException("Target still blocks EGL teardown"))
            }

            val rendererRetired = (renderer == null) || ((rendererOutcome != null) && (rendererOutcome.residue == null))
            val healthyPrerequisites = (targetOwner?.blocksEglTeardown != true) && rendererRetired
            val eglOutcome = if (healthyPrerequisites || !eglOwner.isHealthy) {
                try {
                    eglOwner.close()
                } catch (failure: Throwable) {
                    failures.add(failure)
                    null
                }
            } else {
                failures.add(CapturePhysicalException("Healthy EGL teardown prerequisites remain unproved"))
                null
            }
            failures.add(eglOutcome?.cleanupFailure)
            failures.add(eglOutcome?.residue)

            val namespaceProof = eglOutcome?.namespaceDestroyedProof
            val rendererResidueRetired = if ((namespaceProof != null) && (renderer != null)) {
                renderer.retireGLNamesAfterContextDestroyed(namespaceProof)
            } else {
                false
            }
            val targetResidueRetired = if ((namespaceProof != null) && (targetOwner != null)) {
                targetOwner.retireOesTextureNameAfterContextDestroyed(namespaceProof)
            } else {
                false
            }
            if (!rendererResidueRetired) failures.add(rendererOutcome?.residue)
            if (!targetResidueRetired) failures.add(targetOutcome?.residue)
            return failures
        }

        private fun <T> runOnCaptureThread(name: String, action: () -> T): T {
            val command = BlockingCommand(name, action)
            val accepted = captureHandler.post(command)
            if (!accepted) command.fail(AssertionError("$name post was rejected"))
            return command.await()
        }
    }

    private class ReadTicket(
        internal val caseName: String,
        private val renderer: GLRenderer,
        private val carrier: ByteBuffer,
    ) {
        private val completion = BlockingCommand<Unit>("$caseName readback")

        internal fun read() {
            try {
                renderer.readFrame(carrier)
                completion.succeed(Unit)
            } catch (failure: Throwable) {
                completion.fail(failure)
            }
        }

        internal fun fail(failure: Throwable) {
            completion.fail(failure)
        }

        internal fun await() {
            completion.await()
        }
    }

    private class BlockingCommand<T>(
        private val name: String,
        private val action: (() -> T)? = null,
    ) : Runnable {
        private sealed interface Outcome<out T> {
            class Value<T>(val value: T) : Outcome<T>
            class Failed(val failure: Throwable) : Outcome<Nothing>
        }

        private val completion = CountDownLatch(1)
        private val outcome = AtomicReference<Outcome<T>?>()

        override fun run() {
            val command = checkNotNull(action)
            try {
                succeed(command())
            } catch (failure: Throwable) {
                fail(failure)
            }
        }

        internal fun succeed(value: T) {
            if (outcome.compareAndSet(null, Outcome.Value(value))) completion.countDown()
        }

        internal fun fail(failure: Throwable) {
            if (outcome.compareAndSet(null, Outcome.Failed(failure))) completion.countDown()
        }

        internal fun await(): T {
            if (!completion.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw AssertionError("$name did not settle within $TIMEOUT_MILLIS ms")
            }
            return when (val settled = checkNotNull(outcome.get())) {
                is Outcome.Value -> settled.value
                is Outcome.Failed -> throw settled.failure
            }
        }
    }

    private class FailureReport {
        private val failures = mutableListOf<Throwable>()

        internal fun add(failure: Throwable?) {
            if ((failure != null) && failures.none { it === failure }) failures += failure
        }

        internal fun addTo(primary: Throwable) {
            for (failure in failures) primary.addSuppressed(failure)
        }

        internal fun throwIfAny(message: String) {
            if (failures.isEmpty()) return
            val assertion = AssertionError(message)
            for (failure in failures) assertion.addSuppressed(failure)
            throw assertion
        }
    }

    private class RenderCase(
        internal val resolverSdkInt: Int,
        internal val sourceDimensionsAreAuthoritative: Boolean,
        internal val oracleCase: RawPixelOracle.Case,
    )

    private class RotationCase(
        internal val rotation: Rotation,
        internal val outputWidthPx: Int,
        internal val outputHeightPx: Int,
    )

    private class OpenedResources(
        internal val eglOwner: EglOwner,
        internal val target: TargetOwner,
        internal val renderer: GLRenderer,
        internal val precision: EglOwner.FragmentPrecision,
    )

    private class RenderedFrame(
        internal val carrier: ByteBuffer,
        internal val precision: EglOwner.FragmentPrecision,
    )

    private companion object {
        private const val DENSITY_DPI = 320
        private const val TIMEOUT_MILLIS = 10_000L
        private const val HIGH_PRECISION_TOLERANCE = 2
        private const val MEDIUM_PRECISION_TOLERANCE = 6
        private const val DOWNSCALED_TOLERANCE = 12
        private val REQUIRED_CROP = CropInsetsPx(left = 1, top = 0, right = 1, bottom = 1)
    }
}
