package io.screenstream.capture

import android.os.Build
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.screenstream.capture.internal.capture.CaptureReadResult
import io.screenstream.capture.internal.encoding.EncodingResult
import io.screenstream.capture.internal.encoding.NativeJpegFacade
import io.screenstream.capture.internal.session.SessionCoordinator
import io.screenstream.capture.internal.session.SessionEncodingLink
import io.screenstream.capture.internal.session.production.SessionReadBridge
import io.screenstream.capture.testutil.ControlledNonInlineDispatcher
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.CapturePlatformFixture
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.NativeCarrierSnapshot
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.SafeRejectingNativeJpegFacade
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.requestStopAndDrainSession
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.startActiveSession
import io.screenstream.capture.testutil.SessionHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.nanoseconds

/*
 * Public pre-freeze accounting evidence through the real Coordinator, Capture, Encoding, and typed Links.
 *
 * Scoped private-call interception arranges stop after a real Link selects its actual fact and releases the Session
 * gate. It calls the original consumer with that same argument. Exact physical returns, public State/Stats,
 * forbidden output, and resource settlement decide each scenario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
internal class ScreenCaptureSessionPrefreezeAccountingTest {
    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun requestStopOnlySelectedFilledRetainsReadbackSampleAndDiscardsWithoutEncoding() = runTest {
        val harnessRef = AtomicReference<SessionHarness?>()
        val stopIssued = AtomicBoolean()
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        var harness: SessionHarness? = null
        var primaryFailure: Throwable? = null

        mockkConstructor(SessionCoordinator::class, recordPrivateCalls = true)
        try {
            every {
                anyConstructed<SessionCoordinator>() invoke "consumeRead" withArguments
                        listOf(any<SessionReadBridge>())
            } answers {
                val read = firstArg<SessionReadBridge>()
                val result = read.requireClaimedResult()
                check(result is CaptureReadResult.Filled) {
                    "Expected the selected actual Capture return to be Filled, got ${result::class.java.name}"
                }
                assertEquals(READBACK_DURATION_NANOS, result.readbackDurationNanos)
                if (stopIssued.compareAndSet(false, true)) {
                    val session = checkNotNull(harnessRef.get()).session
                    session.requestStop()
                    assertThrows(IllegalStateException::class.java) {
                        session.updateParameters(parameters)
                    }
                }
                callOriginal()
            }

            harness = newHarness(platform, nativeJpeg)
            val exactHarness = harness
            harnessRef.set(exactHarness)
            startActiveSession(exactHarness, parameters)
            val active = exactHarness.session.state.value as ScreenCaptureState.Active
            val baselineStats = exactHarness.session.stats.value
            val delivered = CopyOnWriteArrayList<EncodedFrame>()
            exactHarness.session.registerFrameConsumer { frame -> delivered += frame }
            check(exactHarness.enterNextControlTask()) { "Consumer registration was not offered to Control" }

            exactHarness.clock.setDefaultNanos(READBACK_STARTED_NANOS)
            platform.runOnceDuringNextReadback {
                exactHarness.clock.setDefaultNanos(READBACK_STARTED_NANOS + READBACK_DURATION_NANOS)
            }
            platform.deliverSourceFrame(rgbaSeed = 41)
            check(exactHarness.enterNextControlTask()) { "Fresh source was not consumed" }
            check(exactHarness.enterNextCaptureTask()) { "Capture read did not enter" }
            check(exactHarness.enterNextControlTask()) { "Selected Filled return was not consumed" }
            exactHarness.driveUntil { exactHarness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = exactHarness.session.state.value as ScreenCaptureState.Stopped
            val finalStats = exactHarness.session.stats.value
            assertTrue(stopIssued.get())
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)
            assertTrue(delivered.isEmpty())
            assertEquals(baselineStats.encodedFrameCount, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
            assertEquals(baselineStats.frameProductionDrops.byStaleWork, finalStats.frameProductionDrops.byStaleWork)
            assertEquals(baselineStats.frameProductionDrops.byFailure, finalStats.frameProductionDrops.byFailure)
            assertEquals(READBACK_DURATION_NANOS.nanoseconds, finalStats.averageReadbackDuration)
            assertEquals(baselineStats.averageEncodingDuration, finalStats.averageEncodingDuration)
            assertEquals(baselineStats.lastEncodedByteCount, finalStats.lastEncodedByteCount)

            exactHarness.drainWorkerTasks()
            assertRetiredCarrier(nativeJpeg.carrierSnapshot(), compressionCount = 0, resultBlockCount = 0)
            assertEquals(stopped, exactHarness.session.state.value)
            assertEquals(finalStats, exactHarness.session.stats.value)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupPreservingPrimary(
                primaryFailure,
                { harnessRef.getAndSet(null)?.let(::requestStopAndDrainSession) },
                { nativeJpeg.close() },
                { harness?.close() },
                { unmockkConstructor(SessionCoordinator::class) },
            )
        }
    }

    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun requestStopOnlySelectedEncodedRetainsTimingAndSizeWithoutOutputOrStale() = runTest {
        val harnessRef = AtomicReference<SessionHarness?>()
        val stopIssued = AtomicBoolean()
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade(successfulCompressionCountBeforeRejection = 1)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        var harness: SessionHarness? = null
        var encodingTask: ControlledNonInlineDispatcher.TaskHandle? = null
        var primaryFailure: Throwable? = null

        mockkConstructor(SessionCoordinator::class, recordPrivateCalls = true)
        try {
            every {
                anyConstructed<SessionCoordinator>() invoke "consumeEncoding" withArguments
                        listOf(any<SessionEncodingLink.ProductionFact>())
            } answers {
                val fact = firstArg<SessionEncodingLink.ProductionFact>()
                val result = fact.result
                check(result is EncodingResult.Encoded) {
                    "Expected the selected actual Encoding return to be Encoded, got ${result::class.java.name}"
                }
                assertEquals(ENCODE_DURATION_NANOS, result.encodeDurationNanos)
                assertEquals(ENCODED_BYTE_COUNT, result.payload.byteCount)
                if (stopIssued.compareAndSet(false, true)) {
                    val session = checkNotNull(harnessRef.get()).session
                    session.requestStop()
                    assertThrows(IllegalStateException::class.java) {
                        session.updateParameters(parameters)
                    }
                }
                callOriginal()
            }

            harness = newHarness(platform, nativeJpeg)
            val exactHarness = harness
            harnessRef.set(exactHarness)
            startActiveSession(exactHarness, parameters)
            val active = exactHarness.session.state.value as ScreenCaptureState.Active
            val baselineStats = exactHarness.session.stats.value
            val delivered = CopyOnWriteArrayList<EncodedFrame>()
            exactHarness.session.registerFrameConsumer { frame -> delivered += frame }
            check(exactHarness.enterNextControlTask()) { "Consumer registration was not offered to Control" }

            exactHarness.clock.setDefaultNanos(READBACK_STARTED_NANOS)
            platform.runOnceDuringNextReadback {
                exactHarness.clock.setDefaultNanos(READBACK_STARTED_NANOS + READBACK_DURATION_NANOS)
            }
            platform.deliverSourceFrame(rgbaSeed = 43)
            check(exactHarness.enterNextControlTask()) { "Fresh source was not consumed" }
            check(exactHarness.enterNextCaptureTask()) { "Capture read did not enter" }
            check(exactHarness.enterNextControlTask()) { "Filled return was not consumed" }

            exactHarness.clock.enqueueValue(ENCODE_STARTED_NANOS)
            exactHarness.clock.enqueueValue(ENCODE_STARTED_NANOS + ENCODE_DURATION_NANOS)
            encodingTask = checkNotNull(exactHarness.enterNextWorker())
            encodingTask.awaitSuccessfulCompletion()
            val afterActualReturn = nativeJpeg.carrierSnapshot()
            assertEquals(1, afterActualReturn.allocationCount)
            assertEquals(1, afterActualReturn.compressionCount)
            assertEquals(1, afterActualReturn.resultBlockCount)
            assertEquals(1, afterActualReturn.outstandingCount)

            check(exactHarness.enterNextControlTask()) { "Selected Encoded fact was not consumed" }
            exactHarness.driveUntil { exactHarness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = exactHarness.session.state.value as ScreenCaptureState.Stopped
            val finalStats = exactHarness.session.stats.value
            assertTrue(stopIssued.get())
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)
            assertTrue(delivered.isEmpty())
            assertEquals(baselineStats.encodedFrameCount + 1L, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
            assertEquals(baselineStats.frameProductionDrops.byStaleWork, finalStats.frameProductionDrops.byStaleWork)
            assertEquals(baselineStats.frameProductionDrops.byFailure, finalStats.frameProductionDrops.byFailure)
            assertEquals(READBACK_DURATION_NANOS.nanoseconds, finalStats.averageReadbackDuration)
            assertEquals(ENCODE_DURATION_NANOS.nanoseconds, finalStats.averageEncodingDuration)
            assertEquals(ENCODED_BYTE_COUNT, finalStats.lastEncodedByteCount)
            assertEquals(ENCODED_BYTE_COUNT, finalStats.averageEncodedByteCount)

            exactHarness.drainWorkerTasks()
            assertRetiredCarrier(nativeJpeg.carrierSnapshot(), compressionCount = 1, resultBlockCount = 1)
            assertEquals(stopped, exactHarness.session.state.value)
            assertEquals(finalStats, exactHarness.session.stats.value)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanupPreservingPrimary(
                primaryFailure,
                { encodingTask?.awaitSuccessfulCompletion() },
                { harnessRef.getAndSet(null)?.let(::requestStopAndDrainSession) },
                { nativeJpeg.close() },
                { harness?.close() },
                { unmockkConstructor(SessionCoordinator::class) },
            )
        }
    }

    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun selectedActualNativeRejectionCountsBeforeRequestStopWithoutReconfigurationOrOutput() = runTest {
        val harnessRef = AtomicReference<SessionHarness?>()
        val stopIssued = AtomicBoolean()
        val postStopInterval = AtomicBoolean()
        val postStopStates = CopyOnWriteArrayList<ScreenCaptureState>()
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade(blockCompression = true)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        var harness: SessionHarness? = null
        var stateCollector: Job? = null
        var encodingTask: ControlledNonInlineDispatcher.TaskHandle? = null
        var primaryFailure: Throwable? = null

        mockkConstructor(SessionCoordinator::class, recordPrivateCalls = true)
        try {
            every {
                anyConstructed<SessionCoordinator>() invoke "consumeEncoding" withArguments
                        listOf(any<SessionEncodingLink.ProductionFact>())
            } answers {
                val fact = firstArg<SessionEncodingLink.ProductionFact>()
                check(fact.result === EncodingResult.ReadinessChanged) {
                    "Expected the selected actual Native rejection to return ReadinessChanged, got ${fact.result::class.java.name}"
                }
                if (stopIssued.compareAndSet(false, true)) {
                    val session = checkNotNull(harnessRef.get()).session
                    session.requestStop()
                    postStopInterval.set(true)
                    assertThrows(IllegalStateException::class.java) {
                        session.updateParameters(parameters)
                    }
                }
                callOriginal()
            }

            harness = newHarness(platform, nativeJpeg)
            val exactHarness = harness
            harnessRef.set(exactHarness)
            startActiveSession(exactHarness, parameters)
            val active = exactHarness.session.state.value as ScreenCaptureState.Active
            val baselineStats = exactHarness.session.stats.value
            val delivered = CopyOnWriteArrayList<EncodedFrame>()
            exactHarness.session.registerFrameConsumer { frame -> delivered += frame }
            check(exactHarness.enterNextControlTask()) { "Consumer registration was not offered to Control" }

            // The non-suspending collector is installed before the selected Control entry to observe this serialized
            // post-stop interval. These observations are not a general StateFlow publication history.
            stateCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                exactHarness.session.state.collect { state ->
                    if (postStopInterval.get()) postStopStates += state
                }
            }

            exactHarness.clock.setDefaultNanos(2_000_000_000L)
            platform.deliverSourceFrame(rgbaSeed = 47)
            encodingTask = enterWorkUntilNativeCompressionBlocks(exactHarness, nativeJpeg)

            nativeJpeg.releaseCompression()
            nativeJpeg.awaitCompressionReturned()
            encodingTask.awaitSuccessfulCompletion()
            val afterActualReturn = nativeJpeg.carrierSnapshot()
            assertEquals(1, afterActualReturn.allocationCount)
            assertEquals(1, afterActualReturn.compressionCount)
            assertEquals(1, afterActualReturn.resultBlockCount)
            assertEquals(1, afterActualReturn.outstandingCount)

            check(exactHarness.enterNextControlTask()) { "Selected Encoding fact was not consumed" }
            exactHarness.driveUntil { exactHarness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = exactHarness.session.state.value as ScreenCaptureState.Stopped
            val finalStats = exactHarness.session.stats.value
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)
            assertTrue(delivered.isEmpty())
            assertEquals(baselineStats.encodedFrameCount, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
            assertEquals(baselineStats.frameProductionDrops.byStaleWork, finalStats.frameProductionDrops.byStaleWork)
            assertEquals(
                baselineStats.frameProductionDrops.byFailure + 1L,
                finalStats.frameProductionDrops.byFailure,
            )

            exactHarness.drainWorkerTasks()
            assertRetiredCarrier(nativeJpeg.carrierSnapshot(), compressionCount = 1, resultBlockCount = 1)
            assertEquals(stopped, exactHarness.session.state.value)
            assertEquals(finalStats, exactHarness.session.stats.value)
            assertTrue(postStopStates.any { state -> state is ScreenCaptureState.Stopped })
            assertTrue(
                postStopStates.none { state ->
                    state is ScreenCaptureState.Reconfiguring || state is ScreenCaptureState.Active
                },
            )
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            nativeJpeg.releaseCompression()
            cleanupPreservingPrimary(
                primaryFailure,
                { encodingTask?.awaitSuccessfulCompletion() },
                { harnessRef.getAndSet(null)?.let(::requestStopAndDrainSession) },
                { nativeJpeg.close() },
                { harness?.close() },
                { stateCollector?.cancelAndJoin() },
                { unmockkConstructor(SessionCoordinator::class) },
            )
        }
    }

    // Verification: SES-07
    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun requestStopBeforeEncodingSelectionExcludesActualNativeRejectionFromFinalAccounting() = runTest {
        val platform = CapturePlatformFixture()
        val nativeJpeg = SafeRejectingNativeJpegFacade(blockCompression = true)
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        var harness: SessionHarness? = null
        var encodingTask: ControlledNonInlineDispatcher.TaskHandle? = null
        var primaryFailure: Throwable? = null

        try {
            harness = newHarness(platform, nativeJpeg)
            val exactHarness = harness
            startActiveSession(exactHarness, parameters)
            val active = exactHarness.session.state.value as ScreenCaptureState.Active
            val baselineStats = exactHarness.session.stats.value
            val delivered = CopyOnWriteArrayList<EncodedFrame>()
            exactHarness.session.registerFrameConsumer { frame -> delivered += frame }
            check(exactHarness.enterNextControlTask()) { "Consumer registration was not offered to Control" }

            exactHarness.clock.setDefaultNanos(2_000_000_000L)
            platform.deliverSourceFrame(rgbaSeed = 53)
            encodingTask = enterWorkUntilNativeCompressionBlocks(exactHarness, nativeJpeg)

            nativeJpeg.releaseCompression()
            nativeJpeg.awaitCompressionReturned()
            encodingTask.awaitSuccessfulCompletion()
            val afterActualReturn = nativeJpeg.carrierSnapshot()
            assertEquals(1, afterActualReturn.allocationCount)
            assertEquals(1, afterActualReturn.compressionCount)
            assertEquals(1, afterActualReturn.resultBlockCount)
            assertEquals(1, afterActualReturn.outstandingCount)

            exactHarness.session.requestStop()
            assertThrows(IllegalStateException::class.java) {
                exactHarness.session.updateParameters(parameters)
            }
            exactHarness.driveUntil { exactHarness.session.state.value is ScreenCaptureState.Stopped }

            val stopped = exactHarness.session.state.value as ScreenCaptureState.Stopped
            val finalStats = exactHarness.session.stats.value
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(active.outputInfo, stopped.lastOutputInfo)
            assertTrue(delivered.isEmpty())
            assertEquals(baselineStats.encodedFrameCount, finalStats.encodedFrameCount)
            assertEquals(baselineStats.producedFrameCount, finalStats.producedFrameCount)
            assertEquals(baselineStats.frameProductionDrops.byStaleWork, finalStats.frameProductionDrops.byStaleWork)
            assertEquals(baselineStats.frameProductionDrops.byFailure, finalStats.frameProductionDrops.byFailure)

            exactHarness.drainWorkerTasks()
            assertRetiredCarrier(nativeJpeg.carrierSnapshot(), compressionCount = 1, resultBlockCount = 1)
            assertEquals(stopped, exactHarness.session.state.value)
            assertEquals(finalStats, exactHarness.session.stats.value)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            nativeJpeg.releaseCompression()
            cleanupPreservingPrimary(
                primaryFailure,
                { encodingTask?.awaitSuccessfulCompletion() },
                { harness?.let(::requestStopAndDrainSession) },
                { nativeJpeg.close() },
                { harness?.close() },
            )
        }
    }

    private fun newHarness(
        platform: CapturePlatformFixture,
        nativeJpeg: NativeJpegFacade,
    ): SessionHarness = SessionHarness(
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
    )

    private fun enterWorkUntilNativeCompressionBlocks(
        harness: SessionHarness,
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

    private fun assertRetiredCarrier(
        snapshot: NativeCarrierSnapshot,
        compressionCount: Int,
        resultBlockCount: Int,
    ) {
        assertEquals(1, snapshot.allocationCount)
        assertEquals(0, snapshot.outstandingCount)
        assertEquals(1, snapshot.freeAttemptCount)
        assertEquals(1, snapshot.freedCount)
        assertEquals(compressionCount, snapshot.compressionCount)
        assertEquals(resultBlockCount, snapshot.resultBlockCount)
        assertSame(snapshot.allocatedCarrier, snapshot.freeAttemptCarrier)
        assertSame(snapshot.allocatedCarrier, snapshot.freedCarrier)
    }

    private suspend fun cleanupPreservingPrimary(
        primaryFailure: Throwable?,
        vararg actions: suspend () -> Unit,
    ) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (failure: Throwable) {
                val anchor = primaryFailure ?: cleanupFailure
                if (anchor == null) {
                    cleanupFailure = failure
                } else if (failure !== anchor) {
                    anchor.addSuppressed(failure)
                }
            }
        }
        if (primaryFailure == null) cleanupFailure?.let { throw it }
    }

    private companion object {
        private const val READBACK_STARTED_NANOS = 2_000_000_000L
        private const val READBACK_DURATION_NANOS = 5_000_000L
        private const val ENCODE_STARTED_NANOS = 3_000_000_000L
        private const val ENCODE_DURATION_NANOS = 7_000_000L
        private const val ENCODED_BYTE_COUNT = 7
    }
}
