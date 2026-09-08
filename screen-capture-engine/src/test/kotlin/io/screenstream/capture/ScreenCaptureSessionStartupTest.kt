package io.screenstream.capture

import io.mockk.Called
import io.mockk.verify
import io.screenstream.capture.testutil.DispatchAttemptKind
import io.screenstream.capture.testutil.DispatchOutcome
import io.screenstream.capture.testutil.ScreenCaptureSessionIntegrationFixture.HappyCapturePlatform
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class ScreenCaptureSessionStartupTest {
    // Verification: API-03
    @Test
    fun cancelledCallerEnteringFreshStartRequestsSessionStop() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val projection = harness.projection()
            val initialStats = harness.session.stats.value
            val cancelledJob = Job(coroutineContext[Job]).apply { cancel() }
            var startCallEntered = false
            var startReturnedNormally = false
            var startThrewCancellation = false

            val result = CoroutineScope(coroutineContext + cancelledJob).async(start = CoroutineStart.UNDISPATCHED) {
                startCallEntered = true
                try {
                    harness.session.start()
                    startReturnedNormally = true
                } catch (failure: CancellationException) {
                    startThrewCancellation = true
                    throw failure
                }
            }

            try {
                result.await()
                fail("start completed from an already-cancelled caller context")
            } catch (_: CancellationException) {
            }

            assertTrue(startCallEntered)
            assertTrue(startThrewCancellation)
            assertFalse(startReturnedNormally)
            assertEquals(0, harness.clock.readCount())
            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(initialStats, harness.session.stats.value)
            val retirement = harness.enterNextWorker() ?: error("Accepted projection retirement was not retained")
            retirement.awaitSuccessfulCompletion()
            assertEquals(1, harness.workerDispatcher.submissions().size)
            assertTrue(harness.delayedEntryScheduler.submissions().isEmpty())
            assertPlatformFree(harness)
            verify(exactly = 1) { projection.stop() }
        }
    }

    // Verification: API-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellationObservedInsidePublicationGateLeavesNoAdmissionOrWork() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val projection = harness.projection()
            val initialStats = harness.session.stats.value
            val callerJob = Job()
            val expectedCancellation = CancellationException("cancel after admission clock read")
            harness.clock.enqueueValue(0L) {
                callerJob.cancel(expectedCancellation)
            }

            val result = startWithCallerContext(harness.session, callerJob)

            assertSame(expectedCancellation, result.exceptionOrNull())
            assertTrue(callerJob.isCancelled)
            assertEquals(1, harness.clock.readCount())
            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertSame(initialStats, harness.session.stats.value)
            val retirement = harness.enterNextWorker() ?: error("Accepted projection retirement was not retained")
            retirement.awaitSuccessfulCompletion()
            assertNoStartWork(harness)

            verify(exactly = 1) { projection.stop() }
        }
    }

    // Verification: API-03
    @Test
    fun invalidAdmissionClockFailsInternalBeforeAdmission() = runTest {
        val cases = listOf<Pair<String, (SessionStartHarness) -> Unit>>(
            "clock exception" to { harness -> harness.clock.enqueueFailure(IllegalStateException("clock failed")) },
            "negative clock" to { harness -> harness.clock.enqueueValue(-1L) },
            "deadline overflow" to { harness -> harness.clock.enqueueValue(Long.MAX_VALUE) },
        )

        cases.forEach { (name, arrangeClock) ->
            SessionStartHarness().use { harness ->
                val projection = harness.projection()
                val initialState = harness.session.state.value
                val initialStats = harness.session.stats.value
                arrangeClock(harness)

                val failure = try {
                    harness.session.start()
                    fail("$name did not fail start")
                    error("unreachable")
                } catch (failure: ScreenCaptureException) {
                    failure
                }

                assertSame(name, ScreenCaptureProblem.InternalFailure, failure.problem)
                assertEquals(name, 1, harness.clock.readCount())
                assertEquals(name, initialState, harness.session.state.value)
                assertEquals(name, initialStats, harness.session.stats.value)
                assertTrue(name, harness.workerDispatcher.submissions().isEmpty())
                assertTrue(name, harness.delayedEntryScheduler.submissions().isEmpty())
                assertPlatformFree(harness)
                verify { projection wasNot Called }

                harness.session.stop()
                val retirement = harness.enterNextWorker() ?: error("Accepted projection retirement was not retained")
                retirement.awaitSuccessfulCompletion()
                verify(exactly = 1) { projection.stop() }
            }
        }
    }

    // Verification: API-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun overlappingStartsRejectSecondAndCancelFirst() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val acceptedProjection = harness.projection()
            val rejectedProjection = harness.projection()
            val acceptedParameters = ScreenCaptureParameters(jpegQuality = 81)
            val initialStats = harness.session.stats.value

            val acceptedStart = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                harness.session.start(acceptedParameters)
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(acceptedStart.isCompleted)
            val workerSubmissionsBeforeLoser = harness.workerDispatcher.submissions().map { it.kind }
            val delayedSubmissionsBeforeLoser = harness.delayedEntryScheduler.submissions().map { it.kind }
            assertEquals(initialStats, harness.session.stats.value)
            assertPlatformFree(harness)
            verify { acceptedProjection wasNot Called }

            try {
                harness.session.start(ScreenCaptureParameters(jpegQuality = 82))
                fail("overlapping start was accepted")
            } catch (_: IllegalStateException) {
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertEquals(workerSubmissionsBeforeLoser, harness.workerDispatcher.submissions().map { it.kind })
            assertEquals(delayedSubmissionsBeforeLoser, harness.delayedEntryScheduler.submissions().map { it.kind })
            assertEquals(initialStats, harness.session.stats.value)
            assertPlatformFree(harness)
            verify { rejectedProjection wasNot Called }

            acceptedStart.cancel()
            runCurrent()
            try {
                acceptedStart.await()
                fail("cancelled accepted start completed successfully")
            } catch (_: CancellationException) {
            }

            val stopped = harness.session.state.value as ScreenCaptureState.Stopped
            assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
            assertEquals(acceptedParameters, stopped.requestedParameters)
            assertNull(stopped.lastEffectiveParameters)
            assertZeroStats(harness.session.stats.value)
            assertPlatformFree(harness)
            verify { acceptedProjection wasNot Called }
            verify { rejectedProjection wasNot Called }
        }
    }

    // Verification: API-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledLosingStartCannotStopAdmittedWinner() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val projection = harness.projection()
            val winnerParameters = ScreenCaptureParameters(jpegQuality = 81)
            val winner = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                harness.session.start(winnerParameters)
            }
            val loserJob = Job(coroutineContext[Job]).apply { cancel() }
            var loserEntered = false
            var loserCaughtCancellation = false
            val loser = CoroutineScope(coroutineContext + loserJob).async(start = CoroutineStart.UNDISPATCHED) {
                loserEntered = true
                try {
                    harness.session.start(ScreenCaptureParameters(jpegQuality = 82))
                    fail("cancelled losing start completed normally")
                } catch (cancellation: CancellationException) {
                    loserCaughtCancellation = true
                    throw cancellation
                }
            }

            try {
                try {
                    loser.await()
                    fail("cancelled losing start completed normally")
                } catch (_: CancellationException) {
                }
                assertTrue(loserEntered)
                assertTrue(loserCaughtCancellation)
                assertSame(ScreenCaptureState.Starting, harness.session.state.value)
                verify { projection wasNot Called }

                winner.cancel()
                runCurrent()
                val stopped = harness.session.state.value as ScreenCaptureState.Stopped
                assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
                assertEquals(winnerParameters, stopped.requestedParameters)
            } finally {
                winner.cancel()
                loser.cancel()
                runCurrent()
                while (harness.workerDispatcher.pendingCount() > 0) {
                    harness.enterNextWorker()?.awaitSuccessfulCompletion()
                }
            }
        }
    }

    // Verification: API-03
    @Test
    fun requestedStartupCancellationIsCatchableInAStillActiveCaller() = runTest {
        SessionStartHarness(bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics).use { harness ->
            val callerJob = Job()
            var callerWasActiveWhenCaught = false
            val operation = CoroutineScope(coroutineContext + callerJob).async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    harness.session.start()
                    fail("requested startup cancellation completed normally")
                } catch (cancellation: CancellationException) {
                    callerWasActiveWhenCaught = currentCoroutineContext().isActive
                    cancellation
                }
            }

            harness.session.stop()
            val cancellation = operation.await()
            assertTrue(cancellation is CancellationException)
            assertTrue(callerWasActiveWhenCaught)
            assertTrue(callerJob.isActive)
            callerJob.cancel()
            while (harness.workerDispatcher.pendingCount() > 0) {
                harness.enterNextWorker()?.awaitSuccessfulCompletion()
            }
        }
    }

    // Verification: API-03
    @Test
    fun projectionStoppedStartupCancellationIsCatchableBeforeActive() = runTest {
        val platform = HappyCapturePlatform()
        val parameters = ScreenCaptureParameters(outputSize = OutputSize.ScaleFactor(1.0))
        SessionStartHarness(
            bootstrapMode = SessionStartHarness.BootstrapMode.ImmediateMetrics,
            metrics = CaptureMetrics(widthPx = 8, heightPx = 6, densityDpi = 320),
            projection = platform.projection,
            projectionPlatform = platform.projectionPlatform,
            eglPlatform = platform.eglPlatform,
            glesPlatform = platform.glesPlatform,
            targetPlatform = platform.targetPlatform,
        ).use { harness ->
            val callerJob = Job()
            var callerWasActiveWhenCaught = false
            val operation = CoroutineScope(coroutineContext + callerJob).async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    harness.session.start(parameters)
                    fail("projection stop completed startup normally")
                } catch (cancellation: CancellationException) {
                    callerWasActiveWhenCaught = currentCoroutineContext().isActive
                    cancellation
                }
            }

            repeat(32) {
                if (platform.initialVirtualDisplayReturned()) return@repeat
                var progressed = harness.enterNextWorkerSuccessfully()
                progressed = harness.enterNextControlTask() || progressed
                progressed = harness.enterNextCaptureTask() || progressed
                check(progressed || platform.initialVirtualDisplayReturned()) {
                    "Capture setup became idle before projection callback registration"
                }
            }
            check(platform.initialVirtualDisplayReturned()) { "Projection callback was not registered" }
            assertFalse(operation.isCompleted)
            platform.deliverProjectionStopped()

            repeat(32) {
                if (harness.session.state.value is ScreenCaptureState.Stopped) return@repeat
                var progressed = harness.enterNextWorkerSuccessfully()
                progressed = harness.enterNextControlTask() || progressed
                progressed = harness.enterNextCaptureTask() || progressed
                check(progressed) { "Projection stop became idle before terminal publication" }
            }
            assertTrue(harness.session.state.value is ScreenCaptureState.Stopped)
            val cancellation = operation.await()
            assertTrue(cancellation is CancellationException)
            assertTrue(callerWasActiveWhenCaught)
            assertTrue(callerJob.isActive)
            callerJob.cancel()
            while (harness.workerDispatcher.pendingCount() > 0) {
                harness.enterNextWorker()?.awaitSuccessfulCompletion()
            }
        }
    }

    // Verification: API-03
    // Verification: SES-01
    @Test
    fun bootstrapDispatchRejectionFailsStartInternally() = runTest {
        SessionStartHarness(workerOutcome = DispatchOutcome.Reject).use { harness ->
            val projection = harness.projection()
            val parameters = ScreenCaptureParameters(jpegQuality = 81)

            val failure = try {
                harness.session.start(parameters)
                fail("rejected Bootstrap dispatch completed start")
                error("unreachable")
            } catch (failure: ScreenCaptureException) {
                failure
            }

            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("Bootstrap dispatch was rejected", failure.cause?.message)
            assertSame(ScreenCaptureState.Starting, harness.workerSubmissionStates().first())
            assertTrue(harness.workerDispatcher.submissions().isNotEmpty())
            assertTrue(harness.workerDispatcher.submissions().none { it.kind == DispatchAttemptKind.Accepted })

            val failed = harness.session.state.value as ScreenCaptureState.Failed
            assertSame(ScreenCaptureProblem.InternalFailure, failed.problem)
            assertEquals(parameters, failed.requestedParameters)
            assertNull(failed.lastEffectiveParameters)
            assertZeroStats(harness.session.stats.value)
            assertPlatformFree(harness)
            verify { projection wasNot Called }
        }
    }

    private fun assertPlatformFree(harness: SessionStartHarness) {
        assertEquals(0, harness.metricsSubscriptionCount())
        assertEquals(0, harness.handlerPlatformCallCount())
        assertEquals(0, harness.handlerPostCallCount())
    }

    private fun assertNoStartWork(harness: SessionStartHarness) {
        assertEquals(1, harness.workerSubmissionStates().size)
        assertTrue(harness.workerSubmissionStates().single() is ScreenCaptureState.Stopped)
        assertEquals(1, harness.workerDispatcher.submissions().size)
        assertTrue(harness.delayedEntryScheduler.submissions().isEmpty())
        assertPlatformFree(harness)
    }

    private fun startWithCallerContext(session: ScreenCaptureSession, callerContext: CoroutineContext): Result<Unit> {
        var outcome: Result<Unit>? = null
        suspend { session.start() }.startCoroutine(object : Continuation<Unit> {
            override val context: CoroutineContext = callerContext

            override fun resumeWith(result: Result<Unit>) {
                check(outcome == null)
                outcome = result
            }
        })
        return outcome ?: error("Pre-admission cancellation unexpectedly suspended start")
    }

    private fun assertZeroStats(stats: ScreenCaptureStats) {
        assertEquals(0L, stats.encodedFrameCount)
        assertEquals(0L, stats.producedFrameCount)
        assertEquals(0L, stats.droppedFrames.byStaleWork)
        assertEquals(0L, stats.droppedFrames.byFailure)
        assertEquals(0L, stats.droppedFrames.total)
        assertEquals(0L, stats.droppedDeliveries.byConsumerBusy)
        assertEquals(0L, stats.droppedDeliveries.byCallbackFailure)
        assertEquals(0L, stats.droppedDeliveries.total)
        assertEquals(0.0, stats.averageProducedFps, 0.0)
        assertEquals(Duration.ZERO, stats.averageEncodingDuration)
        assertEquals(Duration.ZERO, stats.averageReadbackDuration)
        assertEquals(0, stats.lastEncodedByteCount)
        assertEquals(0, stats.averageEncodedByteCount)
    }
}
