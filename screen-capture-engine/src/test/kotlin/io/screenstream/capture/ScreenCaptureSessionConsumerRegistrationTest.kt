package io.screenstream.capture

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.BlockingCallback
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.primeCachedFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.stopAndDrainSession
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/*
 * Public frame-consumer registration evidence through the real Coordinator, cache, and Delivery Link.
 *
 * Controlled task entry and callback latches only arrange queued, entered, and returned work. Queue shape, turn count,
 * private phase, and incidental call ordering are not oracles; public completion or failure, replacement admission,
 * callback access, real callback return, and frozen public values decide these scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionConsumerRegistrationTest {
    // Verification: UNR-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicUnregisterBeforeCallbackEntryCompletesAndLateTaskIsInert() = runTest {
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
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 31)

            val callbackEntries = AtomicInteger()
            val registration = harness.session.registerFrameConsumer {
                callbackEntries.incrementAndGet()
            }
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }

            val unregisterReturned = AtomicBoolean()
            val unregister = async(start = CoroutineStart.UNDISPATCHED) {
                registration.unregister()
                unregisterReturned.set(true)
            }
            var lateCallbackTask: ControlledNonInlineDispatcher.TaskHandle? = null
            try {
                assertTrue(unregisterReturned.get())
                unregister.await()

                val replacement = harness.session.registerFrameConsumer {
                    fail("Replacement callback entered without a new source opportunity")
                }
                replacement.unregister()
                registration.unregister()

                lateCallbackTask = checkNotNull(harness.enterNextWorker())
                lateCallbackTask.awaitSuccessfulCompletion()
                assertEquals(0, callbackEntries.get())
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            } finally {
                lateCallbackTask?.awaitCompletion()
                unregister.cancelAndJoin()
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicUnregisterAfterCallbackEntryWaitsForExactReturn() = runTest {
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
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 33)

            val callback = BlockingCallback()
            val registration = harness.session.registerFrameConsumer(callback::invoke)
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callback.awaitEntered()
            val unregisterReturned = AtomicBoolean()
            val unregister = async(start = CoroutineStart.UNDISPATCHED) {
                registration.unregister()
                unregisterReturned.set(true)
            }

            try {
                assertFalse(unregisterReturned.get())
                callback.release()
                callbackTask.awaitSuccessfulCompletion()
                callback.awaitReturned()
                check(harness.enterNextControlTask()) { "Returned callback closure was not offered to Control" }
                runCurrent()
                unregister.await()
                assertTrue(unregisterReturned.get())

                registration.unregister()
                val replacement = harness.session.registerFrameConsumer {
                    fail("Replacement callback entered without a new source opportunity")
                }
                replacement.unregister()
                assertEquals(1, callback.entryCount())
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                unregister.cancelAndJoin()
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-04
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicSelfUnregisterIsRejectedWithoutRevokingBorrow() = runTest {
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
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 35)

            val registrationRef = AtomicReference<FrameConsumerRegistration>()
            val selfUnregisterFailure = AtomicReference<IllegalStateException?>()
            val firstBorrowByteCountAfterRejection = AtomicInteger(-1)
            val secondBorrowByteCount = AtomicInteger(-1)
            val firstSequence = AtomicLong(-1L)
            val secondSequence = AtomicLong(-1L)
            val callbackEntries = AtomicInteger()
            val registration = harness.session.registerFrameConsumer { frame ->
                when (callbackEntries.incrementAndGet()) {
                    1 -> {
                        try {
                            runBlocking { checkNotNull(registrationRef.get()).unregister() }
                            fail("Self-unregister completed successfully")
                        } catch (failure: IllegalStateException) {
                            selfUnregisterFailure.set(failure)
                        }
                        firstBorrowByteCountAfterRejection.set(frame.byteCount)
                        firstSequence.set(frame.sequence)
                    }

                    2 -> {
                        secondBorrowByteCount.set(frame.byteCount)
                        secondSequence.set(frame.sequence)
                    }
                }
            }
            registrationRef.set(registration)
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val firstCallbackTask = checkNotNull(harness.enterNextWorker())
            var externalUnregister: Deferred<Unit>? = null

            try {
                firstCallbackTask.awaitSuccessfulCompletion()
                assertTrue(selfUnregisterFailure.get() is IllegalStateException)
                assertTrue(firstBorrowByteCountAfterRejection.get() > 0)
                assertEquals(1, callbackEntries.get())
                check(harness.enterNextControlTask()) { "Returned first callback closure was not offered to Control" }

                platform.deliverSourceFrame(rgbaSeed = 37)
                harness.driveUntil { secondBorrowByteCount.get() > 0 }
                assertEquals(2, callbackEntries.get())
                assertTrue(secondBorrowByteCount.get() > 0)
                assertTrue(secondSequence.get() > firstSequence.get())

                externalUnregister = async(start = CoroutineStart.UNDISPATCHED) {
                    registration.unregister()
                }
                check(harness.enterNextControlTask()) { "Returned second callback closure was not offered to Control" }
                runCurrent()
                externalUnregister.await()

                val replacement = harness.session.registerFrameConsumer {
                    fail("Replacement callback entered without a new source opportunity")
                }
                replacement.unregister()
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            } finally {
                firstCallbackTask.awaitCompletion()
                externalUnregister?.cancelAndJoin()
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-05
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancelledPublicUnregisterRetainsSettlementForRetry() = runTest {
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
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 39)

            val callback = BlockingCallback()
            val registration = harness.session.registerFrameConsumer(callback::invoke)
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callback.awaitEntered()
            val cancelledUnregisterReturned = AtomicBoolean()
            val cancelledCaller = async(start = CoroutineStart.UNDISPATCHED) {
                registration.unregister()
                cancelledUnregisterReturned.set(true)
            }
            var retry: Deferred<Unit>? = null

            try {
                assertFalse(cancelledUnregisterReturned.get())
                cancelledCaller.cancel(CancellationException("test caller cancelled"))
                runCurrent()
                try {
                    cancelledCaller.await()
                    fail("Cancelled unregister caller completed successfully")
                } catch (_: CancellationException) {
                }
                assertFalse(cancelledUnregisterReturned.get())

                try {
                    harness.session.registerFrameConsumer {
                        fail("Replacement callback entered while unregister was unresolved")
                    }
                    fail("Replacement registration was admitted before callback return")
                } catch (_: IllegalStateException) {
                }

                callback.release()
                callbackTask.awaitSuccessfulCompletion()
                callback.awaitReturned()
                retry = async(start = CoroutineStart.UNDISPATCHED) {
                    registration.unregister()
                }
                check(harness.enterNextControlTask()) { "Returned callback closure was not offered to Control" }
                runCurrent()
                retry.await()
                registration.unregister()

                val replacement = harness.session.registerFrameConsumer {
                    fail("Replacement callback entered without a new source opportunity")
                }
                replacement.unregister()
                assertEquals(1, callback.entryCount())
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                cancelledCaller.cancelAndJoin()
                retry?.cancelAndJoin()
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun terminalFailureSettlesOutstandingPublicUnregisterBeforeLateCallbackReturn() = runTest {
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
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 41)

            val callback = BlockingCallback()
            val registration = harness.session.registerFrameConsumer(callback::invoke)
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callback.awaitEntered()
            val unregisterSettled = AtomicBoolean()
            val unregister = async(start = CoroutineStart.UNDISPATCHED) {
                val outcome = try {
                    registration.unregister()
                    null
                } catch (failure: ScreenCaptureException) {
                    failure
                }
                unregisterSettled.set(true)
                outcome
            }

            try {
                assertFalse(unregisterSettled.get())
                platform.deliverSourceFrame(rgbaSeed = 73, dataSpace = DataSpace.DATASPACE_DISPLAY_P3)
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Failed }
                runCurrent()

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.UnsupportedColorSpace, failed.problem)
                val unregisterOutcome = checkNotNull(unregister.await()) {
                    "Terminal failure completed unregister successfully"
                }
                assertTrue(unregisterSettled.get())
                assertSame(ScreenCaptureProblem.UnsupportedColorSpace, unregisterOutcome.problem)
                val repeatedFailure = try {
                    registration.unregister()
                    throw AssertionError("Repeated unregister ignored terminal failure")
                } catch (failure: ScreenCaptureException) {
                    failure
                }
                assertSame(ScreenCaptureProblem.UnsupportedColorSpace, repeatedFailure.problem)
                val frozenStats = harness.session.stats.value

                callback.release()
                callbackTask.awaitSuccessfulCompletion()
                callback.awaitReturned()
                drainAcceptedSessionWork(harness)

                assertEquals(1, callback.entryCount())
                assertEquals(failed, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                unregister.cancelAndJoin()
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun enteredCallbackDoesNotDelayRequestedTerminalOrTerminalUnregisterSettlement() = runTest {
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
            primeCachedFrame(harness, platform, rgbaSeed = 43)

            val callbackEntered = CountDownLatch(1)
            val callbackMayReturn = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val registration = harness.session.registerFrameConsumer {
                callbackEntered.countDown()
                try {
                    check(callbackMayReturn.await(5L, TimeUnit.SECONDS)) {
                        "Entered callback was not released"
                    }
                    throw IllegalStateException("expected late callback failure")
                } finally {
                    callbackReturned.countDown()
                }
            }
            harness.enterNextControlTask()
            val callbackTask = checkNotNull(harness.enterNextWorker())
            check(callbackEntered.await(5L, TimeUnit.SECONDS)) {
                "Frame callback did not enter"
            }

            val unregister = async(UnconfinedTestDispatcher(testScheduler)) {
                try {
                    registration.unregister()
                    fail("Terminal stop completed an entered callback unregister successfully")
                } catch (_: CancellationException) {
                }
            }

            try {
                harness.session.stop()
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }
                unregister.await()

                val frozenState = harness.session.state.value as ScreenCaptureState.Stopped
                val frozenStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)

                callbackMayReturn.countDown()
                callbackTask.awaitSuccessfulCompletion()
                check(callbackReturned.await(5L, TimeUnit.SECONDS)) {
                    "Frame callback did not return"
                }
                drainAcceptedSessionWork(harness)

                assertEquals(frozenState, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                callbackMayReturn.countDown()
                callbackTask.awaitCompletion()
                unregister.cancelAndJoin()
            }
        }
    }

}
