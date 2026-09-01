package io.screenstream.capture

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.encoding.NativeJpegProcess
import io.screenstream.capture.internal.encoding.NativeSegmentSink
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.FrameworkBitmapCompressionFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.assertJpegDimensions
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Public frame-production integration evidence through the real Coordinator, Capture, Encoding, cache, and Links.
 *
 * Injected compression/native faults and controlled stale returns only arrange failure and recovery boundaries.
 * Queue shape, turn count, private phase, handler identity, and incidental call ordering are not oracles; exact public
 * State/Stats, immutable frame values, current effective parameters, and exact resource settlement decide the tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionFrameProductionTest {
    // Verification: SES-06
    // Verification: FWK-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun frameworkCompressionRejectionDropsPartialPayloadAndRecoversOnFreshFrame() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        FrameworkBitmapCompressionFixture(widthPx = 8, heightPx = 6).use { bitmapFixture ->
            SessionStartHarness(
                bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
                metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
                platformSdkInt = Build.VERSION_CODES.TIRAMISU,
                projectionPlatform = platform.projectionPlatform,
                eglPlatform = platform.eglPlatform,
                glesPlatform = platform.glesPlatform,
                targetPlatform = platform.targetPlatform,
                nativeJpeg = FailFastNativeJpegFacade,
            ).use { harness ->
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(platform.projection, parameters)
                    harness.session.state.value
                }
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val initialActive = start.await() as ScreenCaptureState.Active
                val baselineStats = harness.session.stats.value
                val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
                val registration = harness.session.registerFrameConsumer { frame ->
                    deliveredFrames += copyFrame(frame)
                }

                platform.deliverSourceFrame(rgbaSeed = 29)
                harness.driveUntil { bitmapFixture.compressionAttemptCount >= 1 }
                drainAcceptedSessionWork(harness)

                assertEquals(1, bitmapFixture.compressionAttemptCount)
                assertEquals(initialActive, harness.session.state.value)
                assertTrue(deliveredFrames.isEmpty())
                val rejectedStats = harness.session.stats.value
                assertEquals(baselineStats.encodedFrameCount, rejectedStats.encodedFrameCount)
                assertEquals(baselineStats.producedFrameCount, rejectedStats.producedFrameCount)

                platform.deliverSourceFrame(rgbaSeed = 83)
                harness.driveUntil { deliveredFrames.isNotEmpty() }
                drainAcceptedSessionWork(harness)

                assertEquals(2, bitmapFixture.compressionAttemptCount)
                val delivered = deliveredFrames.single()
                assertArrayEquals(bitmapFixture.successfulJpegBytes, delivered.bytes)
                assertEquals(initialActive.effectiveParameters, delivered.effectiveParameters)
                assertEquals(initialActive, harness.session.state.value)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()
                harness.session.stop()
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Stopped }

                val finalStats = harness.session.stats.value
                assertEquals(baselineStats.encodedFrameCount + 1L, finalStats.encodedFrameCount)
                assertEquals(baselineStats.producedFrameCount + 1L, finalStats.producedFrameCount)
                assertEquals(
                    baselineStats.droppedFrames.byFailure + 1L,
                    finalStats.droppedFrames.byFailure,
                )
            }
        }
    }

    // Verification: P3-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun currentDisplayP3ReadFailsSessionWithExactPublicProblem() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
                harness.session.state.value
            }
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            val initialActive = start.await() as ScreenCaptureState.Active
            val baselineStats = harness.session.stats.value
            val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
            harness.session.registerFrameConsumer { frame -> deliveredFrames += copyFrame(frame) }

            platform.deliverSourceFrame(rgbaSeed = 29, dataSpace = DataSpace.DATASPACE_DISPLAY_P3)
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.UnsupportedColorSpace, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertEquals(initialActive.effectiveParameters, failed.lastEffectiveParameters)
            assertTrue(deliveredFrames.isEmpty())
            val finalStats = harness.session.stats.value
            assertEquals(baselineStats.encodedFrameCount, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
            assertEquals(
                baselineStats.droppedFrames.byFailure + 1L,
                finalStats.droppedFrames.byFailure,
            )

            drainAcceptedSessionWork(harness)
            assertEquals(failed, harness.session.state.value)
            assertTrue(deliveredFrames.isEmpty())
        }
    }

    // Verification: SES-03
    // Verification: SES-06
    // Verification: P3-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun staleDisplayP3ReadIsCleanupOnlyAndFreshSrgbFrameStillPublishes() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.Auto,
        )
        val updatedParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, initialParameters)
                harness.session.state.value
            }
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            start.await() as ScreenCaptureState.Active
            val baselineStats = harness.session.stats.value
            val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
            val registration = harness.session.registerFrameConsumer { frame ->
                deliveredFrames += copyFrame(frame)
            }

            platform.deliverSourceFrame(rgbaSeed = 41, dataSpace = DataSpace.DATASPACE_DISPLAY_P3)
            harness.driveUntil { harness.pendingCaptureTaskCount() > 0 }
            harness.session.updateParameters(updatedParameters)
            check(harness.enterNextCaptureTask())
            harness.driveUntil {
                val state = harness.session.state.value
                state is ScreenCaptureState.Active && state.requestedParameters == updatedParameters
            }
            drainAcceptedSessionWork(harness)

            val updatedActive = harness.session.state.value as ScreenCaptureState.Active
            assertEquals(updatedParameters, updatedActive.requestedParameters)
            assertEquals(updatedParameters, updatedActive.effectiveParameters.appliedParameters)
            assertTrue(deliveredFrames.isEmpty())

            platform.deliverSourceFrame(rgbaSeed = 97, dataSpace = DataSpace.DATASPACE_SRGB)
            harness.driveUntil { deliveredFrames.isNotEmpty() }

            val delivered = deliveredFrames.single()
            assertEquals(updatedActive.effectiveParameters, delivered.effectiveParameters)
            assertJpegDimensions(delivered.bytes, widthPx = 8, heightPx = 6)
            assertEquals(updatedActive, harness.session.state.value)

            val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
            harness.driveUntil { unregister.isCompleted }
            unregister.await()
            harness.session.stop()
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Stopped }

            val finalStats = harness.session.stats.value
            assertEquals(baselineStats.encodedFrameCount + 1L, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount + 1L, finalStats.producedFrameCount)
            assertEquals(
                baselineStats.droppedFrames.byFailure + 1L,
                finalStats.droppedFrames.byFailure,
            )
        }
    }

    // Verification: SES-03
    // Verification: SES-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun safeNativeRejectionReconcilesBeforePublishingLaterFrameworkFrame() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.R,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(platform.projection, parameters)
                    harness.session.state.value
                }
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val initialActive = start.await() as ScreenCaptureState.Active
                val baselineStats = harness.session.stats.value
                val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
                harness.session.registerFrameConsumer { frame ->
                    deliveredFrames += copyFrame(frame)
                }

                platform.deliverSourceFrame(rgbaSeed = 29)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Reconfiguring }

                val reconfiguring = harness.session.state.value as ScreenCaptureState.Reconfiguring
                assertEquals(initialActive.requestedParameters, reconfiguring.requestedParameters)
                assertEquals(initialActive.effectiveParameters, reconfiguring.lastEffectiveParameters)
                assertEquals(initialActive.isCapturedContentVisible, reconfiguring.isCapturedContentVisible)
                assertTrue(deliveredFrames.isEmpty())

                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val reconciledActive = harness.session.state.value as ScreenCaptureState.Active
                assertEquals(initialActive, reconciledActive)
                assertTrue(deliveredFrames.isEmpty())

                platform.deliverSourceFrame(rgbaSeed = 83)
                harness.driveUntil { deliveredFrames.isNotEmpty() }

                assertEquals(1, deliveredFrames.size)
                val delivered = deliveredFrames.single()
                assertEquals(reconciledActive.effectiveParameters, delivered.effectiveParameters)
                assertJpegDimensions(delivered.bytes, widthPx = 8, heightPx = 6)

                harness.session.stop()
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Stopped }

                val finalStats = harness.session.stats.value
                assertEquals(baselineStats.encodedFrameCount + 1L, finalStats.encodedFrameCount)
                assertEquals(
                    baselineStats.droppedFrames.byFailure + 1L,
                    finalStats.droppedFrames.byFailure,
                )
            } finally {
                harness.session.stop()
                if (harness.session.state.value !is ScreenCaptureState.Stopped &&
                    harness.session.state.value !is ScreenCaptureState.Failed
                ) {
                    harness.driveUntil {
                        harness.session.state.value is ScreenCaptureState.Stopped ||
                                harness.session.state.value is ScreenCaptureState.Failed
                    }
                }
                harness.drainWorkerTasks()
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-03
    // Verification: SES-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun staleNativeReadinessChangeInvalidatesCompatibleCacheBeforeFreshCurrentDelivery() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(
            successfulCompressionCountBeforeRejection = 1,
            blockCompression = true,
        )
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.Auto,
        )
        val updatedParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.R,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            val observedStates = CopyOnWriteArrayList<ScreenCaptureState>()
            // Serialized UnconfinedTestDispatcher collection is deterministic test instrumentation for a
            // transient Suspended value only; it is not a public StateFlow publication receipt.
            val stateCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.state.collect { state -> observedStates += state }
            }
            var blockedNativeTask: ControlledNonInlineDispatcher.TaskHandle? = null
            try {
                val start = async(UnconfinedTestDispatcher(testScheduler)) {
                    harness.session.start(platform.projection, initialParameters)
                    harness.session.state.value
                }
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
                val initialActive = start.await() as ScreenCaptureState.Active
                val cachedFrames = CopyOnWriteArrayList<FrameSnapshot>()
                val cachedRegistration = harness.session.registerFrameConsumer { frame ->
                    cachedFrames += copyFrame(frame)
                }

                platform.deliverSourceFrame(rgbaSeed = 41)
                harness.driveUntil { cachedFrames.isNotEmpty() }
                val cachedFrame = cachedFrames.single()
                val cachedUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                    cachedRegistration.unregister()
                }
                harness.driveUntil { cachedUnregister.isCompleted }
                cachedUnregister.await()

                platform.deliverSourceFrame(rgbaSeed = 67)
                blockedNativeTask = enterWorkUntilNativeCompressionBlocks(harness, nativeJpeg)

                harness.session.updateParameters(updatedParameters)
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Reconfiguring }

                val reconfiguring = harness.session.state.value as ScreenCaptureState.Reconfiguring
                assertEquals(updatedParameters, reconfiguring.requestedParameters)
                assertEquals(initialActive.effectiveParameters, reconfiguring.lastEffectiveParameters)

                nativeJpeg.releaseCompression()
                nativeJpeg.awaitCompressionReturned()
                blockedNativeTask.awaitSuccessfulCompletion()
                harness.driveUntil {
                    val state = harness.session.state.value
                    (state is ScreenCaptureState.Active) &&
                            (state.effectiveParameters.appliedParameters == updatedParameters)
                }

                val updatedActive = harness.session.state.value as ScreenCaptureState.Active
                assertEquals(updatedParameters, updatedActive.requestedParameters)
                assertEquals(updatedParameters, updatedActive.effectiveParameters.appliedParameters)
                assertTrue(observedStates.none { state -> state is ScreenCaptureState.Suspended })

                val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
                val currentRegistration = harness.session.registerFrameConsumer { frame ->
                    deliveredFrames += copyFrame(frame)
                }
                platform.deliverSourceFrame(rgbaSeed = 97)
                harness.driveUntil { deliveredFrames.isNotEmpty() }

                assertEquals(1, deliveredFrames.size)
                val delivered = deliveredFrames.single()
                assertFalse(cachedFrame.bytes.contentEquals(delivered.bytes))
                assertTrue(delivered.sequence > cachedFrame.sequence)
                assertEquals(updatedActive.effectiveParameters, delivered.effectiveParameters)
                assertJpegDimensions(delivered.bytes, widthPx = 8, heightPx = 6)

                val currentUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                    currentRegistration.unregister()
                }
                harness.driveUntil { currentUnregister.isCompleted }
                currentUnregister.await()

                harness.session.stop()
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Stopped }

                val finalStats = harness.session.stats.value
                assertEquals(2L, finalStats.encodedFrameCount)
                assertEquals(2L, finalStats.producedFrameCount)
                assertEquals(1L, finalStats.droppedFrames.byFailure)
            } finally {
                nativeJpeg.releaseCompression()
                blockedNativeTask?.awaitCompletion()
                stateCollector.cancelAndJoin()
                harness.session.stop()
                if (harness.session.state.value !is ScreenCaptureState.Stopped &&
                    harness.session.state.value !is ScreenCaptureState.Failed
                ) {
                    harness.driveUntil {
                        harness.session.state.value is ScreenCaptureState.Stopped ||
                                harness.session.state.value is ScreenCaptureState.Failed
                    }
                }
                harness.drainWorkerTasks()
                nativeJpeg.close()
            }
        }
    }

    private fun enterWorkUntilNativeCompressionBlocks(
        harness: SessionStartHarness,
        nativeJpeg: SafeRejectingNativeJpegFacade,
    ): ControlledNonInlineDispatcher.TaskHandle {
        repeat(32) {
            harness.enterNextControlTask()
            harness.enterNextCaptureTask()
            val workerTask = harness.enterNextWorker() ?: return@repeat
            check(workerTask.awaitEntered()) { "Native production task did not enter" }
            nativeJpeg.awaitCompressionEntered()
            return workerTask
        }
        error("Controlled Session work did not reach Native compression")
    }

    private object FailFastNativeJpegFacade : NativeJpegFacade {
        override fun resolveAvailability(): NativeJpegProcess.Availability = failNativeAccess("resolveAvailability")
        override fun hasWeakCompressor(): Boolean = failNativeAccess("hasWeakCompressor")
        override fun newResultBlock(): ByteBuffer = failNativeAccess("newResultBlock")
        override fun allocateCarrier(carrierByteCount: Long): ByteBuffer = failNativeAccess("allocateCarrier")
        override fun freeCarrier(carrierBuffer: ByteBuffer): Unit = failNativeAccess("freeCarrier")

        override fun compress(
            carrierBuffer: ByteBuffer,
            pixelByteCount: Long,
            width: Int,
            height: Int,
            stride: Int,
            quality: Int,
            sink: NativeSegmentSink,
            resultBlock: ByteBuffer,
        ): Unit = failNativeAccess("compress")

        private fun failNativeAccess(method: String): Nothing =
            throw AssertionError("FrameworkOnly crossed NativeJpegFacade.$method")
    }

}
