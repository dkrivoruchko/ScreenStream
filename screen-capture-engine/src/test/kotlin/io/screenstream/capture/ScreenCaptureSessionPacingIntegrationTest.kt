package io.screenstream.capture

import android.os.Build
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.FrameSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.copyFrame
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.drainAcceptedSessionWork
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionPacingIntegrationTest {
    // Verification: SES-05
    // Verification: SES-06
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun deferredSecondMaxFpsFreshGrantPublishesAtExplicitDelayedControlEntry() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 2)
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.MaxFps(10),
        )
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionHarness.DelayedControlOutcome.Accept,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, parameters)
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
                assertTrue(second.outputTimestampElapsedRealtimeNanos > first.outputTimestampElapsedRealtimeNanos)
                assertEquals(active.outputInfo, second.outputInfo)
                assertEquals(2, platform.sourceUpdateCount())
                assertEquals(2, nativeJpeg.carrierSnapshot().compressionCount)
                assertEquals(2L, harness.session.stats.value.encodedFrameCount)
                assertEquals(2L, harness.session.stats.value.producedFrameCount)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()
            } finally {
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-06
    // Verification: SES-07
    // Verification: DEL-02
    // Verification: STO-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumerlessFreshProductionContinuesAndLateConsumerReceivesLatestCache() = runTest {
        val platform = CapturePlatformFixture()
        val firstBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x31, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte())
        val secondBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x32, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte())
        val nativeJpeg = SafeRejectingNativeJpegFacade(
            successfulCompressionCountBeforeRejection = 2,
            successfulBytesForCompression = { compressionCount ->
                when (compressionCount) {
                    1 -> firstBytes
                    2 -> secondBytes
                    else -> error("Unexpected successful compression $compressionCount")
                }
            },
        )
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionHarness.DelayedControlOutcome.Accept,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, parameters)
                val active = harness.session.state.value as ScreenCaptureState.Active

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 37)
                harness.driveUntil { harness.session.stats.value.producedFrameCount == 1L }
                drainAcceptedSessionWork(harness)

                val statsAfterFirst = harness.session.stats.value
                val readsAfterFirst = platform.sourceUpdateCount()
                val compressionsAfterFirst = nativeJpeg.carrierSnapshot().compressionCount
                assertEquals(1L, statsAfterFirst.encodedFrameCount)
                assertEquals(1L, statsAfterFirst.producedFrameCount)
                assertEquals(0L, statsAfterFirst.frameProductionDrops.total)
                assertEquals(0L, statsAfterFirst.droppedDeliveries.total)

                harness.clock.setDefaultNanos(2_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 73)
                harness.driveUntil { harness.session.stats.value.producedFrameCount == 2L }
                drainAcceptedSessionWork(harness)
                val statsAfterSecond = harness.session.stats.value

                assertEquals(statsAfterFirst.encodedFrameCount + 1L, statsAfterSecond.encodedFrameCount)
                assertEquals(statsAfterFirst.producedFrameCount + 1L, statsAfterSecond.producedFrameCount)
                assertEquals(statsAfterFirst.frameProductionDrops, statsAfterSecond.frameProductionDrops)
                assertEquals(statsAfterFirst.droppedDeliveries, statsAfterSecond.droppedDeliveries)
                assertEquals(readsAfterFirst + 1, platform.sourceUpdateCount())
                assertEquals(compressionsAfterFirst + 1, nativeJpeg.carrierSnapshot().compressionCount)

                val delivered = CopyOnWriteArrayList<FrameSnapshot>()
                harness.clock.setDefaultNanos(3_000_000_000L)
                val registration = harness.session.registerFrameConsumer { frame -> delivered += copyFrame(frame) }
                harness.driveUntil { delivered.size == 1 }
                drainAcceptedSessionWork(harness)
                val cached = delivered.single()

                assertArrayEquals(secondBytes, cached.bytes)
                assertTrue(!firstBytes.contentEquals(cached.bytes))
                assertEquals(2L, cached.sequence)
                assertEquals(2_000_000_000L, cached.outputTimestampElapsedRealtimeNanos)
                assertEquals(active.outputInfo, cached.outputInfo)
                assertEquals(statsAfterSecond, harness.session.stats.value)
                assertEquals(readsAfterFirst + 1, platform.sourceUpdateCount())
                assertEquals(compressionsAfterFirst + 1, nativeJpeg.carrierSnapshot().compressionCount)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()
            } finally {
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-06
    // Verification: SES-07
    // Verification: DEL-02
    // Verification: STO-01
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun heldCallbackRemainsImmutableWhileLaterFreshOutputsReplaceCacheAndCountBusyDrops() = runTest {
        val platform = CapturePlatformFixture()
        val firstBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x41, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte())
        val secondBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte())
        val thirdBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x43, 0x43, 0x45, 0xFF.toByte(), 0xD9.toByte())
        val nativeJpeg = SafeRejectingNativeJpegFacade(
            successfulCompressionCountBeforeRejection = 3,
            successfulBytesForCompression = { compressionCount ->
                when (compressionCount) {
                    1 -> firstBytes
                    2 -> secondBytes
                    3 -> thirdBytes
                    else -> error("Unexpected successful compression $compressionCount")
                }
            },
        )
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        val callbackEntered = CountDownLatch(1)
        val callbackMayReturn = CountDownLatch(1)
        val callbackEntries = AtomicInteger()
        val beforeHold = AtomicReference<FrameSnapshot?>()
        val afterHold = AtomicReference<FrameSnapshot?>()
        SessionHarness(
            // Keep one worker available for fresh encodes while the first delivery callback occupies the other.
            workerThreadCount = 2,
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionHarness.DelayedControlOutcome.Accept,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, parameters)
                val active = harness.session.state.value as ScreenCaptureState.Active
                val registration = harness.session.registerFrameConsumer { frame ->
                    callbackEntries.incrementAndGet()
                    beforeHold.set(copyFrame(frame))
                    callbackEntered.countDown()
                    check(callbackMayReturn.await(5L, TimeUnit.SECONDS)) { "Entered callback was not released" }
                    afterHold.set(copyFrame(frame))
                }

                harness.clock.setDefaultNanos(1_000_000_000L)
                platform.deliverSourceFrame(rgbaSeed = 43)
                check(harness.enterNextControlTask())
                check(harness.enterNextCaptureTask())
                check(harness.enterNextControlTask())
                checkNotNull(harness.enterNextWorker()).awaitSuccessfulCompletion()
                check(harness.enterNextControlTask())
                val callbackTask = checkNotNull(harness.enterNextWorker())
                try {
                    check(callbackTask.awaitEntered()) { "Delivery worker did not enter" }
                    check(callbackEntered.await(5L, TimeUnit.SECONDS)) { "Frame callback did not enter" }

                    val statsBeforeLaterFresh = harness.session.stats.value
                    val readsBeforeLaterFresh = platform.sourceUpdateCount()
                    val compressionsBeforeLaterFresh = nativeJpeg.carrierSnapshot().compressionCount
                    harness.clock.setDefaultNanos(2_000_000_000L)
                    platform.deliverSourceFrame(rgbaSeed = 59)
                    harness.driveUntil {
                        val stats = harness.session.stats.value
                        stats.encodedFrameCount == statsBeforeLaterFresh.encodedFrameCount + 1L &&
                                stats.producedFrameCount == statsBeforeLaterFresh.producedFrameCount + 1L &&
                                stats.droppedDeliveries.byConsumerBusy ==
                                statsBeforeLaterFresh.droppedDeliveries.byConsumerBusy + 1L
                    }
                    drainAcceptedSessionWork(harness)
                    val statsAfterSecond = harness.session.stats.value
                    assertEquals(statsBeforeLaterFresh.encodedFrameCount + 1L, statsAfterSecond.encodedFrameCount)
                    assertEquals(statsBeforeLaterFresh.producedFrameCount + 1L, statsAfterSecond.producedFrameCount)
                    assertEquals(
                        statsBeforeLaterFresh.droppedDeliveries.byConsumerBusy + 1L,
                        statsAfterSecond.droppedDeliveries.byConsumerBusy,
                    )
                    assertEquals(readsBeforeLaterFresh + 1, platform.sourceUpdateCount())
                    assertEquals(compressionsBeforeLaterFresh + 1, nativeJpeg.carrierSnapshot().compressionCount)

                    harness.clock.setDefaultNanos(3_000_000_000L)
                    platform.deliverSourceFrame(rgbaSeed = 83)
                    harness.driveUntil {
                        val stats = harness.session.stats.value
                        stats.encodedFrameCount == statsAfterSecond.encodedFrameCount + 1L &&
                                stats.producedFrameCount == statsAfterSecond.producedFrameCount + 1L &&
                                stats.droppedDeliveries.byConsumerBusy ==
                                statsAfterSecond.droppedDeliveries.byConsumerBusy + 1L
                    }
                    drainAcceptedSessionWork(harness)
                    val statsAfterThird = harness.session.stats.value

                    assertEquals(1, callbackEntries.get())
                    assertEquals(statsAfterSecond.encodedFrameCount + 1L, statsAfterThird.encodedFrameCount)
                    assertEquals(statsAfterSecond.producedFrameCount + 1L, statsAfterThird.producedFrameCount)
                    assertEquals(statsBeforeLaterFresh.frameProductionDrops, statsAfterThird.frameProductionDrops)
                    assertEquals(
                        statsBeforeLaterFresh.droppedDeliveries.byCallbackFailure,
                        statsAfterThird.droppedDeliveries.byCallbackFailure,
                    )
                    assertEquals(
                        statsAfterSecond.droppedDeliveries.byConsumerBusy + 1L,
                        statsAfterThird.droppedDeliveries.byConsumerBusy,
                    )
                    assertEquals(readsBeforeLaterFresh + 2, platform.sourceUpdateCount())
                    assertEquals(compressionsBeforeLaterFresh + 2, nativeJpeg.carrierSnapshot().compressionCount)
                } finally {
                    callbackMayReturn.countDown()
                    callbackTask.awaitSuccessfulCompletion()
                }

                val originalBeforeHold = checkNotNull(beforeHold.get())
                val originalAfterHold = checkNotNull(afterHold.get())
                assertArrayEquals(firstBytes, originalBeforeHold.bytes)
                assertArrayEquals(originalBeforeHold.bytes, originalAfterHold.bytes)
                assertEquals(originalBeforeHold.sequence, originalAfterHold.sequence)
                assertEquals(
                    originalBeforeHold.outputTimestampElapsedRealtimeNanos,
                    originalAfterHold.outputTimestampElapsedRealtimeNanos,
                )
                assertEquals(originalBeforeHold.outputInfo, originalAfterHold.outputInfo)

                val unregister = async(UnconfinedTestDispatcher(testScheduler)) { registration.unregister() }
                harness.driveUntil { unregister.isCompleted }
                unregister.await()

                val statsBeforeCachedDelivery = harness.session.stats.value
                val lateFrames = CopyOnWriteArrayList<FrameSnapshot>()
                harness.clock.setDefaultNanos(4_000_000_000L)
                val lateRegistration = harness.session.registerFrameConsumer { frame -> lateFrames += copyFrame(frame) }
                harness.driveUntil { lateFrames.size == 1 }
                drainAcceptedSessionWork(harness)
                val cached = lateFrames.single()
                assertArrayEquals(thirdBytes, cached.bytes)
                assertTrue(!originalBeforeHold.bytes.contentEquals(cached.bytes))
                assertEquals(originalBeforeHold.sequence + 2L, cached.sequence)
                assertEquals(3_000_000_000L, cached.outputTimestampElapsedRealtimeNanos)
                assertEquals(active.outputInfo, cached.outputInfo)
                assertEquals(statsBeforeCachedDelivery, harness.session.stats.value)

                val unregisterLate = async(UnconfinedTestDispatcher(testScheduler)) { lateRegistration.unregister() }
                harness.driveUntil { unregisterLate.isCompleted }
                unregisterLate.await()
            } finally {
                callbackMayReturn.countDown()
                requestStopAndDrainSession(harness)
                nativeJpeg.close()
            }
        }
    }

    // Verification: SES-05
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun rejectedDelayedPacingPostFailsWithInternalFailure() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 2)
        val parameters = ScreenCaptureParameters(
            outputSize = OutputSize.ScaleFactor(1.0),
            frameRate = FrameRate.MaxFps(10),
        )
        SessionHarness(
            bootstrapMode = SessionHarness.BootstrapMode.ImmediateMetrics,
            delayedControlOutcome = SessionHarness.DelayedControlOutcome.Reject,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            platformSdkInt = Build.VERSION_CODES.TIRAMISU,
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
            jpegBackendPolicy = JpegBackendPolicy.Auto,
            nativeJpeg = nativeJpeg,
        ).use { harness ->
            try {
                startActiveSession(harness, parameters)
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

}
