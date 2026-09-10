package io.screenstream.capture

import android.graphics.Bitmap
import android.hardware.DataSpace
import android.media.projection.MediaProjection
import android.os.Build
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.DispatchOutcome
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.NativeCarrierSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.assertJpegDimensions
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.primeCachedFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
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
@OptIn(ExperimentalCoroutinesApi::class)
internal class ScreenCaptureSessionTerminalFenceTest {
    // Verification: SES-02
    // Verification: SES-03
    // Verification: SES-07
    // Verification: ENC-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun stoppedSessionHeldInFrameworkCompressionCannotAffectSuccessorFramesOrFrozenValues() = runTest {
        val bitmapA = spyk(Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888))
        val bitmapB = spyk(Bitmap.createBitmap(10, 6, Bitmap.Config.ARGB_8888))
        val compressionEntered = CountDownLatch(1)
        val compressionMayReturn = CountDownLatch(1)
        val platformA = CapturePlatformFixture()
        val platformB = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        try {
            every { bitmapA.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
                compressionEntered.countDown()
                assertTrue("Session A compression was not released", compressionMayReturn.await(30L, TimeUnit.SECONDS))
                callOriginal()
            }
            mockkStatic(Bitmap::class)
            every { Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888) } returns bitmapA
            every { Bitmap.createBitmap(10, 6, Bitmap.Config.ARGB_8888) } returns bitmapB
            SessionHarness(
                bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
                metrics = CaptureMetrics(8, 6, 320),
                platformSdkInt = Build.VERSION_CODES.N,
                projection = platformA.projection,
                projectionPlatform = platformA.projectionPlatform,
                eglPlatform = platformA.eglPlatform,
                glesPlatform = platformA.glesPlatform,
                targetPlatform = platformA.targetPlatform,
            ).use { a ->
                SessionHarness(
                    bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
                    metrics = CaptureMetrics(10, 6, 320),
                    platformSdkInt = Build.VERSION_CODES.N,
                    projection = platformB.projection,
                    projectionPlatform = platformB.projectionPlatform,
                    eglPlatform = platformB.eglPlatform,
                    glesPlatform = platformB.glesPlatform,
                    targetPlatform = platformB.targetPlatform,
                ).use { b ->
                    var heldTask: ControlledNonInlineDispatcher.TaskHandle? = null
                    val aFrames = AtomicInteger()
                    val bFrames = CopyOnWriteArrayList<FrameSnapshot>()
                    try {
                        startActiveSession(a, parameters)
                        a.session.registerFrameConsumer { aFrames.incrementAndGet() }
                        platformA.deliverSourceFrame(rgbaSeed = 41)
                        // Controlled entry arranges the interval; the compress latch proves actual codec entry.
                        repeat(32) {
                            if (heldTask == null) {
                                a.enterNextControlTask()
                                a.enterNextCaptureTask()
                                heldTask = a.enterNextWorker()
                            }
                        }
                        val exactAProduction = checkNotNull(heldTask) { "Session A did not submit compression" }
                        check(compressionEntered.await(5L, TimeUnit.SECONDS)) { "Session A compression did not enter" }
                        a.session.requestStop()

                        startActiveSession(b, parameters)
                        assertTrue(a.session !== b.session)
                        b.session.registerFrameConsumer { bFrames += copyFrame(it) }
                        platformB.deliverSourceFrame(rgbaSeed = 73)
                        b.driveUntil { bFrames.size == 1 }
                        assertTrue(b.session.state.value is ScreenCaptureState.Active)
                        val firstB = bFrames.single()
                        assertJpegDimensions(firstB.bytes, 10, 6)
                        verify(exactly = 1) { bitmapB.compress(Bitmap.CompressFormat.JPEG, any(), any()) }

                        driveControlUntil(a) { a.session.state.value is ScreenCaptureState.Stopped }
                        val frozenState = a.session.state.value as ScreenCaptureState.Stopped
                        val frozenStats = a.session.stats.value
                        assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)
                        assertEquals(0, aFrames.get())
                        assertFalse(bitmapA.isRecycled)
                        assertFalse(bitmapB.isRecycled)
                        verify(exactly = 0) {
                            bitmapA.recycle()
                            bitmapB.recycle()
                        }

                        compressionMayReturn.countDown()
                        exactAProduction.awaitSuccessfulCompletion()
                        drainAcceptedSessionWork(a)
                        assertTrue(bitmapA.isRecycled)
                        verify(exactly = 1) { bitmapA.recycle() }
                        assertFalse(bitmapB.isRecycled)
                        verify(exactly = 0) { bitmapB.recycle() }
                        assertEquals(0, aFrames.get())
                        assertEquals(frozenState, a.session.state.value)
                        assertEquals(frozenStats, a.session.stats.value)

                        platformB.deliverSourceFrame(rgbaSeed = 109)
                        b.driveUntil { bFrames.size == 2 }
                        val secondB = bFrames.last()
                        assertTrue(secondB.sequence > firstB.sequence)
                        assertJpegDimensions(secondB.bytes, 10, 6)
                        verify(exactly = 2) { bitmapB.compress(Bitmap.CompressFormat.JPEG, any(), any()) }
                        assertTrue(b.session.state.value is ScreenCaptureState.Active)
                        assertEquals(0, aFrames.get())
                        assertEquals(frozenState, a.session.state.value)
                        assertEquals(frozenStats, a.session.stats.value)
                    } finally {
                        compressionMayReturn.countDown()
                        heldTask?.awaitSuccessfulCompletion()
                        requestStopAndDrainSession(a)
                        requestStopAndDrainSession(b)
                    }
                }
            }
        } finally {
            compressionMayReturn.countDown()
            unmockkStatic(Bitmap::class)
            if (!bitmapA.isRecycled) bitmapA.recycle()
            if (!bitmapB.isRecycled) bitmapB.recycle()
        }
    }

    // Verification: TERM-01
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    @OptIn(ExperimentalCoroutinesApi::class)
    fun requestedTerminalDetachesEnteredCaptureReadAndLateFilledReturnOnlyFreesItsNativeCarrier() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.R,
            projection = platform.projection,
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
                    harness.session.requestStop()
                } catch (failure: Throwable) {
                    stopFailure.compareAndSet(null, failure)
                } finally {
                    stopReturned.countDown()
                }
            }, "ScreenCaptureEngine-Terminal-Stopper")

            try {
                startActiveSession(harness, parameters)
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
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-02
    // Verification: DEL-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(ExperimentalCoroutinesApi::class)
    fun terminalRequestStopFencesAcceptedCallbackBeforeEntryAndFreezesPublicStateAndStats() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val start = async(UnconfinedTestDispatcher(testScheduler)) {
                harness.session.start(parameters)
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

                harness.session.requestStop()
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

    /*
     * Characterization only: wrappers share test authority by construction, not by an asserted Android guarantee.
     * The successor's Active snapshot does not prove continued capture after the modeled old stop.
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun lateOldStopTargetsAuthorityModeledAsSharedByDistinctActiveWrappers() = runTest {
        val authority = ModeledProjectionAuthority()
        val oldPlatform = CapturePlatformFixture()
        val successorPlatform = CapturePlatformFixture()
        authority.attach(oldPlatform.projection)
        authority.attach(successorPlatform.projection)
        every {
            oldPlatform.projectionPlatform.stop(refEq(oldPlatform.projection))
        } answers { authority.recordStop(firstArg()) }
        every {
            successorPlatform.projectionPlatform.stop(refEq(successorPlatform.projection))
        } answers { authority.recordStop(firstArg()) }

        projectionStopHarness(oldPlatform).use { old ->
            projectionStopHarness(successorPlatform).use { successor ->
                try {
                    startActiveSession(old, PROJECTION_STOP_PARAMETERS)
                    old.session.requestStop()
                    driveControlOnlyUntilTerminal(old)
                    val oldTerminal = old.session.state.value

                    assertTrue(oldTerminal is ScreenCaptureState.Stopped)
                    assertTrue(authority.isActive())
                    assertTrue(authority.stopCalls().isEmpty())

                    startActiveSession(successor, PROJECTION_STOP_PARAMETERS)
                    assertNotSame(oldPlatform.projection, successorPlatform.projection)
                    assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                    assertTrue(authority.isActive())

                    check(old.enterNextCaptureTask()) { "Old Capture cleanup was not retained" }

                    assertFalse(authority.isActive())
                    assertSame(oldPlatform.projection, authority.stopCalls().single())
                    assertEquals(oldTerminal, old.session.state.value)
                    assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                    verify(exactly = 1) {
                        oldPlatform.projectionPlatform.stop(refEq(oldPlatform.projection))
                    }
                    verify(exactly = 0) {
                        successorPlatform.projectionPlatform.stop(refEq(successorPlatform.projection))
                    }
                } finally {
                    requestStopAndDrainSession(old)
                    requestStopAndDrainSession(successor)
                }
            }
        }
    }

    // Verification: SES-02
    // Verification: SES-08
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun oldStopCanRemainEnteredAfterTerminalWhileIndependentSuccessorIsActive() = runTest {
        val oldAuthority = ModeledProjectionAuthority()
        val successorAuthority = ModeledProjectionAuthority()
        val oldPlatform = CapturePlatformFixture()
        val successorPlatform = CapturePlatformFixture()
        oldAuthority.attach(oldPlatform.projection)
        successorAuthority.attach(successorPlatform.projection)
        val stopEntered = CountDownLatch(1)
        val stopMayReturn = CountDownLatch(1)
        val stopExited = CountDownLatch(1)
        val successorFrameDelivered = CountDownLatch(1)
        val progressSuccessorDuringStop = AtomicReference<(() -> Unit)?>(null)
        every {
            oldPlatform.projectionPlatform.stop(refEq(oldPlatform.projection))
        } answers {
            oldAuthority.recordStop(firstArg())
            stopEntered.countDown()
            try {
                checkNotNull(progressSuccessorDuringStop.get()).invoke()
                check(stopMayReturn.await(5L, TimeUnit.SECONDS)) { "Old projection stop was not released" }
            } finally {
                stopExited.countDown()
            }
        }
        every {
            successorPlatform.projectionPlatform.stop(refEq(successorPlatform.projection))
        } answers { successorAuthority.recordStop(firstArg()) }

        projectionStopHarness(oldPlatform).use { old ->
            projectionStopHarness(successorPlatform).use { successor ->
                val verifierFailure = AtomicReference<Throwable?>()
                var verifier: Thread? = null
                try {
                    startActiveSession(old, PROJECTION_STOP_PARAMETERS)
                    old.session.requestStop()
                    driveControlOnlyUntilTerminal(old)
                    val oldTerminal = old.session.state.value
                    val stopped = async(UnconfinedTestDispatcher(testScheduler)) { old.session.stop() }
                    assertFalse(stopped.isCompleted)

                    startActiveSession(successor, PROJECTION_STOP_PARAMETERS)
                    assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                    successor.session.registerFrameConsumer { frame ->
                        check(frame.byteCount > 0)
                        successorFrameDelivered.countDown()
                    }
                    check(successor.enterNextControlTask()) { "Successor consumer registration was not offered" }
                    progressSuccessorDuringStop.set {
                        successorPlatform.deliverSourceFrame(rgbaSeed = 79)
                        successor.driveUntil { successorFrameDelivered.count == 0L }
                    }

                    verifier = Thread({
                        try {
                            check(stopEntered.await(5L, TimeUnit.SECONDS)) { "Old projection stop did not enter" }
                            check(successorFrameDelivered.await(5L, TimeUnit.SECONDS)) {
                                "Successor frame was not delivered while old projection stop was entered"
                            }
                            assertTrue(old.session.state.value is ScreenCaptureState.Stopped)
                            assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                            assertFalse(oldAuthority.isActive())
                            assertTrue(successorAuthority.isActive())
                            assertEquals(1L, stopExited.count)
                            assertFalse(stopped.isCompleted)
                        } catch (failure: Throwable) {
                            verifierFailure.set(failure)
                        } finally {
                            stopMayReturn.countDown()
                        }
                    }, "ScreenCaptureEngine-Stop-Verifier").also(Thread::start)

                    check(old.enterNextCaptureTask()) { "Old Capture cleanup was not retained" }
                    check(stopExited.await(5L, TimeUnit.SECONDS)) { "Old projection stop did not exit" }
                    verifier.join(5_000L)
                    assertFalse("Stop verifier did not return", verifier.isAlive)
                    verifierFailure.get()?.let { throw AssertionError("Stop verifier failed", it) }

                    stopped.await()
                    old.session.stop()
                    assertEquals(oldTerminal, old.session.state.value)
                    assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                    assertTrue(successorAuthority.isActive())
                    assertEquals(0L, successorFrameDelivered.count)
                    assertSame(oldPlatform.projection, oldAuthority.stopCalls().single())
                } finally {
                    stopMayReturn.countDown()
                    verifier?.join(5_000L)
                    requestStopAndDrainSession(old)
                    requestStopAndDrainSession(successor)
                }
            }
        }
    }

    // Verification: SES-02
    // Verification: SES-07
    // Verification: SES-08
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun throwingOldStopLeavesTerminalFrozenAndIndependentSuccessorActive() = runTest {
        val stopFailure = IllegalStateException("injected old stop failure")
        val oldPlatform = CapturePlatformFixture()
        val successorPlatform = CapturePlatformFixture()
        every {
            oldPlatform.projectionPlatform.stop(refEq(oldPlatform.projection))
        } throws stopFailure

        projectionStopHarness(oldPlatform).use { old ->
            projectionStopHarness(successorPlatform).use { successor ->
                val successorFrames = AtomicInteger()
                try {
                    startActiveSession(old, PROJECTION_STOP_PARAMETERS)
                    old.session.requestStop()
                    driveControlOnlyUntilTerminal(old)
                    val oldTerminal = old.session.state.value
                    val oldStats = old.session.stats.value
                    val stopped = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { old.session.stop() } }

                    startActiveSession(successor, PROJECTION_STOP_PARAMETERS)
                    successor.session.registerFrameConsumer { frame ->
                        check(frame.byteCount > 0)
                        successorFrames.incrementAndGet()
                    }
                    check(successor.enterNextControlTask()) { "Successor consumer registration was not offered" }
                    check(old.enterNextCaptureTask()) { "Old Capture cleanup was not retained" }
                    successorPlatform.deliverSourceFrame(rgbaSeed = 103)
                    successor.driveUntil { successorFrames.get() == 1 }

                    val failure = stopped.await().exceptionOrNull() as ScreenCaptureException
                    assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
                    assertSame(stopFailure, failure.cause)
                    assertSame(failure, runCatching { old.session.stop() }.exceptionOrNull())
                    assertEquals(oldTerminal, old.session.state.value)
                    assertEquals(oldStats, old.session.stats.value)
                    assertTrue(successor.session.state.value is ScreenCaptureState.Active)
                    assertEquals(1, successorFrames.get())
                    verify(exactly = 1) {
                        oldPlatform.projectionPlatform.stop(refEq(oldPlatform.projection))
                    }
                    verify(exactly = 0) {
                        successorPlatform.projectionPlatform.stop(refEq(successorPlatform.projection))
                    }
                } finally {
                    requestStopAndDrainSession(old)
                    requestStopAndDrainSession(successor)
                }
            }
        }
    }

    // Verification: SES-08
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun stopCompletesBeforeProjectionUnregisterAndSurvivesItsFailure() = runTest {
        val platform = CapturePlatformFixture()
        projectionStopHarness(platform).use { harness ->
            startActiveSession(harness, PROJECTION_STOP_PARAMETERS)
            val stopped = async(UnconfinedTestDispatcher(testScheduler)) { harness.session.stop() }
            driveControlOnlyUntilTerminal(harness)
            assertFalse(stopped.isCompleted)
            val finalStats = harness.session.stats.value
            every { platform.projectionPlatform.unregisterCallback(refEq(platform.projection), any()) } answers {
                // Unconfined resumption exposes completed stop while this cleanup invocation is still entered.
                assertTrue(stopped.isCompleted)
                assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
                assertEquals(finalStats, harness.session.stats.value)
                throw IllegalStateException("unregister cleanup failed after stop completion")
            }
            check(harness.enterNextCaptureTask())
            stopped.await()
            harness.session.stop()
            assertTrue(runCatching { harness.session.start() }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { harness.session.updateParameters(PROJECTION_STOP_PARAMETERS) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { harness.session.registerFrameConsumer {} }.exceptionOrNull() is IllegalStateException)
            requestStopAndDrainSession(harness)
        }
    }

    // Verification: SES-08
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun cancelledStopWaiterDoesNotCancelSharedCompletionOrRetryProjectionStop() = runTest {
        val platform = CapturePlatformFixture()
        projectionStopHarness(platform).use { harness ->
            startActiveSession(harness, PROJECTION_STOP_PARAMETERS)
            // Bootstrap no longer owns the projection. Rejection of its redundant prefix cleanup cannot fail stop.
            harness.workerDispatcher.enqueueReject()
            val cancelled = async(UnconfinedTestDispatcher(testScheduler)) { harness.session.stop() }
            val surviving = async(UnconfinedTestDispatcher(testScheduler)) { harness.session.stop() }
            cancelled.cancelAndJoin()
            driveControlOnlyUntilTerminal(harness)
            assertFalse(surviving.isCompleted)
            check(harness.enterNextCaptureTask())
            surviving.await()
            harness.session.stop()
            verify(exactly = 1) { platform.projectionPlatform.stop(refEq(platform.projection)) }
            requestStopAndDrainSession(harness)
        }
    }

    // Verification: SES-08
    // Verification: CAP-05
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun stopSettlesPendingStartupAndMakesLateAcceptedOpenInert() = runTest {
        val platform = CapturePlatformFixture()
        projectionStopHarness(platform).use { harness ->
            val startup = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { harness.session.start(PROJECTION_STOP_PARAMETERS) } }
            harness.driveUntil { harness.pendingCaptureTaskCount() != 0 }
            val lateOpen = checkNotNull(harness.claimNextCaptureTask())
            val stopped = async(UnconfinedTestDispatcher(testScheduler)) { harness.session.stop() }
            driveControlOnlyUntilTerminal(harness)
            assertFalse(stopped.isCompleted)
            check(harness.enterNextCaptureTask())
            stopped.await()
            assertTrue(startup.await().exceptionOrNull() is java.util.concurrent.CancellationException)
            val terminal = harness.session.state.value
            val finalStats = harness.session.stats.value
            lateOpen.run()
            verify(exactly = 0) { platform.projectionPlatform.createVirtualDisplay(any(), any(), any(), any(), any()) }
            verify(exactly = 1) { platform.projectionPlatform.stop(refEq(platform.projection)) }
            assertSame(terminal, harness.session.state.value)
            assertEquals(finalStats, harness.session.stats.value)
            requestStopAndDrainSession(harness)
        }
    }

    // Verification: SES-08
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    fun rejectedCaptureRetirementFailsStopWithoutRetry() = runTest {
        val platform = CapturePlatformFixture()
        projectionStopHarness(platform).use { harness ->
            startActiveSession(harness, PROJECTION_STOP_PARAMETERS)
            harness.setNextCapturePostOutcome(DispatchOutcome.Reject)
            val stopped = async(UnconfinedTestDispatcher(testScheduler)) { runCatching { harness.session.stop() } }
            driveControlOnlyUntilTerminal(harness)
            val failure = stopped.await().exceptionOrNull() as ScreenCaptureException
            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertSame(failure, runCatching { harness.session.stop() }.exceptionOrNull())
            verify(exactly = 0) { platform.projectionPlatform.stop(refEq(platform.projection)) }
            requestStopAndDrainSession(harness)
        }
    }

    private fun projectionStopHarness(platform: CapturePlatformFixture): SessionHarness = SessionHarness(
        bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
        metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
        platformSdkInt = Build.VERSION_CODES.N,
        projection = platform.projection,
        projectionPlatform = platform.projectionPlatform,
        eglPlatform = platform.eglPlatform,
        glesPlatform = platform.glesPlatform,
        targetPlatform = platform.targetPlatform,
    )

    private fun driveControlOnlyUntilTerminal(harness: SessionHarness) {
        repeat(32) {
            if (harness.session.state.value is ScreenCaptureState.Stopped ||
                harness.session.state.value is ScreenCaptureState.Failed
            ) return
            check(harness.enterNextControlTask()) { "Control became idle before terminal publication" }
        }
        error("Control did not publish a terminal state within the bounded drive")
    }

    private class ModeledProjectionAuthority {
        private val wrappers = CopyOnWriteArrayList<MediaProjection>()
        private val stops = CopyOnWriteArrayList<MediaProjection>()

        fun attach(wrapper: MediaProjection) {
            check(!wrappers.contains(wrapper))
            wrappers += wrapper
        }

        fun recordStop(wrapper: MediaProjection) {
            check(wrappers.contains(wrapper))
            stops += wrapper
        }

        fun isActive(): Boolean = stops.isEmpty()

        fun stopCalls(): List<MediaProjection> = stops.toList()
    }

    private companion object {
        val PROJECTION_STOP_PARAMETERS = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
    }
}
