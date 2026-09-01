package io.screenstream.capture

import android.os.Build
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.stopAndDrainSession
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/*
 * Public production and repeat composition evidence through the real Coordinator and owner graph.
 * Explicit delayed-Control entry and non-inline worker entry arrange boundaries; public State, Stats, immutable
 * frames, and maintained platform/facade effects decide the tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionProductionIntegrationTest {
    // Verification: SES-05
    // Verification: SES-06
    // Audit item: P3-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun deferredSecondMaxFpsFreshGrantPublishesAtExplicitDelayedControlEntry() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 2)
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.MaxFps(10),
        )
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionStartHarness.DelayedControlOutcome.Accept,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, platform, parameters)
                val active = harness.session.state.value as ScreenCaptureState.Active
                val delivered = CopyOnWriteArrayList<FrameSnapshot>()
                val registration = harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 17)
                harness.driveUntil { delivered.size == 1 }
                val first = delivered.single()

                harness.clock.setDefaultNanos(1_050_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 41)
                check(harness.enterNextControlTask())
                assertEquals(1, delivered.size)
                assertEquals(1, platform.sourceUpdateCount())
                assertEquals(1, nativeJpeg.carrierSnapshot().compressionCount)

                harness.clock.setDefaultNanos(2_000_000_000L)
                check(harness.enterNextDelayedControlTask())
                harness.driveUntil { delivered.size == 2 }
                val second = delivered.last()

                assertTrue(second.sequence > first.sequence)
                assertTrue(second.timestampElapsedRealtimeNanos > first.timestampElapsedRealtimeNanos)
                assertEquals(active.effectiveParameters, second.effectiveParameters)
                assertEquals(2, platform.sourceUpdateCount())
                assertEquals(2, nativeJpeg.carrierSnapshot().compressionCount)
                assertEquals(2L, harness.session.stats.value.encodedFrameCount)
                assertEquals(2L, harness.session.stats.value.producedFrameCount)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()
            } finally {
                stopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-06
    // Verification: DEL-02
    // Audit item: P3-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun repeatReusesPayloadWithNewPublicIdentityWithoutReadOrEncode() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 1)
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRepeatInterval = 1.seconds,
        )
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionStartHarness.DelayedControlOutcome.Accept,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, platform, parameters)
                val active = harness.session.state.value as ScreenCaptureState.Active
                val delivered = CopyOnWriteArrayList<FrameSnapshot>()
                val registration = harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 23)
                harness.driveUntil { delivered.size == 1 }
                val original = delivered.single()
                val statsBeforeRepeat = harness.session.stats.value
                val readsBeforeRepeat = platform.sourceUpdateCount()
                val encodesBeforeRepeat = nativeJpeg.carrierSnapshot().compressionCount

                harness.clock.setDefaultNanos(2_000_000_000L)
                check(harness.enterNextDelayedControlTask())
                harness.driveUntil { delivered.size == 2 }
                val repeated = delivered.last()
                val statsAfterRepeat = harness.session.stats.value

                assertArrayEquals(original.bytes, repeated.bytes)
                assertEquals(original.sequence + 1L, repeated.sequence)
                assertEquals(2_000_000_000L, repeated.timestampElapsedRealtimeNanos)
                assertTrue(repeated.timestampElapsedRealtimeNanos > 0L)
                assertEquals(active.effectiveParameters, repeated.effectiveParameters)
                assertEquals(statsBeforeRepeat.producedFrameCount + 1L, statsAfterRepeat.producedFrameCount)
                assertEquals(statsBeforeRepeat.encodedFrameCount, statsAfterRepeat.encodedFrameCount)
                assertEquals(readsBeforeRepeat, platform.sourceUpdateCount())
                assertEquals(encodesBeforeRepeat, nativeJpeg.carrierSnapshot().compressionCount)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()
            } finally {
                stopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-05
    // Audit item: P3-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rejectedDelayedPacingPostFailsWithInternalFailure() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 2)
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.MaxFps(10),
        )
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionStartHarness.DelayedControlOutcome.Reject,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, platform, parameters)
                val delivered = CopyOnWriteArrayList<FrameSnapshot>()
                harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 29)
                harness.driveUntil { delivered.size == 1 }

                harness.clock.setDefaultNanos(1_050_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 53)
                harness.driveUntil { harness.session.state.value is ScreenCaptureState.Failed }

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertEquals(parameters, failed.requestedParameters)
                assertEquals(1, delivered.size)
            } finally {
                drainAcceptedSessionWork(harness)
                nativeJpeg.close()
            }
        }
    }

    // Audit item: P3-03
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun successfulEncodeSettledAfterRevisionChangeIsCountedOnlyAsStaleWork() = runTest {
        val platform = HappyCapturePlatform()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 1)
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val updatedParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, platform, initialParameters)
                val baselineStats = harness.session.stats.value
                val delivered = CopyOnWriteArrayList<FrameSnapshot>()
                harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }
                harness.clock.setDefaultNanos(1_000_000_000L)

                platform.deliverSourceFrame(rgbaSeed = 31)
                check(harness.enterNextControlTask())
                check(harness.enterNextCaptureTask())
                check(harness.enterNextControlTask())
                val encodingTask = checkNotNull(harness.enterNextWorker())
                encodingTask.awaitSuccessfulCompletion()

                harness.session.updateParameters(updatedParameters)
                harness.driveUntil {
                    val state = harness.session.state.value
                    state is ScreenCaptureState.Active && state.requestedParameters == updatedParameters
                }
                val current = harness.session.state.value as ScreenCaptureState.Active
                val finalStats = harness.session.stats.value

                assertEquals(updatedParameters, current.effectiveParameters.appliedParameters)
                assertEquals(baselineStats.encodedFrameCount + 1L, finalStats.encodedFrameCount)
                assertEquals(7, finalStats.lastEncodedByteCount)
                assertEquals(baselineStats.droppedFrames.byStaleWork + 1L, finalStats.droppedFrames.byStaleWork)
                assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
                assertTrue(delivered.isEmpty())
            } finally {
                stopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }
}
