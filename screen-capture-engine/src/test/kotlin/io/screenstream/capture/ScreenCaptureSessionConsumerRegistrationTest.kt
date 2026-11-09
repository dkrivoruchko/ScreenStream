package io.screenstream.capture

import android.hardware.DataSpace
import android.os.Build
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.BlockingCallback
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.driveControlUntil
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.primeCachedFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionConsumerRegistrationTest {
    // Verification: UNR-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicUnregisterBeforeCallbackEntryCompletesAndLateTaskIsInert() = runTest {
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
            startActiveSession(harness, parameters)
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
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicUnregisterAfterCallbackEntryWaitsForExactReturn() = runTest {
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
            startActiveSession(harness, parameters)
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
                val replacementEntries = AtomicInteger()
                val replacement = harness.session.registerFrameConsumer { replacementEntries.incrementAndGet() }
                check(harness.enterNextControlTask()) { "Cached replacement delivery was not offered" }
                val replacementTask = checkNotNull(harness.enterNextWorker())
                replacementTask.awaitSuccessfulCompletion()
                assertEquals(1, replacementEntries.get())
                check(harness.enterNextControlTask()) { "Replacement callback closure was not offered to Control" }
                runCurrent()
                replacement.unregister()
                assertEquals(1, callback.entryCount())
                assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                unregister.cancelAndJoin()
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: DEL-02
    // Verification: UNR-03
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun sameCallbackWorkerCanRetryAndReplaceBeforePhysicalNotificationReturns() = runTest {
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
            startActiveSession(harness, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 34)

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val originalSequence = AtomicLong()
            val originalWorker = AtomicReference<Thread>()
            val replacementSequence = AtomicLong()
            val replacementEntries = AtomicInteger()
            val registration = harness.session.registerFrameConsumer { frame ->
                originalWorker.set(Thread.currentThread())
                originalSequence.set(frame.sequence)
                entered.countDown()
                check(release.await(5L, TimeUnit.SECONDS)) { "callback was not released" }
            }
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val callbackTask = checkNotNull(harness.enterNextWorker())
            check(entered.await(5L, TimeUnit.SECONDS)) { "callback did not enter" }

            val continuationWorker = AtomicReference<Thread>()
            val replacementRef = AtomicReference<FrameConsumerRegistration>()
            val unregister = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                registration.unregister()
                registration.unregister()
                continuationWorker.set(Thread.currentThread())
                replacementRef.set(harness.session.registerFrameConsumer { frame ->
                    replacementEntries.incrementAndGet()
                    replacementSequence.set(frame.sequence)
                })
            }
            assertFalse(unregister.isCompleted)
            release.countDown()
            callbackTask.awaitSuccessfulCompletion()
            unregister.await()

            assertSame(callbackTask.enteredThread, continuationWorker.get())
            assertSame(callbackTask.enteredThread, originalWorker.get())
            assertEquals(0, replacementEntries.get())

            harness.driveUntil { replacementEntries.get() == 1 }
            assertEquals(originalSequence.get(), replacementSequence.get())
            checkNotNull(replacementRef.get()).unregister()
            requestStopAndDrainSession(harness)
        }
    }

    // Verification: UNR-04
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publicSelfUnregisterIsRejectedWithoutRevokingBorrow() = runTest {
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
            startActiveSession(harness, parameters)
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
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: UNR-05
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancelledPublicUnregisterRetainsSettlementForRetry() = runTest {
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
            startActiveSession(harness, parameters)
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
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: DEL-02
    // Verification: UNR-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun terminalFailureKeepsOutstandingPublicUnregisterPendingUntilLateCallbackReturn() = runTest {
        val platform = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 41)

            val callback = BlockingCallback()
            val registration = harness.session.registerFrameConsumer(callback::invoke)
            check(harness.enterNextControlTask()) { "Cached delivery was not offered" }
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callback.awaitEntered()
            val unregisterSettled = AtomicBoolean()
            val unregister = async(start = CoroutineStart.UNDISPATCHED) {
                registration.unregister()
                unregisterSettled.set(true)
            }

            try {
                assertFalse(unregisterSettled.get())
                platform.deliverSourceFrame(rgbaSeed = 73, dataSpace = DataSpace.DATASPACE_DISPLAY_P3)
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Failed }
                runCurrent()

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.UnsupportedColorSpace, failed.problem)
                assertFalse(unregister.isCompleted)
                callback.release()
                callbackTask.awaitSuccessfulCompletion()
                callback.awaitReturned()
                unregister.await()
                assertTrue(unregisterSettled.get())
                registration.unregister()
                val frozenStats = harness.session.stats.value

                drainAcceptedSessionWork(harness)

                assertEquals(1, callback.entryCount())
                assertEquals(failed, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                unregister.cancelAndJoin()
                requestStopAndDrainSession(harness)
            }
        }
    }

    // Verification: DEL-02
    // Verification: UNR-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun enteredCallbackKeepsUnregisterPendingUntilActualReturnAfterTerminal() = runTest {
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
            primeCachedFrame(harness, platform, rgbaSeed = 43)

            val callbackEntered = CountDownLatch(1)
            val selfCallNow = CountDownLatch(1)
            val callbackMayReturn = CountDownLatch(1)
            val selfCallCompleted = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val selfCallFailure = AtomicReference<Throwable>()
            val borrowAfterSelfCall = AtomicBoolean()
            val registrationRef = AtomicReference<FrameConsumerRegistration>()
            val registration = harness.session.registerFrameConsumer { frame ->
                callbackEntered.countDown()
                try {
                    check(selfCallNow.await(5L, TimeUnit.SECONDS)) { "self-call was not released" }
                    try {
                        val failure = try {
                            runBlocking { registrationRef.get().unregister() }
                            AssertionError("self-unregister unexpectedly succeeded")
                        } catch (failure: Throwable) {
                            failure
                        }
                        selfCallFailure.set(failure)
                        frame.byteCount
                        borrowAfterSelfCall.set(true)
                    } finally {
                        selfCallCompleted.countDown()
                    }
                    check(callbackMayReturn.await(5L, TimeUnit.SECONDS)) {
                        "Entered callback was not released"
                    }
                    throw IllegalStateException("expected late callback failure")
                } finally {
                    callbackReturned.countDown()
                }
            }
            registrationRef.set(registration)
            harness.enterNextControlTask()
            val callbackTask = checkNotNull(harness.enterNextWorker())
            check(callbackEntered.await(5L, TimeUnit.SECONDS)) {
                "Frame callback did not enter"
            }

            var unregister: Deferred<Unit>? = null

            try {
                harness.session.requestStop()
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }

                val frozenState = harness.session.state.value as ScreenCaptureState.Stopped
                val frozenStats = harness.session.stats.value
                assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)

                selfCallNow.countDown()
                check(selfCallCompleted.await(5L, TimeUnit.SECONDS)) { "self-call did not complete" }
                assertTrue(selfCallFailure.get() is IllegalStateException)
                assertTrue(borrowAfterSelfCall.get())

                unregister = async(UnconfinedTestDispatcher(testScheduler)) {
                    registration.unregister()
                }
                assertFalse(unregister.isCompleted)

                callbackMayReturn.countDown()
                callbackTask.awaitSuccessfulCompletion()
                check(callbackReturned.await(5L, TimeUnit.SECONDS)) {
                    "Frame callback did not return"
                }
                unregister.await()
                registration.unregister()
                drainAcceptedSessionWork(harness)

                assertEquals(frozenState, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                selfCallNow.countDown()
                callbackMayReturn.countDown()
                callbackTask.awaitCompletion()
                unregister?.cancelAndJoin()
            }
        }
    }

    // Verification: DEL-02
    // Verification: UNR-06
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun terminalHeldSessionCompletesItsRegistrationWithoutAControlTurnAndDoesNotAffectAnotherSession() = runTest {
        val platformA = CapturePlatformFixture()
        val platformB = CapturePlatformFixture()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val harnessA = SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projection = platformA.projection,
            projectionPlatform = platformA.projectionPlatform,
            eglPlatform = platformA.eglPlatform,
            glesPlatform = platformA.glesPlatform,
            targetPlatform = platformA.targetPlatform,
        )
        val harnessB = SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projection = platformB.projection,
            projectionPlatform = platformB.projectionPlatform,
            eglPlatform = platformB.eglPlatform,
            glesPlatform = platformB.glesPlatform,
            targetPlatform = platformB.targetPlatform,
        )
        var callbackTaskA: ControlledNonInlineDispatcher.TaskHandle? = null
        var callbackA: BlockingCallback? = null
        var unregisterA: Deferred<Unit>? = null
        try {
            startActiveSession(harnessA, parameters)
            primeCachedFrame(harnessA, platformA, rgbaSeed = 53)

            val heldCallback = BlockingCallback()
            callbackA = heldCallback
            val registrationA = harnessA.session.registerFrameConsumer(heldCallback::invoke)
            check(harnessA.enterNextControlTask()) { "Session A cached delivery was not offered" }
            val heldTask = checkNotNull(harnessA.enterNextWorker())
            callbackTaskA = heldTask
            heldCallback.awaitEntered()

            harnessA.session.requestStop()
            driveControlUntil(harnessA) { harnessA.session.state.value is ScreenCaptureState.Stopped }
            val frozenAState = harnessA.session.state.value
            val frozenAStats = harnessA.session.stats.value

            startActiveSession(harnessB, parameters)
            val bFrames = AtomicInteger()
            val bSequence = AtomicLong()
            harnessB.session.registerFrameConsumer { frame ->
                bFrames.incrementAndGet()
                bSequence.set(frame.sequence)
            }
            platformB.deliverSourceFrame(rgbaSeed = 61)
            harnessB.driveUntil { bFrames.get() == 1 }
            val firstBSequence = bSequence.get()
            assertTrue(harnessB.session.state.value is ScreenCaptureState.Active)

            val unregisterHandle = async(start = CoroutineStart.UNDISPATCHED) { registrationA.unregister() }
            unregisterA = unregisterHandle
            assertFalse(unregisterHandle.isCompleted)

            heldCallback.release()
            heldTask.awaitSuccessfulCompletion()
            unregisterHandle.await()
            assertEquals(frozenAState, harnessA.session.state.value)
            assertEquals(frozenAStats, harnessA.session.stats.value)

            platformB.deliverSourceFrame(rgbaSeed = 67)
            harnessB.driveUntil { bFrames.get() == 2 }
            assertTrue(bSequence.get() > firstBSequence)
            assertTrue(harnessB.session.state.value is ScreenCaptureState.Active)
        } finally {
            callbackA?.release()
            callbackTaskA?.awaitCompletion()
            unregisterA?.cancelAndJoin()
            requestStopAndDrainSession(harnessA)
            requestStopAndDrainSession(harnessB)
            harnessA.close()
            harnessB.close()
        }
    }

    // Verification: DEL-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cachedFirstDispatchRejectionFailsSessionWithoutCallbackEntry() = runTest {
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
            try {
                startActiveSession(harness, parameters)
                primeCachedFrame(harness, platform, rgbaSeed = 47)
                drainAcceptedSessionWork(harness)

                val callbackEntries = AtomicInteger()
                harness.workerDispatcher.enqueueReject()
                harness.session.registerFrameConsumer {
                    callbackEntries.incrementAndGet()
                }
                check(harness.enterNextControlTask()) { "Cached-first delivery was not offered" }
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Failed }

                val failed = harness.session.state.value as ScreenCaptureState.Failed
                assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
                assertEquals(0, callbackEntries.get())
            } finally {
                requestStopAndDrainSession(harness)
            }
        }
    }

}
