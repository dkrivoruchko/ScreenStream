package io.screenstream.capture

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.NativeCarrierSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.primeCachedFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.stopAndDrainSession
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * Public active-session terminal-fence evidence through the real Coordinator, Capture, Delivery, cache, and Links.
 *
 * Entered reads, retained accepted callback tasks, latches, and controlled task entry only arrange work outstanding
 * at terminal claim. Public terminal State/Stats, real callback exclusion, and exact late carrier settlement decide
 * these scenarios. The exact Control-post count is an oracle only where TERM-01 names the absence of a late ordinary
 * wake; it never proves queue shape, task identity, or execution progress.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionTerminalFenceTest {
    // Verification: TERM-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun requestedTerminalDetachesEnteredCaptureReadAndLateFilledReturnOnlyFreesItsNativeCarrier() = runTest {
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
            val readbackEntered = CountDownLatch(1)
            val stopReturned = CountDownLatch(1)
            val stopFailure = AtomicReference<Throwable?>()
            val stoppedState = AtomicReference<ScreenCaptureState?>()
            val stoppedStats = AtomicReference<ScreenCaptureStats?>()
            val carrierAtFreeze = AtomicReference<NativeCarrierSnapshot?>()
            val controlPostsAtFreeze = AtomicInteger(-1)
            val deliveredFrames = AtomicInteger()
            val stopper = Thread({
                try {
                    check(readbackEntered.await(5L, TimeUnit.SECONDS)) {
                        "Capture readback did not enter before the bounded stop wait"
                    }
                    harness.session.stop()
                } catch (failure: Throwable) {
                    stopFailure.compareAndSet(null, failure)
                } finally {
                    stopReturned.countDown()
                }
            }, "ScreenCaptureEngine-Terminal-Stopper")

            try {
                startActiveSession(harness, platform, parameters)
                val carrierBeforeRead = nativeJpeg.carrierSnapshot()
                assertEquals(1, carrierBeforeRead.allocationCount)
                assertEquals(1, carrierBeforeRead.outstandingCount)
                assertEquals(0, carrierBeforeRead.freeAttemptCount)
                assertEquals(0, carrierBeforeRead.compressionCount)
                assertEquals(0, carrierBeforeRead.resultBlockCount)

                harness.session.registerFrameConsumer {
                    deliveredFrames.incrementAndGet()
                }
                check(harness.enterNextControlTask()) { "Consumer registration was not offered to Control" }

                platform.runOnceDuringNextReadback { destination ->
                    assertSame(carrierBeforeRead.allocatedCarrier, destination)
                    readbackEntered.countDown()
                    check(stopReturned.await(5L, TimeUnit.SECONDS)) {
                        "Public stop did not return while Capture readback was entered"
                    }
                    stopFailure.get()?.let { failure ->
                        throw AssertionError("Public stop failed while Capture readback was entered", failure)
                    }
                    repeat(32) {
                        if (harness.session.state.value !is ScreenCaptureState.Stopped) {
                            harness.enterNextControlTask()
                        }
                    }
                    val stopped = harness.session.state.value as? ScreenCaptureState.Stopped
                        ?: error("Control did not reach public Stopped while Capture readback was entered")
                    stoppedState.set(stopped)
                    stoppedStats.set(harness.session.stats.value)
                    carrierAtFreeze.set(nativeJpeg.carrierSnapshot())
                    controlPostsAtFreeze.set(harness.controlPostCount())
                }

                stopper.start()
                platform.deliverSourceFrame(rgbaSeed = 61, dataSpace = DataSpace.DATASPACE_SRGB)
                var enteredReadTask = false
                repeat(32) {
                    if (!enteredReadTask) {
                        harness.enterNextControlTask()
                        enteredReadTask = harness.enterNextCaptureTask()
                    }
                }
                assertTrue("Accepted Capture readback work did not enter", enteredReadTask)
                stopper.join(5_000L)
                assertFalse("Terminal stopper did not return", stopper.isAlive)
                stopFailure.get()?.let { failure ->
                    throw AssertionError("Terminal stopper failed", failure)
                }

                val frozenState = stoppedState.get() as? ScreenCaptureState.Stopped
                    ?: error("Terminal State was not captured before readback returned")
                val frozenStats = checkNotNull(stoppedStats.get())
                val frozenCarrier = checkNotNull(carrierAtFreeze.get())
                val frozenControlPosts = controlPostsAtFreeze.get()
                assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)
                assertEquals(1, frozenCarrier.allocationCount)
                assertEquals(1, frozenCarrier.outstandingCount)
                assertEquals(0, frozenCarrier.freeAttemptCount)
                assertEquals(0, frozenCarrier.compressionCount)
                assertEquals(0, frozenCarrier.resultBlockCount)
                assertEquals(frozenControlPosts, harness.controlPostCount())

                drainAcceptedSessionWork(harness)

                val retiredCarrier = nativeJpeg.carrierSnapshot()
                assertEquals(1, retiredCarrier.allocationCount)
                assertEquals(0, retiredCarrier.outstandingCount)
                assertEquals(1, retiredCarrier.freeAttemptCount)
                assertEquals(1, retiredCarrier.freedCount)
                assertSame(retiredCarrier.allocatedCarrier, retiredCarrier.freedCarrier)
                assertSame(retiredCarrier.allocatedCarrier, retiredCarrier.freeAttemptCarrier)
                assertEquals(0, retiredCarrier.compressionCount)
                assertEquals(0, retiredCarrier.resultBlockCount)
                assertEquals(0, deliveredFrames.get())
                assertEquals(frozenControlPosts, harness.controlPostCount())
                assertEquals(frozenState, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                readbackEntered.countDown()
                stopper.join(5_000L)
                stopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-02
    // Verification: DEL-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun terminalStopFencesAcceptedCallbackBeforeEntryAndFreezesPublicStateAndStats() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(platform.projection, parameters)
            }
            harness.driveUntil { harness.session.state.value is ScreenCaptureState.Active }
            start.await()
            primeCachedFrame(harness, platform, rgbaSeed = 37)

            val blockerEntered = CountDownLatch(1)
            val blockerMayReturn = CountDownLatch(1)
            check(harness.workerDispatcher.tryDispatch {
                blockerEntered.countDown()
                check(blockerMayReturn.await(5L, TimeUnit.SECONDS)) {
                    "Worker scheduling blocker was not released"
                }
            })
            val blockerTask = checkNotNull(harness.enterNextWorker())
            check(blockerEntered.await(5L, TimeUnit.SECONDS)) {
                "Worker scheduling blocker did not enter"
            }

            val callbackEntries = AtomicInteger()
            var retainedEntry: ControlledNonInlineDispatcher.TaskHandle? = null
            try {
                harness.session.registerFrameConsumer {
                    callbackEntries.incrementAndGet()
                }
                harness.enterNextControlTask()
                val queuedEntry = checkNotNull(harness.enterNextWorker())
                retainedEntry = queuedEntry

                harness.session.stop()
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }

                val frozenState = harness.session.state.value as ScreenCaptureState.Stopped
                val frozenStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)
                assertEquals(0, callbackEntries.get())

                blockerMayReturn.countDown()
                blockerTask.awaitSuccessfulCompletion()
                queuedEntry.awaitSuccessfulCompletion()
                drainAcceptedSessionWork(harness)

                assertEquals(0, callbackEntries.get())
                assertEquals(frozenState, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                blockerMayReturn.countDown()
                blockerTask.awaitCompletion()
                retainedEntry?.awaitCompletion()
            }
        }
    }

}
