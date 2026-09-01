package io.screenstream.capture

import android.os.Build
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.assertJpegDimensions
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/*
 * Public live-parameter-update and cache-compatibility evidence through the real Coordinator, owners, cache, and
 * Links.
 *
 * Accepted non-inline work and the one-shot Control-post action only arrange update convergence or update/terminal
 * contention. Queue shape, turn count, private phase, handler identity, and incidental platform-call ordering are
 * not oracles; public State, immutable frame identity/effective parameters, and maintained target effects decide
 * these scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionParameterUpdateTest {
    // Verification: UPD-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun admittedUnequalUpdateStoppedAtFirstUnlockedEffectFreezesNewestRequestWithoutOrdinarySuccessor() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val updatedParameters = initialParameters.copy(outputSize = OutputSize.ScaleFactor(0.5))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.S_V2,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, platform, initialParameters)
            val initialActive = harness.session.state.value as ScreenCaptureState.Active
            drainAcceptedSessionWork(harness)

            val raceArranged = AtomicBoolean(false)
            try {
                harness.runBeforeNextControlPost {
                    check(raceArranged.compareAndSet(false, true))
                    harness.session.stop()
                }

                harness.session.updateParameters(updatedParameters)

                assertTrue("The terminal contender was not inserted at the outward Control post", raceArranged.get())
                assertEquals(initialActive, harness.session.state.value)
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }
                val stopped = harness.session.state.value as ScreenCaptureState.Stopped
                drainAcceptedSessionWork(harness)

                assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
                assertEquals(updatedParameters, stopped.requestedParameters)
                assertEquals(initialActive.effectiveParameters, stopped.lastEffectiveParameters)
                assertEquals(stopped, harness.session.state.value)
                platform.verifyNoReplacementTargetWasCreated()
            } finally {
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UPD-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun imageAffectingUpdatePublishesNewestReconfiguringBeforeFirstEncodingEffect() = runTest {
        val platform = HappyCapturePlatform()
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            jpegQuality = 80,
        )
        val updatedParameters = initialParameters.copy(jpegQuality = 35)

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            try {
                startActiveSession(harness, platform, initialParameters)
                val initialActive = harness.session.state.value as ScreenCaptureState.Active
                drainAcceptedSessionWork(harness)

                harness.session.updateParameters(updatedParameters)
                check(harness.enterNextControlTask()) { "The admitted update did not schedule its Control turn" }

                val reconfiguring = ScreenCaptureState.Reconfiguring.create(
                    requestedParameters = updatedParameters,
                    lastEffectiveParameters = initialActive.effectiveParameters,
                    isCapturedContentVisible = initialActive.isCapturedContentVisible,
                )
                assertEquals(reconfiguring, harness.session.state.value)
                assertEquals(reconfiguring, harness.workerSubmissionStates().last())

                harness.driveUntil {
                    val state = harness.session.state.value
                    (state is ScreenCaptureState.Active) &&
                            (state.effectiveParameters.appliedParameters == updatedParameters)
                }
                platform.verifyNoProjectionTopologyChanges()
                platform.verifyNoReplacementTargetWasCreated()
            } finally {
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: SES-06
    // Verification: STO-01
    // Audit item: P3-05
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun pacingOnlyUpdatePreservesCachedFirstFrameForLateConsumer() = runTest {
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val initialParameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.MaxFps(30),
        )
        val updatedParameters = initialParameters.copy(frameRate = FrameRate.MaxFps(15))
        val platform = HappyCapturePlatform()

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.N,
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
            val statsBeforeProduction = harness.session.stats.value
            val productionNanos = 1_250_000_000L
            harness.clock.setDefaultNanos(productionNanos)

            val originalFrame = AtomicReference<FrameSnapshot?>()
            val originalRegistration = harness.session.registerFrameConsumer { frame ->
                check(originalFrame.compareAndSet(null, copyFrame(frame)))
            }
            platform.deliverSourceFrame(rgbaSeed = 11)
            harness.driveUntil { originalFrame.get() != null }
            val original = checkNotNull(originalFrame.get())
            val statsAfterProduction = harness.session.stats.value

            val originalUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                originalRegistration.unregister()
            }
            harness.driveUntil { originalUnregister.isCompleted }
            originalUnregister.await()

            harness.session.updateParameters(updatedParameters)
            harness.driveUntil {
                val state = harness.session.state.value
                (state is ScreenCaptureState.Active) &&
                        (state.effectiveParameters.appliedParameters == updatedParameters)
            }
            val currentActive = harness.session.state.value as ScreenCaptureState.Active
            harness.clock.setDefaultNanos(productionNanos + 1_000_000_000L)

            val cachedFrame = AtomicReference<FrameSnapshot?>()
            val cachedRegistration = harness.session.registerFrameConsumer { frame ->
                check(cachedFrame.compareAndSet(null, copyFrame(frame)))
            }
            harness.driveUntil { cachedFrame.get() != null }
            val cached = checkNotNull(cachedFrame.get())

            val cachedUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                cachedRegistration.unregister()
            }
            harness.driveUntil { cachedUnregister.isCompleted }
            cachedUnregister.await()

            assertEquals(updatedParameters, currentActive.requestedParameters)
            assertEquals(updatedParameters, currentActive.effectiveParameters.appliedParameters)
            assertEquals(initialParameters, original.effectiveParameters.appliedParameters)
            assertEquals(productionNanos, original.timestampElapsedRealtimeNanos)
            assertArrayEquals(original.bytes, cached.bytes)
            assertEquals(original.sequence, cached.sequence)
            assertEquals(original.timestampElapsedRealtimeNanos, cached.timestampElapsedRealtimeNanos)
            assertEquals(original.effectiveParameters, cached.effectiveParameters)
            assertEquals(statsBeforeProduction.encodedFrameCount + 1L, statsAfterProduction.encodedFrameCount)
            assertEquals(statsBeforeProduction.producedFrameCount + 1L, statsAfterProduction.producedFrameCount)
            assertEquals(statsAfterProduction, harness.session.stats.value)
            assertJpegDimensions(original.bytes, widthPx = 8, heightPx = 6)
        }
    }

    // Verification: SES-03
    // Verification: SES-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rawEquivalentUpdateSkipsOldCacheAndDeliversFreshCurrentMetadata() = runTest {
        val metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320)
        val initialParameters = ScreenCaptureParameters(
            crop = CropInsetsPx(left = 4, top = 0, right = 0, bottom = 0),
            outputSize = OutputSize.TargetSize(4, 6, OutputSize.ContentMode.Stretch),
        )
        val updatedParameters = ScreenCaptureParameters(
            sourceRegion = SourceRegion.RightHalf,
            outputSize = OutputSize.TargetSize(4, 6, OutputSize.ContentMode.Stretch),
        )
        val platform = HappyCapturePlatform()

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = metrics,
            platformSdkInt = Build.VERSION_CODES.N,
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

            val originalFrame = AtomicReference<FrameSnapshot?>()
            val originalRegistration = harness.session.registerFrameConsumer { frame ->
                check(originalFrame.compareAndSet(null, copyFrame(frame)))
            }
            platform.deliverSourceFrame(rgbaSeed = 23)
            harness.driveUntil { originalFrame.get() != null }
            val original = checkNotNull(originalFrame.get())

            val originalUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                originalRegistration.unregister()
            }
            harness.driveUntil { originalUnregister.isCompleted }
            originalUnregister.await()

            harness.session.updateParameters(updatedParameters)
            harness.driveUntil {
                val state = harness.session.state.value
                (state is ScreenCaptureState.Active) &&
                        (state.effectiveParameters.appliedParameters == updatedParameters)
            }
            val currentActive = harness.session.state.value as ScreenCaptureState.Active

            val deliveredFrames = CopyOnWriteArrayList<FrameSnapshot>()
            val currentRegistration = harness.session.registerFrameConsumer { frame ->
                deliveredFrames += copyFrame(frame)
            }
            platform.deliverSourceFrame(rgbaSeed = 91)
            harness.driveUntil { deliveredFrames.isNotEmpty() }

            val currentUnregister = async(UnconfinedTestDispatcher(testScheduler)) {
                currentRegistration.unregister()
            }
            harness.driveUntil { currentUnregister.isCompleted }
            currentUnregister.await()

            assertEquals(1, deliveredFrames.size)
            val firstDelivered = deliveredFrames.single()
            assertEquals(updatedParameters, currentActive.requestedParameters)
            assertEquals(updatedParameters, currentActive.effectiveParameters.appliedParameters)
            assertEquals(updatedParameters, firstDelivered.effectiveParameters.appliedParameters)
            assertEquals(currentActive.effectiveParameters, firstDelivered.effectiveParameters)
            assertEquals(original.effectiveParameters.appliedSourceRect, firstDelivered.effectiveParameters.appliedSourceRect)
            assertTrue(firstDelivered.sequence > original.sequence)
            assertJpegDimensions(firstDelivered.bytes, widthPx = 4, heightPx = 6)
            platform.verifyNoProjectionTopologyChanges()
            platform.verifyNoReplacementTargetWasCreated()
        }
    }

}
