package io.screenstream.capture

import android.os.Build
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.BlockingCallback
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
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

/*
 * Public Session Stats accounting evidence through the real Coordinator, Production, and Delivery.
 *
 * Injected elapsed-time samples, callback latches, the second worker, and controlled task entry only arrange
 * eligible activity and consumer overlap. Exact public cumulative or frozen Stats and real callback return decide
 * these scenarios; clock-read count, queue shape, turn count, and private phase are not oracles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionStatsTest {
    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun freshOutputWithoutConsumerCountsProductionWithoutDeliveryDrop() = runTest {
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
            try {
                startActiveSession(harness, platform, parameters)
                val baselineStats = harness.session.stats.value
                harness.clock.setDefaultNanos(1L * 1_000_000_000L)

                platform.deliverSourceFrame(rgbaSeed = 47)
                harness.driveUntil {
                    harness.session.stats.value.producedFrameCount == baselineStats.producedFrameCount + 1L
                }

                val eligibleStats = harness.session.stats.value
                assertEquals(baselineStats.producedFrameCount + 1L, eligibleStats.producedFrameCount)
                assertEquals(
                    baselineStats.droppedDeliveries.byConsumerBusy,
                    eligibleStats.droppedDeliveries.byConsumerBusy,
                )
                assertEquals(
                    baselineStats.droppedDeliveries.byCallbackFailure,
                    eligibleStats.droppedDeliveries.byCallbackFailure,
                )

                harness.session.stop()
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }
                val finalStats = harness.session.stats.value
                assertEquals(eligibleStats.producedFrameCount, finalStats.producedFrameCount)
                assertEquals(eligibleStats.droppedDeliveries, finalStats.droppedDeliveries)
            } finally {
                stopAndDrainSession(harness)
            }
        }
    }

    // Verification: SES-07
    // Verification: DEL-02
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun callbackFailureBeforeRequestedStopIsCountedExactlyOnceInFinalStats() = runTest {
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
            primeCachedFrame(harness, platform, rgbaSeed = 53)
            val statsBeforeCallback = harness.session.stats.value

            val callbackEntered = CountDownLatch(1)
            val callbackReturned = CountDownLatch(1)
            val callbackEntries = AtomicInteger()
            harness.session.registerFrameConsumer {
                when (callbackEntries.incrementAndGet()) {
                    1 -> {
                        callbackEntered.countDown()
                        try {
                            throw IllegalStateException("expected callback failure")
                        } finally {
                            callbackReturned.countDown()
                        }
                    }

                    2 -> Unit

                    else -> throw AssertionError("Unexpected callback entry")
                }
            }
            harness.enterNextControlTask()
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callbackTask.awaitSuccessfulCompletion()
            check(callbackEntered.await(5L, TimeUnit.SECONDS)) {
                "Frame callback did not enter"
            }
            check(callbackReturned.await(5L, TimeUnit.SECONDS)) {
                "Frame callback did not return"
            }
            harness.clock.setDefaultNanos(1L * 1_000_000_000L)
            harness.driveUntil {
                harness.session.stats.value.droppedDeliveries.byCallbackFailure ==
                        statsBeforeCallback.droppedDeliveries.byCallbackFailure + 1L
            }

            val statsAfterFailure = harness.session.stats.value
            assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            assertEquals(1, callbackEntries.get())
            assertEquals(
                statsBeforeCallback.droppedDeliveries.byCallbackFailure + 1L,
                statsAfterFailure.droppedDeliveries.byCallbackFailure,
            )

            platform.deliverSourceFrame(rgbaSeed = 79)
            harness.driveUntil { callbackEntries.get() == 2 }
            drainAcceptedSessionWork(harness)

            val statsAfterSecondDelivery = harness.session.stats.value
            assertTrue(harness.session.state.value is ScreenCaptureState.Active)
            assertEquals(2, callbackEntries.get())
            assertEquals(
                statsAfterFailure.droppedDeliveries.byCallbackFailure,
                statsAfterSecondDelivery.droppedDeliveries.byCallbackFailure,
            )

            harness.session.stop()
            driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            val finalStats = harness.session.stats.value
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(
                statsAfterSecondDelivery.droppedDeliveries.byCallbackFailure,
                finalStats.droppedDeliveries.byCallbackFailure,
            )
        }
    }

    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.N])
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun enteredConsumerBusyDropIsCountedExactlyOnceInFrozenFinalStats() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))

        SessionStartHarness(
            workerThreadCount = 2,
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.N,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            startActiveSession(harness, platform, parameters)
            primeCachedFrame(harness, platform, rgbaSeed = 59)
            harness.clock.setDefaultNanos(1L * 1_000_000_000L)

            val callback = BlockingCallback()
            harness.session.registerFrameConsumer(callback::invoke)
            harness.enterNextControlTask()
            val callbackTask = checkNotNull(harness.enterNextWorker())
            callback.awaitEntered()
            val statsBeforeBusy = harness.session.stats.value

            try {
                harness.clock.setDefaultNanos(2L * 1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 83)
                harness.driveUntil {
                    harness.session.stats.value.droppedDeliveries.byConsumerBusy ==
                            statsBeforeBusy.droppedDeliveries.byConsumerBusy + 1L
                }
                val statsAfterBusy = harness.session.stats.value

                assertEquals(
                    statsBeforeBusy.droppedDeliveries.byConsumerBusy + 1L,
                    statsAfterBusy.droppedDeliveries.byConsumerBusy,
                )
                assertEquals(
                    statsBeforeBusy.droppedDeliveries.byCallbackFailure,
                    statsAfterBusy.droppedDeliveries.byCallbackFailure,
                )
                assertEquals(1, callback.entryCount())

                harness.session.stop()
                driveControlUntil(harness) { harness.session.state.value is ScreenCaptureState.Stopped }
                val frozenState = harness.session.state.value as ScreenCaptureState.Stopped
                val frozenStats = harness.session.stats.value

                assertSame(ScreenCaptureStopReason.Requested, frozenState.reason)
                assertEquals(statsAfterBusy, frozenStats)

                callback.release()
                callbackTask.awaitSuccessfulCompletion()
                callback.awaitReturned()
                drainAcceptedSessionWork(harness)

                assertEquals(1, callback.entryCount())
                assertEquals(frozenState, harness.session.state.value)
                assertEquals(frozenStats, harness.session.stats.value)
            } finally {
                callback.release()
                callbackTask.awaitCompletion()
                stopAndDrainSession(harness)
            }
        }
    }

}
