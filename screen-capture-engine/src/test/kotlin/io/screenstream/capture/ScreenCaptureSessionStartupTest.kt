package io.screenstream.capture

import android.media.projection.MediaProjection
import io.mockk.Called
import io.mockk.verify
import io.screenstream.capture.testutil.DispatchAttemptKind
import io.screenstream.capture.testutil.DispatchOutcome
import io.screenstream.capture.testutil.SessionStartHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration

internal class ScreenCaptureSessionStartupTest {
    // Verification: API-03
    @Test
    fun cancelledCallerBeforeAdmissionLeavesSessionAndProjectionUntouched() = runTest {
        SessionStartHarness().use { harness ->
            val projection = harness.projection()
            val initialState = harness.session.state.value
            val initialStats = harness.session.stats.value
            val cancelledJob = Job(coroutineContext[Job]).apply { cancel() }
            var startCallEntered = false
            var startReturnedNormally = false
            var startThrewCancellation = false

            val result = CoroutineScope(coroutineContext + cancelledJob).async(start = CoroutineStart.UNDISPATCHED) {
                startCallEntered = true
                try {
                    harness.session.start(projection)
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
            assertEquals(initialState, harness.session.state.value)
            assertEquals(initialStats, harness.session.stats.value)
            assertTrue(harness.workerDispatcher.submissions().isEmpty())
            assertTrue(harness.delayedEntryScheduler.submissions().isEmpty())
            assertPlatformFree(harness)
            verify { projection wasNot Called }
        }
    }

    // Verification: API-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellationObservedInsidePublicationGateLeavesNoAdmissionOrWork() = runTest {
        SessionStartHarness().use { harness ->
            val projection = harness.projection()
            val freshProjection = harness.projection()
            val initialState = harness.session.state.value
            val initialStats = harness.session.stats.value
            val callerJob = Job()
            val expectedCancellation = CancellationException("cancel after admission clock read")
            harness.clock.enqueueValue(0L) {
                callerJob.cancel(expectedCancellation)
            }

            val result = startWithCallerContext(harness.session, projection, callerJob)

            assertSame(expectedCancellation, result.exceptionOrNull())
            assertTrue(callerJob.isCancelled)
            assertEquals(1, harness.clock.readCount())
            assertSame(initialState, harness.session.state.value)
            assertSame(initialStats, harness.session.stats.value)
            assertNoStartWork(harness)

            assertFreshStartAdmittedThenCancel(harness, freshProjection, backgroundScope) { runCurrent() }

            verify { projection wasNot Called }
            verify { freshProjection wasNot Called }
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
                    harness.session.start(projection)
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
            }
        }
    }

    // Verification: API-03
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun overlappingStartsRejectSecondAndCancelFirst() = runTest {
        SessionStartHarness().use { harness ->
            val acceptedProjection = harness.projection()
            val rejectedProjection = harness.projection()
            val acceptedParameters = ScreenCaptureParameters(jpegQuality = 81)
            val initialStats = harness.session.stats.value

            val acceptedStart = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                harness.session.start(acceptedProjection, acceptedParameters)
            }

            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(acceptedStart.isCompleted)
            val workerSubmissionsBeforeLoser = harness.workerDispatcher.submissions().map { it.kind }
            val delayedSubmissionsBeforeLoser = harness.delayedEntryScheduler.submissions().map { it.kind }
            assertEquals(initialStats, harness.session.stats.value)
            assertPlatformFree(harness)
            verify { acceptedProjection wasNot Called }

            try {
                harness.session.start(rejectedProjection, ScreenCaptureParameters(jpegQuality = 82))
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
    // Verification: SES-01
    @Test
    fun bootstrapDispatchRejectionFailsStartInternally() = runTest {
        SessionStartHarness(workerOutcome = DispatchOutcome.Reject).use { harness ->
            val projection = harness.projection()
            val parameters = ScreenCaptureParameters(jpegQuality = 81)

            val failure = try {
                harness.session.start(projection, parameters)
                fail("rejected Bootstrap dispatch completed start")
                error("unreachable")
            } catch (failure: ScreenCaptureException) {
                failure
            }

            assertSame(ScreenCaptureProblem.InternalFailure, failure.problem)
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
        assertTrue(harness.workerSubmissionStates().isEmpty())
        assertTrue(harness.workerDispatcher.submissions().isEmpty())
        assertTrue(harness.delayedEntryScheduler.submissions().isEmpty())
        assertPlatformFree(harness)
    }

    private suspend fun assertFreshStartAdmittedThenCancel(
        harness: SessionStartHarness,
        projection: MediaProjection,
        callerScope: CoroutineScope,
        runCancellation: () -> Unit,
    ) {
        val parameters = ScreenCaptureParameters(jpegQuality = 81)
        val acceptedStart = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
            harness.session.start(projection, parameters)
        }

        var primaryFailure: Throwable? = null
        try {
            assertSame(ScreenCaptureState.Starting, harness.session.state.value)
            assertFalse(acceptedStart.isCompleted)
            assertEquals(1, harness.workerDispatcher.pendingCount())
            assertEquals(1, harness.delayedEntryScheduler.pendingCount())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            try {
                acceptedStart.cancel()
            } catch (failure: Throwable) {
                cleanupFailure = combineFailures(cleanupFailure, failure)
            }
            try {
                runCancellation()
            } catch (failure: Throwable) {
                cleanupFailure = combineFailures(cleanupFailure, failure)
            }
            try {
                acceptedStart.await()
                throw AssertionError("Cancelled residue-probe start completed successfully")
            } catch (_: CancellationException) {
            } catch (failure: Throwable) {
                cleanupFailure = combineFailures(cleanupFailure, failure)
            }
            cleanupFailure?.let { failure ->
                val failureToPreserve = primaryFailure
                if (failureToPreserve != null) failureToPreserve.addSuppressed(failure) else throw failure
            }
        }

        val stopped = harness.session.state.value as ScreenCaptureState.Stopped
        assertSame(ScreenCaptureStopReason.Requested, stopped.reason)
        assertEquals(parameters, stopped.requestedParameters)
        assertNull(stopped.lastEffectiveParameters)
    }

    private fun combineFailures(primary: Throwable?, secondary: Throwable): Throwable {
        primary?.addSuppressed(secondary)
        return primary ?: secondary
    }

    private fun startWithCallerContext(
        session: ScreenCaptureSession,
        projection: MediaProjection,
        callerContext: CoroutineContext,
    ): Result<Unit> {
        var outcome: Result<Unit>? = null
        suspend { session.start(projection) }.startCoroutine(object : Continuation<Unit> {
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
