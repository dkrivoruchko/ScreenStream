package io.screenstream.capture

import android.os.Build
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.screenstream.capture.internal.delivery.DeliveryOffer
import io.screenstream.capture.internal.delivery.DeliveryOwner
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.assertJpegDimensions
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.stopAndDrainSession
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.nanoseconds

/*
 * API 34 resize-currentness evidence through the real Coordinator, Capture, Encoding, Storage, and Delivery owners.
 * Latches and explicit task entry only arrange the accepted intervals. Public output identity and Stats, exact native
 * carrier effects, current replacement Target readback, and actual outer task return decide the scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionResizeCurrentnessTest {
    // Verification: SES-03
    // Verification: SES-06
    // Audit item: P5-G01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun admittedOldCallbackRemainsImmutableAcrossResizeAdoptionThenReplacementProducesCurrentOutput() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val callbackEntered = CountDownLatch(1)
        val callbackMayReturn = CountDownLatch(1)
        val callbackCount = AtomicInteger()
        val firstBorrow = AtomicReference<EncodedImageFrame?>()
        val firstBeforeAdoption = AtomicReference<FrameSnapshot?>()
        val firstAfterAdoption = AtomicReference<FrameSnapshot?>()
        val firstCallbackThread = AtomicReference<Thread?>()
        val delivered = CopyOnWriteArrayList<FrameSnapshot>()
        val offerPauseArmed = AtomicBoolean(false)
        val harnessReference = AtomicReference<SessionStartHarness?>()
        val callbackTaskReference = AtomicReference<ControlledNonInlineDispatcher.TaskHandle?>()
        var harness: SessionStartHarness? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null

        fun preserveCleanupFailure(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                val primary = primaryFailure
                if (primary != null) {
                    if (failure !== primary) primary.addSuppressed(failure)
                } else {
                    val previousCleanup = cleanupFailure
                    if (previousCleanup == null) {
                        cleanupFailure = failure
                    } else if (failure !== previousCleanup) {
                        previousCleanup.addSuppressed(failure)
                    }
                }
            }
        }

        mockkConstructor(DeliveryOwner::class)
        try {
            every {
                anyConstructed<DeliveryOwner>().offer(any(), any(), any(), any())
            } answers {
                val result = callOriginal()
                if (offerPauseArmed.compareAndSet(true, false)) {
                    assertTrue("The real old-frame offer must be accepted", result is DeliveryOffer.Accepted)
                    val exactHarness = harnessReference.get()
                        ?: throw AssertionError("The Session harness was not installed before Delivery offer")
                    val callbackTask = exactHarness.enterNextWorker()
                        ?: throw AssertionError("The accepted old-frame callback task was missing")
                    assertTrue(
                        "The old-frame callback TaskHandle must be retained exactly once",
                        callbackTaskReference.compareAndSet(null, callbackTask),
                    )
                    assertTrue("Old delivery worker did not enter", callbackTask.awaitEntered())
                    assertTrue("Old callback did not take its pre-adoption snapshot", callbackEntered.await(5L, TimeUnit.SECONDS))
                }
                result
            }

            val exactHarness = SessionStartHarness(
                bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
                metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
                platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                projection = platform.projection,
                projectionPlatform = platform.projectionPlatform,
                eglPlatform = platform.eglPlatform,
                glesPlatform = platform.glesPlatform,
                targetPlatform = platform.targetPlatform,
            )
            harness = exactHarness
            harnessReference.set(exactHarness)
            try {
                val initialActive = startWithAuthoritativeResize(exactHarness, platform, parameters, widthPx = 8, heightPx = 6)
                exactHarness.session.registerFrameConsumer { frame ->
                    when (callbackCount.incrementAndGet()) {
                        1 -> {
                            firstBorrow.set(frame)
                            firstCallbackThread.set(Thread.currentThread())
                            val stateBeforeAdoption = exactHarness.session.state.value
                            assertTrue(stateBeforeAdoption is ScreenCaptureState.Active)
                            assertEquals(
                                initialActive.effectiveParameters,
                                (stateBeforeAdoption as ScreenCaptureState.Active).effectiveParameters,
                            )
                            firstBeforeAdoption.set(copyFrame(frame))
                            callbackEntered.countDown()
                            check(callbackMayReturn.await(5L, TimeUnit.SECONDS)) { "Old callback was not released" }
                            firstAfterAdoption.set(copyFrame(frame))
                            delivered += copyFrame(frame)
                        }

                        2 -> {
                            assertSame(firstCallbackThread.get(), Thread.currentThread())
                            assertThrows(IllegalStateException::class.java) {
                                checkNotNull(firstBorrow.get()).toByteArray()
                            }
                            delivered += copyFrame(frame)
                        }

                        else -> error("Unexpected callback entry")
                    }
                }

                exactHarness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 17)
                check(exactHarness.enterNextControlTask())
                check(exactHarness.enterNextCaptureTask())
                check(exactHarness.enterNextControlTask())
                checkNotNull(exactHarness.enterNextWorker()).awaitSuccessfulCompletion()

                platform.deliverCapturedContentResize(widthPx = 6, heightPx = 4)
                offerPauseArmed.set(true)
                check(exactHarness.enterNextControlTask())
                assertFalse("The old-frame Delivery offer interception was not consumed", offerPauseArmed.get())
                assertTrue(exactHarness.session.state.value is ScreenCaptureState.Reconfiguring)
                val callbackTask = callbackTaskReference.get()
                    ?: throw AssertionError("The old-frame callback TaskHandle was not retained")
                try {
                    assertNull(firstAfterAdoption.get())
                    assertEquals(initialActive.effectiveParameters, checkNotNull(firstBeforeAdoption.get()).effectiveParameters)
                } finally {
                    callbackMayReturn.countDown()
                    callbackTask.awaitSuccessfulCompletion()
                }
                check(exactHarness.enterNextControlTask())
                exactHarness.driveUntil {
                    val state = exactHarness.session.state.value
                    state is ScreenCaptureState.Active &&
                            state.effectiveParameters.captureGeometry.widthPx == 6 &&
                            state.effectiveParameters.captureGeometry.heightPx == 4
                }

                val before = checkNotNull(firstBeforeAdoption.get())
                val after = checkNotNull(firstAfterAdoption.get())
                assertArrayEquals(before.bytes, after.bytes)
                assertEquals(before.sequence, after.sequence)
                assertEquals(before.timestampElapsedRealtimeNanos, after.timestampElapsedRealtimeNanos)
                assertEquals(before.effectiveParameters, after.effectiveParameters)
                assertJpegDimensions(after.bytes, widthPx = 8, heightPx = 6)

                exactHarness.clock.setDefaultNanos(2_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 73)
                exactHarness.driveUntil { delivered.size == 2 }

                val replacement = delivered.last()
                assertTrue(replacement.sequence > after.sequence)
                assertTrue(replacement.timestampElapsedRealtimeNanos > after.timestampElapsedRealtimeNanos)
                assertEquals(6, replacement.effectiveParameters.captureGeometry.widthPx)
                assertEquals(4, replacement.effectiveParameters.captureGeometry.heightPx)
                assertEquals(6, replacement.effectiveParameters.finalImageSize.widthPx)
                assertEquals(4, replacement.effectiveParameters.finalImageSize.heightPx)
                assertJpegDimensions(replacement.bytes, widthPx = 6, heightPx = 4)
                assertFalse(after.bytes.contentEquals(replacement.bytes))
                assertEquals(2, platform.sourceUpdateCount())
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            }
        } catch (failure: Throwable) {
            if (primaryFailure == null) primaryFailure = failure
            throw failure
        } finally {
            callbackMayReturn.countDown()
            preserveCleanupFailure {
                callbackTaskReference.get()?.awaitSuccessfulCompletion()
            }
            preserveCleanupFailure { harness?.let(::stopAndDrainSession) }
            preserveCleanupFailure { harness?.close() }
            preserveCleanupFailure { unmockkConstructor(DeliveryOwner::class) }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    // Verification: SES-03
    // Verification: SES-06
    // Verification: SES-07
    // Audit item: P5-G01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun enteredOldReadSettlesStaleAcrossAdoptionBeforeReplacementReadsAndPublishes() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 1)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            val delivered = CopyOnWriteArrayList<FrameSnapshot>()
            val enteredReadView = AtomicReference<ByteBuffer?>()
            try {
                startWithAuthoritativeResize(harness, platform, parameters, widthPx = 8, heightPx = 6)
                harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }
                val baselineStats = harness.session.stats.value
                val oldCarrier = checkNotNull(nativeJpeg.carrierSnapshot().allocatedCarrier)

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 31)
                check(harness.enterNextControlTask())
                val oldRead = checkNotNull(harness.claimNextCaptureTask())
                harness.clock.enqueueValue(1_100_000_000L)
                platform.runOnceDuringNextReadback { view ->
                    enteredReadView.set(view)
                    assertEquals(8 * 6 * 4, view.capacity())

                    platform.deliverCapturedContentResize(widthPx = 6, heightPx = 4)
                    check(harness.enterNextControlTask())

                    assertTrue(harness.session.state.value is ScreenCaptureState.Reconfiguring)
                    assertTrue(delivered.isEmpty())
                    assertEquals(0, nativeJpeg.carrierSnapshot().compressionCount)
                    assertEquals(1, nativeJpeg.carrierSnapshot().outstandingCount)
                    assertSame(oldCarrier, nativeJpeg.carrierSnapshot().allocatedCarrier)
                    platform.verifyNoProjectionTopologyChanges()
                    harness.clock.enqueueValue(1_100_000_123L)
                }

                oldRead.run()
                assertEquals(8 * 6 * 4, checkNotNull(enteredReadView.get()).capacity())
                check(harness.enterNextControlTask())
                harness.driveUntil {
                    val state = harness.session.state.value
                    state is ScreenCaptureState.Active &&
                            state.effectiveParameters.captureGeometry.widthPx == 6 &&
                            state.effectiveParameters.captureGeometry.heightPx == 4 &&
                            harness.session.stats.value.droppedFrames.byStaleWork ==
                            baselineStats.droppedFrames.byStaleWork + 1L
                }

                val settledStats = harness.session.stats.value
                val replacementCarrier = nativeJpeg.carrierSnapshot()
                assertEquals(baselineStats.encodedFrameCount, settledStats.encodedFrameCount)
                assertEquals(baselineStats.producedFrameCount, settledStats.producedFrameCount)
                assertEquals(baselineStats.droppedFrames.byStaleWork + 1L, settledStats.droppedFrames.byStaleWork)
                assertEquals(123.nanoseconds, settledStats.averageReadbackDuration)
                assertTrue(delivered.isEmpty())
                assertEquals(0, replacementCarrier.compressionCount)
                assertEquals(2, replacementCarrier.allocationCount)
                assertEquals(1, replacementCarrier.outstandingCount)
                assertEquals(1, replacementCarrier.freeAttemptCount)
                assertEquals(1, replacementCarrier.freedCount)
                assertSame(oldCarrier, replacementCarrier.freeAttemptCarrier)
                assertSame(oldCarrier, replacementCarrier.freedCarrier)
                platform.verifyAuthoritativeResizeBoundaries(widthPx = 6, heightPx = 4, densityDpi = 320)

                harness.clock.setDefaultNanos(2_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 89)
                harness.driveUntil { delivered.size == 1 }

                val replacement = delivered.single()
                assertEquals(6, replacement.effectiveParameters.captureGeometry.widthPx)
                assertEquals(4, replacement.effectiveParameters.captureGeometry.heightPx)
                assertEquals(6, replacement.effectiveParameters.finalImageSize.widthPx)
                assertEquals(4, replacement.effectiveParameters.finalImageSize.heightPx)
                assertTrue(replacement.sequence > 0L)
                assertEquals(2_000_000_000L, replacement.timestampElapsedRealtimeNanos)
                assertArrayEquals(
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x53, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte()),
                    replacement.bytes,
                )
                assertEquals(1, nativeJpeg.carrierSnapshot().compressionCount)
                harness.clock.setDefaultNanos(3_000_000_000L)
                check(harness.enterNextControlTask())
                assertEquals(baselineStats.droppedFrames.byStaleWork + 1L, harness.session.stats.value.droppedFrames.byStaleWork)
                assertEquals(baselineStats.encodedFrameCount + 1L, harness.session.stats.value.encodedFrameCount)
                assertEquals(baselineStats.producedFrameCount + 1L, harness.session.stats.value.producedFrameCount)
                assertEquals(2, platform.sourceUpdateCount())
            } finally {
                stopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun kotlinx.coroutines.test.TestScope.startWithAuthoritativeResize(
        harness: SessionStartHarness,
        platform: HappyCapturePlatform,
        parameters: ScreenCaptureParameters,
        widthPx: Int,
        heightPx: Int,
    ): ScreenCaptureState.Active {
        val start = async(UnconfinedTestDispatcher(testScheduler)) {
            harness.session.start(parameters)
        }
        harness.driveUntil(platform::initialVirtualDisplayReturned)
        platform.deliverCapturedContentResize(widthPx, heightPx)
        harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
        start.await()
        return harness.session.state.value as ScreenCaptureState.Active
    }
}
