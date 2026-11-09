package io.screenstream.mjpeg

import android.media.projection.MediaProjection
import io.screenstream.capture.ScreenCaptureConfig
import io.screenstream.capture.ScreenCaptureException
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], shadows = [InertMjpegProjectionShadow::class])
// SDK-invariant MJPEG policy for SDK 24–37. This does not prove platform consent reuse,
// Binder, graphics, or actual callback-thread retirement. The coordinator stores consent only on SDK 24–33.
internal class MjpegCaptureSessionOwnershipTest {
    // Cases: MJPEG-CAP-10, MJPEG-CAP-11
    @Test
    fun exactRegistrationCompletionDoesNotReleaseForegroundBeforeMediaStop() = runTest(StandardTestDispatcher()) {
        val engine = OwnershipEngineSession(holdMediaStop = true, holdConsumerUnregister = true)
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()
            assertTrue(engine.mediaStopEntered.isCompleted)
            assertTrue(engine.registrationCloseEntered.isCompleted)
            assertFalse(engine.registrationRetired.isCompleted)

            engine.releaseRegistration()
            runCurrent()
            assertTrue(engine.registrationRetired.isCompleted)
            assertFalse(engine.mediaStopped.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            assertEquals(MjpegCaptureSession.State.Status.Stopping, session.state.value.status)
            assertFalse(session.state.value.isStreaming)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertTrue(inputs.savedConsents.isEmpty())

            engine.releaseMedia()
            runCurrent()
            assertTrue(awaitCleanup(session))
            assertCleanlyStopped(session)
            assertEquals(listOf("enterForeground", "leaveForeground"), inputs.foregroundEvents)
            assertSame(inputs.grant, inputs.savedConsents.single())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-10, MJPEG-CAP-11
    @Test
    fun mediaStopStillWaitsForExactRegistrationBeforeForegroundAndConsent() = runTest(StandardTestDispatcher()) {
        val engine = OwnershipEngineSession(holdConsumerUnregister = true)
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()

            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(engine.registrationCloseEntered.isCompleted)
            assertFalse(engine.registrationRetired.isCompleted)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertNull(session.state.value.cleanupSucceeded)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(MjpegCaptureSession.State.Status.Stopping, session.state.value.status)
            assertFalse(session.state.value.isStreaming)
            assertTrue(inputs.savedConsents.isEmpty())
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(waiter.isCompleted)

            engine.releaseRegistration()
            runCurrent()
            assertTrue(withTimeout(5.seconds) { waiter.await() })
            assertTrue(engine.registrationRetired.isCompleted)
            assertCleanlyStopped(session)
            assertSame(inputs.grant, inputs.savedConsents.single())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-10, MJPEG-CAP-11
    @Test
    fun enteredCleanupSurvivesOwnerAndWaiterCancellationAndAllRemainingWaitersAgree() = runTest(StandardTestDispatcher()) {
        val ownerJob = Job(coroutineContext[Job])
        val engine = OwnershipEngineSession(holdConsumerUnregister = true)
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.createOwnershipSession(CoroutineScope(coroutineContext + ownerJob), engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(engine.registrationCloseEntered.isCompleted)

            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            val firstWaiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            val secondWaiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            cancelledWaiter.cancelAndJoin()
            ownerJob.cancel()
            session.requestStop(MjpegCaptureSession.StopReason.System)
            runCurrent()

            assertFalse(firstWaiter.isCompleted)
            assertFalse(secondWaiter.isCompleted)
            assertFalse(engine.registrationRetired.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            assertTrue(inputs.savedConsents.isEmpty())

            engine.releaseRegistration()
            runCurrent()
            assertTrue(withTimeout(5.seconds) { firstWaiter.await() })
            assertTrue(withTimeout(5.seconds) { secondWaiter.await() })
            assertTrue(awaitCleanup(session))
            assertCleanlyStopped(session)
            // A later System Stop cannot replace the first accepted User Stop.
            assertSame(inputs.grant, inputs.savedConsents.single())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine, ownerJob)
        }
    }

    // Cases: MJPEG-CAP-09, MJPEG-CAP-10, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopFailureShadow::class])
    fun terminalCaptureFailureAndMediaFailureSurvivePendingExactRegistrationCompletion() = runTest(StandardTestDispatcher()) {
        val mediaFailure = createProjectionStopFailure()
        val engine = OwnershipEngineSession(holdConsumerUnregister = true, mediaFailure = mediaFailure)
        var cleanupGuardReleased = false
        val inputs = MjpegCaptureSessionInputs(guardRelease = {
            if (engine.mediaStopEntered.isCompleted) cleanupGuardReleased = true
        })
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            engine.publish(MjpegEngineState(MjpegEngineState.Phase.Failed, problem = ScreenCaptureProblem.InternalFailure))
            runCurrent()

            val captureFailure = checkNotNull(session.state.value.captureFailure)
            val cleanupFailure = checkNotNull(session.state.value.cleanupFailure)
            assertEquals("Engine.state", captureFailure.stage)
            assertEquals("Cleanup.media", cleanupFailure.stage)
            assertSame(mediaFailure, cleanupFailure.cause)
            assertTrue(engine.registrationCloseEntered.isCompleted)
            assertFalse(engine.registrationRetired.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
            assertFalse(session.state.value.isStreaming)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertTrue(cleanupGuardReleased)
            assertTrue(inputs.savedConsents.isEmpty())

            session.requestStop(MjpegCaptureSession.StopReason.User)
            engine.releaseRegistration()
            runCurrent()
            assertFalse(awaitCleanup(session))
            assertTrue(engine.registrationRetired.isCompleted)
            assertSame(captureFailure, session.state.value.captureFailure)
            assertSame(cleanupFailure, session.state.value.cleanupFailure)
            assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertTrue(inputs.savedConsents.isEmpty())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    fun foregroundReleaseFailureFollowsGuardConsumerAndEligibleConsentReuse() = runTest(StandardTestDispatcher()) {
        val cause = IllegalStateException("Foreground release failed")
        val engine = OwnershipEngineSession()
        var foregroundReleaseAttempted = false
        var guardReleasedAfterForegroundFailure = false
        val inputs = MjpegCaptureSessionInputs(
            foregroundRelease = {
                foregroundReleaseAttempted = true
                throw cause
            },
            guardRelease = { if (foregroundReleaseAttempted) guardReleasedAfterForegroundFailure = true },
        )
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()

            assertFalse(awaitCleanup(session))
            assertCleanupFailure(session, "Cleanup.foreground", cause)
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(engine.registrationRetired.isCompleted)
            assertEquals(listOf("enterForeground", "leaveForeground"), inputs.foregroundEvents)
            assertFalse(guardReleasedAfterForegroundFailure)
            assertEquals(listOf(inputs.grant), inputs.savedConsents)
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    fun guardReleaseFailureAfterSuccessfulHandoffStillRetiresMediaAndRegistration() = runTest(StandardTestDispatcher()) {
        val cause = IllegalStateException("Creation guard release failed")
        val engine = OwnershipEngineSession()
        var cleanupEntered = false
        val inputs = MjpegCaptureSessionInputs(guardRelease = { if (cleanupEntered) throw cause })
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            assertEquals(listOf(session.id), inputs.projectionCreationFinishes)
            cleanupEntered = true
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()

            assertFalse(awaitCleanup(session))
            assertCleanupFailure(session, "Cleanup.projectionCreation", cause)
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(engine.registrationRetired.isCompleted)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertTrue(inputs.savedConsents.isEmpty())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-09, MJPEG-CAP-10, MJPEG-CAP-11, MJPEG-CAP-12
    @Test
    fun consentSaveFailureIsReportedOnlyAfterExactRegistrationAndMediaSettle() = runTest(StandardTestDispatcher()) {
        val cause = IllegalStateException("Consent save failed")
        val engine = OwnershipEngineSession(holdConsumerUnregister = true)
        var foregroundReleased = false
        var cleanupGuardReleased = false
        val inputs = MjpegCaptureSessionInputs(
            foregroundRelease = { foregroundReleased = true },
            guardRelease = { if (engine.mediaStopped.isCompleted) cleanupGuardReleased = true },
            consentReuse = {
                assertTrue(engine.mediaStopped.isCompleted)
                assertTrue(engine.registrationRetired.isCompleted)
                assertFalse(foregroundReleased)
                assertTrue(cleanupGuardReleased)
                throw cause
            },
        )
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()
            assertTrue(engine.mediaStopped.isCompleted)
            assertEquals(listOf("enterForeground"), inputs.foregroundEvents)
            assertNull(session.state.value.cleanupSucceeded)
            assertNull(session.state.value.cleanupFailure)
            assertTrue(inputs.savedConsents.isEmpty())

            engine.releaseRegistration()
            runCurrent()
            assertFalse(awaitCleanup(session))
            assertCleanupFailure(session, "Cleanup.consentReuse", cause)
            assertSame(inputs.grant, inputs.savedConsents.single())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-10, MJPEG-CAP-11, MJPEG-CAP-12
    @Test
    fun firstStopAndProjectionRevocationDetermineWhetherSuccessfulCleanupOffersConsent() = runTest(StandardTestDispatcher()) {
        // Explicit independent histories; these expected outcomes are not calculated from adapter state.
        val requestedHistories = listOf(
            Triple(MjpegCaptureSession.StopReason.User, MjpegCaptureSession.StopReason.System, true),
            Triple(MjpegCaptureSession.StopReason.Shutdown, MjpegCaptureSession.StopReason.User, true),
            Triple(MjpegCaptureSession.StopReason.System, MjpegCaptureSession.StopReason.User, false),
            Triple(MjpegCaptureSession.StopReason.Error, MjpegCaptureSession.StopReason.User, false),
        )
        for ((first, later, offersConsent) in requestedHistories) {
            val engine = OwnershipEngineSession()
            val inputs = MjpegCaptureSessionInputs()
            val session = inputs.createOwnershipSession(backgroundScope, engine)
            try {
                session.start()
                runCurrent()
                assertStreaming(session)
                session.requestStop(first)
                session.requestStop(later)
                runCurrent()

                assertTrue(awaitCleanup(session), "First Stop: $first")
                assertCleanlyStopped(session)
                assertTrue(engine.registrationRetired.isCompleted)
                if (offersConsent) {
                    assertSame(inputs.grant, inputs.savedConsents.single())
                } else {
                    assertTrue(inputs.savedConsents.isEmpty(), "First Stop: $first")
                }
            } finally {
                releaseGatesAndAwaitCleanup(session, engine)
            }
        }

        val revoked = MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = ScreenCaptureStopReason.ProjectionStopped)
        val engine = OwnershipEngineSession(stoppedState = revoked)
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            engine.publish(revoked)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            assertEquals(MjpegCaptureSession.StopReason.User, session.state.value.stopReason)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertCleanlyStopped(session)
            assertEquals(MjpegCaptureSession.StopReason.User, session.state.value.stopReason)
            assertTrue(engine.registrationRetired.isCompleted)
            assertTrue(inputs.savedConsents.isEmpty())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-10, MJPEG-CAP-11, MJPEG-CAP-12
    @Test
    fun activePublicationWithoutSettledStartupCannotMakeConsentReusable() = runTest(StandardTestDispatcher()) {
        val engine = OwnershipEngineSession(holdStart = true)
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.createOwnershipSession(backgroundScope, engine)
        try {
            session.start()
            runCurrent()
            assertTrue(engine.startEntered.isCompleted)
            assertEquals(MjpegCaptureSession.State.Status.Active, session.state.value.status)
            assertFalse(session.state.value.isStreaming)
            assertNull(session.state.value.captureFailure)
            session.requestStop(MjpegCaptureSession.StopReason.User)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertCleanlyStopped(session)
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(engine.registrationRetired.isCompleted)
            assertTrue(inputs.savedConsents.isEmpty())
        } finally {
            releaseGatesAndAwaitCleanup(session, engine)
        }
    }

    private fun MjpegCaptureSessionInputs.createOwnershipSession(scope: CoroutineScope, engine: OwnershipEngineSession): MjpegCaptureSession = createSession(
        scope = scope,
        engineFactory = { actualContext, actualProjection, _ ->
            assertSame(context, actualContext)
            assertSame(projection, actualProjection)
            engine
        },
    )

    private fun assertStreaming(session: MjpegCaptureSession) {
        val state = session.state.value
        assertEquals(MjpegCaptureSession.State.Status.Active, state.status, "captureFailure=${state.captureFailure}")
        assertTrue(state.isStreaming)
        assertNull(state.captureFailure)
        assertNull(state.cleanupFailure)
        assertNull(state.cleanupSucceeded)
    }

    private fun assertCleanlyStopped(session: MjpegCaptureSession) {
        val state = session.state.value
        assertEquals(MjpegCaptureSession.State.Status.Stopped, state.status)
        assertFalse(state.isStreaming)
        assertNull(state.captureFailure)
        assertNull(state.cleanupFailure)
        assertEquals(true, state.cleanupSucceeded)
    }

    private fun assertCleanupFailure(session: MjpegCaptureSession, stage: String, cause: Throwable) {
        val state = session.state.value
        assertEquals(MjpegCaptureSession.State.Status.Failed, state.status)
        assertFalse(state.isStreaming)
        assertNull(state.captureFailure)
        assertEquals(stage, state.cleanupFailure?.stage)
        assertSame(cause, state.cleanupFailure?.cause)
        assertEquals(false, state.cleanupSucceeded)
    }

    private suspend fun awaitCleanup(session: MjpegCaptureSession): Boolean = withTimeout(5.seconds) { session.awaitCleanup() }

    private suspend fun createProjectionStopFailure(): ScreenCaptureException {
        // Obtain the public Engine exception rather than inventing its inaccessible constructor or exception policy.
        val inputs = MjpegCaptureSessionInputs()
        val platformCause = IllegalStateException("Projection stop failed")
        Shadow.extract<ProjectionStopFailureShadow>(inputs.projection).failure = platformCause
        val engine = DefaultMjpegEngineFactory.create(inputs.context, inputs.projection, ScreenCaptureConfig())
        // This real Engine operation uses its worker thread; keep the timeout on wall-clock time.
        val failure = withContext(Dispatchers.Default) {
            runCatching { withTimeout(5.seconds) { engine.stop() } }.exceptionOrNull()
        }
        val captureException = assertIs<ScreenCaptureException>(failure, "Expected the public projection-stop failure, got $failure")
        assertEquals(ScreenCaptureProblem.InternalFailure, captureException.problem)
        return captureException
    }

    @Implements(MediaProjection::class)
    class ProjectionStopFailureShadow {
        var failure: Throwable? = null

        @Implementation
        protected fun stop() {
            failure?.let { throw it }
        }
    }

    private suspend fun releaseGatesAndAwaitCleanup(session: MjpegCaptureSession, engine: OwnershipEngineSession, ownerJob: Job? = null) {
        // Release test-owned waits before entering noncancellable cleanup; never strand a child on assertion failure.
        engine.releaseStart()
        engine.releaseMedia()
        engine.releaseRegistration()
        withContext(NonCancellable) {
            withTimeout(5.seconds) {
                session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
                session.awaitCleanup()
                ownerJob?.cancelAndJoin()
            }
        }
    }

    /** Only legal operation outcomes and observations needed by these ownership histories. */
    private class OwnershipEngineSession(
        holdStart: Boolean = false,
        holdMediaStop: Boolean = false,
        holdConsumerUnregister: Boolean = false,
        private val mediaFailure: Throwable? = null,
        private val stoppedState: MjpegEngineState = MjpegEngineState(
            MjpegEngineState.Phase.Stopped,
            stopReason = ScreenCaptureStopReason.Requested,
        ),
    ) : MjpegEngineSession {
        private val observedState = MutableStateFlow(MjpegEngineState(MjpegEngineState.Phase.NotStarted))
        private val allowStartToReturn = CompletableDeferred<Unit>().apply { if (!holdStart) complete(Unit) }
        private val allowMediaStopToReturn = CompletableDeferred<Unit>().apply { if (!holdMediaStop) complete(Unit) }
        private val allowUnregisterToReturn = CompletableDeferred<Unit>().apply { if (!holdConsumerUnregister) complete(Unit) }
        val startEntered = CompletableDeferred<Unit>()
        val mediaStopEntered = CompletableDeferred<Unit>()
        val mediaStopped = CompletableDeferred<Unit>()
        val registrationCloseEntered = CompletableDeferred<Unit>()
        val registrationRetired = CompletableDeferred<Unit>()

        // A held completion represents the Engine's legal unresolved exact registration; it asserts no callback deadline.
        private val registration = MjpegFrameRegistration {
            registrationCloseEntered.complete(Unit)
            allowUnregisterToReturn.await()
            registrationRetired.complete(Unit)
        }

        override val currentState: MjpegEngineState
            get() = observedState.value
        override val states: Flow<MjpegEngineState> = observedState
        override val stats: kotlinx.coroutines.flow.StateFlow<ScreenCaptureStats> = MutableStateFlow(initialEngineStatistics())

        override suspend fun start(parameters: ScreenCaptureParameters) {
            observedState.value = MjpegEngineState(MjpegEngineState.Phase.Active)
            startEntered.complete(Unit)
            allowStartToReturn.await()
        }

        override fun updateParameters(parameters: ScreenCaptureParameters): Unit = error("No parameter update is expected in this history")

        override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration = registration

        override suspend fun stop() {
            mediaStopEntered.complete(Unit)
            allowMediaStopToReturn.await()
            mediaFailure?.let { throw it }
            observedState.value = stoppedState
            mediaStopped.complete(Unit)
        }

        fun publish(state: MjpegEngineState) {
            observedState.value = state
        }

        fun releaseStart() {
            allowStartToReturn.complete(Unit)
        }

        fun releaseMedia() {
            allowMediaStopToReturn.complete(Unit)
        }

        fun releaseRegistration() {
            allowUnregisterToReturn.complete(Unit)
        }
    }
}
