package io.screenstream.mjpeg

import android.content.Context
import android.content.ContextWrapper
import android.media.projection.MediaProjection
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.settings.ImageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
internal class MjpegCaptureSessionStartupTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    // Cases: MJPEG-CAP-04
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun realEngineFactoryFailureStopsUnownedProjectionAndSettlesRollback() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val projectionShadow = Shadow.extract<ProjectionStopShadow>(projection)
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = StartupCaptureAccess(request = { grant }, projection = { projection })
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope, context = missingDisplayContext())

        session.start()
        runCurrent()

        assertTrue(session.awaitCleanup())
        val failure = session.state.value.captureFailure!!
        assertEquals("Engine.creation", failure.stage)
        assertEquals(MjpegCaptureSession.StopReason.Error, session.state.value.stopReason)
        assertEquals(1, projectionShadow.stopCalls.get())
        assertEquals(listOf<ScreenCaptureAccess.Grant>(grant), access.projectionGrants)
        assertEquals(listOf("enterForeground", "leaveForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.savedConsents.isEmpty())
        assertNull(session.state.value.cleanupFailure)
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun rawProjectionStopFailureRetainsFactoryFailureAndGuardWithoutForegroundRelease() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val projectionShadow = Shadow.extract<ProjectionStopShadow>(projection)
        val stopFailure = IllegalStateException("Raw projection stop failed")
        projectionShadow.onStop = { throw stopFailure }
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = StartupCaptureAccess(request = { grant }, projection = { projection })
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope, context = missingDisplayContext())

        session.start()
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertEquals("Engine.creation", session.state.value.captureFailure?.stage)
        assertEquals("Cleanup.media", session.state.value.cleanupFailure?.stage)
        assertSame(stopFailure, session.state.value.cleanupFailure?.cause)
        assertEquals(1, projectionShadow.stopCalls.get())
        assertEquals(listOf("enterForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.savedConsents.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
    }

    // Cases: MJPEG-CAP-04
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun realUnstartedEngineHandoffFailureRetiresTransferredProjection() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val projectionShadow = Shadow.extract<ProjectionStopShadow>(projection)
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var projectionFinishCount = 0
        val access = StartupCaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (++projectionFinishCount == 1) throw handoffFailure
            },
        )
        val events = CopyOnWriteArrayList<String>()
        projectionShadow.onStop = { events += "projection.stop" }
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.projectionHandoff", handoffFailure)
        assertNull(session.state.value.cleanupFailure)
        assertEquals(1, projectionShadow.stopCalls.get())
        assertEquals(listOf("enterForeground", "projection.stop", "leaveForeground"), events)
        assertEquals(listOf(session.id, session.id), access.projectionCreationFinishes)
        assertEquals(listOf<ScreenCaptureAccess.Grant>(grant), access.projectionGrants)
        assertTrue(access.savedConsents.isEmpty())
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun ownedProjectionStopFailureRetainsHandoffFailureAndForegroundObligation() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val projectionShadow = Shadow.extract<ProjectionStopShadow>(projection)
        val stopFailure = IllegalStateException("Owned projection stop failed")
        projectionShadow.onStop = { throw stopFailure }
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var projectionFinishCount = 0
        val access = StartupCaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (++projectionFinishCount == 1) throw handoffFailure
            },
        )
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertFailure(session, "Startup.projectionHandoff", handoffFailure)
        assertEquals("Cleanup.media", session.state.value.cleanupFailure?.stage)
        assertTrue(
            generateSequence(session.state.value.cleanupFailure!!.cause) { it.cause }.any { it === stopFailure },
        )
        assertEquals(1, projectionShadow.stopCalls.get())
        assertEquals(listOf("enterForeground"), events)
        assertEquals(listOf(session.id, session.id), access.projectionCreationFinishes)
        assertTrue(access.savedConsents.isEmpty())
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun heldRawProjectionStopDelaysRollbackDespiteCanceledWaiter() = runBlocking {
        assertRollbackWaitsForProjectionStop(engineOwnsProjection = false)
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-10
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun heldUnstartedEngineStopDelaysRollbackDespiteCanceledWaiter() = runBlocking {
        assertRollbackWaitsForProjectionStop(engineOwnsProjection = true)
    }

    // Cases: MJPEG-CAP-01
    @Test
    fun declinedConsentStopsSingleUseAttemptWithoutProjection() = runTest {
        val access = StartupCaptureAccess()
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        assertEquals(MjpegCaptureSession.State.Status.WaitingForPermission, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertNull(session.state.value.cleanupSucceeded)

        val waiter = backgroundScope.async { session.awaitCleanup() }
        runCurrent()

        assertFalse(waiter.isCompleted)
        assertTrue(access.consentRequests.isEmpty())

        session.start()
        runCurrent()

        assertTrue(waiter.await())
        assertEquals(listOf(session.id), access.consentRequests)
        assertTrue(access.projectionGrants.isEmpty())
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertNull(session.state.value.captureFailure)
        assertNull(session.state.value.cleanupFailure)
        assertTrue(access.savedConsents.isEmpty())
        assertFailsWith<IllegalStateException> { session.start() }
    }

    // Cases: MJPEG-CAP-02
    @Test
    fun stopBeforeCoroutineEntryAcquiresNothingAndPublishesSuccessfulCleanup() = runTest {
        val access = StartupCaptureAccess()
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        session.start()
        session.requestStop(MjpegCaptureSession.StopReason.User)
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertTrue(access.consentRequests.isEmpty())
        assertTrue(access.projectionGrants.isEmpty())
        assertTrue(access.projectionCreationFinishes.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
    }

    // Cases: MJPEG-CAP-02
    @Test
    fun cancelingCleanupWaiterDoesNotStopConsentAndStopRollsBackEnteredAttempt() = runTest {
        val consent = CompletableDeferred<ScreenCaptureAccess.Grant?>()
        val access = StartupCaptureAccess(request = { consent.await() })
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertEquals(listOf(session.id), access.consentRequests)

        val abandonedWaiter = backgroundScope.async { session.awaitCleanup() }
        runCurrent()
        abandonedWaiter.cancelAndJoin()

        assertNull(session.state.value.cleanupSucceeded)
        assertEquals(MjpegCaptureSession.State.Status.WaitingForPermission, session.state.value.status)

        session.requestStop(MjpegCaptureSession.StopReason.User)
        session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
        session.updateImageSettings(ImageSettings(jpegQuality = 10))
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertTrue(access.projectionGrants.isEmpty())
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.savedConsents.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-09
    @Test
    fun consentFailureRetainsItsCauseWhileRollbackStillCompletes() = runTest {
        val cause = IllegalStateException("Consent boundary failed")
        val access = StartupCaptureAccess(request = { throw cause })
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.consent", cause)
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.projectionGrants.isEmpty())
        assertTrue(access.savedConsents.isEmpty())
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    fun foregroundFailurePreventsProjectionAndRetainsSeparateFirstCleanupFailure() = runTest {
        val acquisitionFailure = IllegalArgumentException("Foreground rejected")
        val foregroundCleanupFailure = IllegalStateException("Foreground rollback failed")
        val guardCleanupFailure = IllegalStateException("Guard rollback failed")
        val events = mutableListOf<String>()
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = StartupCaptureAccess(
            request = { grant },
            finish = { throw guardCleanupFailure },
        )
        val session = createSession(
            access, events, backgroundScope,
            enter = {
                events += "enterForeground"
                throw acquisitionFailure
            },
            leave = {
                events += "leaveForeground"
                throw foregroundCleanupFailure
            },
        )

        session.start()
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertFailure(session, "Startup.foreground", acquisitionFailure)
        assertEquals("Cleanup.projectionCreation", session.state.value.cleanupFailure?.stage)
        assertSame(guardCleanupFailure, session.state.value.cleanupFailure?.cause)
        assertEquals(listOf("enterForeground"), events)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.projectionGrants.isEmpty())
        assertTrue(access.savedConsents.isEmpty())
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-04
    @Test
    fun projectionCreationFollowsForegroundAndItsFailureStillReleasesForegroundAndGuard() = runTest {
        val cause = IllegalStateException("Projection creation failed")
        val events = mutableListOf<String>()
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = StartupCaptureAccess(
            request = { grant },
            projection = {
                events += "createProjection"
                throw cause
            },
        )
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.projection", cause)
        assertEquals(listOf("enterForeground", "createProjection", "leaveForeground"), events)
        assertEquals(listOf<ScreenCaptureAccess.Grant>(grant), access.projectionGrants)
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
        assertTrue(access.savedConsents.isEmpty())
    }

    // Cases: MJPEG-CAP-11
    @Test
    fun projectionGuardCleanupFailureRemainsObservableAfterNullConsent() = runTest {
        val cause = IllegalStateException("Guard release failed")
        val access = StartupCaptureAccess(finish = { throw cause })
        val events = mutableListOf<String>()
        val session = createSession(access, events, backgroundScope)

        session.start()
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertNull(session.state.value.captureFailure)
        assertEquals("Cleanup.projectionCreation", session.state.value.cleanupFailure?.stage)
        assertSame(cause, session.state.value.cleanupFailure?.cause)
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertTrue(events.isEmpty())
        assertEquals(listOf(session.id), access.projectionCreationFinishes)
    }

    private suspend fun CoroutineScope.assertRollbackWaitsForProjectionStop(engineOwnsProjection: Boolean) {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val projectionShadow = Shadow.extract<ProjectionStopShadow>(projection)
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CountDownLatch(1)
        projectionShadow.onStop = {
            stopEntered.complete(Unit)
            check(releaseStop.await(20, TimeUnit.SECONDS)) { "Projection stop was not released" }
        }
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var projectionFinishCount = 0
        val access = StartupCaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (engineOwnsProjection && ++projectionFinishCount == 1) throw handoffFailure
            },
        )
        val events = CopyOnWriteArrayList<String>()
        // Raw stop is synchronous on this adapter lane; Engine-owned stop uses its real worker.
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val ownerJob = SupervisorJob()
        val session = createSession(
            access, events, CoroutineScope(ownerJob + dispatcher),
            context = if (engineOwnsProjection) RuntimeEnvironment.getApplication() else missingDisplayContext(),
        )
        try {
            session.start()
            withTimeout(5.seconds) { stopEntered.await() }
            if (engineOwnsProjection) {
                // The adapter has settled or suspended its current task; only its Engine worker is held.
                withTimeout(5.seconds) { withContext(dispatcher) { Unit } }
            }
            val expectedGuardEntries = if (engineOwnsProjection) listOf(session.id) else emptyList()
            val expectedFailureStage = if (engineOwnsProjection) "Startup.projectionHandoff" else "Engine.creation"
            assertEquals(expectedGuardEntries, access.projectionCreationFinishes)
            assertEquals(listOf("enterForeground"), events)
            assertEquals(expectedFailureStage, session.state.value.captureFailure?.stage)
            val acquisitionFailure = session.state.value.captureFailure!!.cause
            if (engineOwnsProjection) {
                assertSame(handoffFailure, acquisitionFailure)
            }
            assertNull(session.state.value.cleanupSucceeded)
            val cancelledWaiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(cancelledWaiter.isCompleted)
            cancelledWaiter.cancelAndJoin()
            session.requestStop(MjpegCaptureSession.StopReason.User)
            val remainingWaiter = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(remainingWaiter.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            assertEquals(expectedGuardEntries, access.projectionCreationFinishes)
            assertEquals(listOf("enterForeground"), events)

            releaseStop.countDown()
            assertTrue(withTimeout(5.seconds) { remainingWaiter.await() })
            assertEquals(1, projectionShadow.stopCalls.get())
            assertEquals(listOf("enterForeground", "leaveForeground"), events)
            assertEquals(
                if (engineOwnsProjection) listOf(session.id, session.id) else listOf(session.id),
                access.projectionCreationFinishes,
            )
            assertNull(session.state.value.cleanupFailure)
            assertFailure(session, expectedFailureStage, acquisitionFailure)
            assertTrue(access.savedConsents.isEmpty())
        } finally {
            releaseStop.countDown()
            try {
                session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
                withContext(NonCancellable) {
                    withTimeout(5.seconds) {
                        session.awaitCleanup()
                        ownerJob.cancelAndJoin()
                    }
                }
            } finally {
                ownerJob.cancel()
                dispatcher.close()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Capture adapter executor did not terminate")
            }
        }
    }

    @Implements(MediaProjection::class)
    class ProjectionStopShadow {
        val stopCalls = AtomicInteger()
        var onStop: () -> Unit = {}

        @Implementation
        protected fun stop() {
            stopCalls.incrementAndGet()
            onStop()
        }
    }

    private fun createSession(
        access: StartupCaptureAccess,
        events: MutableList<String>,
        scope: CoroutineScope,
        enter: () -> Unit = { events += "enterForeground" },
        leave: () -> Unit = { events += "leaveForeground" },
        context: Context = RuntimeEnvironment.getApplication(),
    ): MjpegCaptureSession = MjpegCaptureSession(
        id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))),
        initialImageSettings = ImageSettings(),
        initialCaptureGeneration = Any(),
        http = ControlledHttpDelivery(),
        context = context,
        controllerScope = scope,
        captureAccess = access,
        enterForeground = { enter(); true },
        leaveForeground = leave,
    )

    private fun missingDisplayContext(): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? =
            if (name == DISPLAY_SERVICE) null else super.getSystemService(name)
    }

    private fun assertFailure(session: MjpegCaptureSession, stage: String, cause: Throwable) {
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertNull(session.state.value.suspensionProblem)
        assertEquals(stage, session.state.value.captureFailure?.stage)
        assertSame(cause, session.state.value.captureFailure?.cause)
    }

    private class StartupCaptureAccess(
        private val request: suspend () -> ScreenCaptureAccess.Grant? = { null },
        private val projection: (ScreenCaptureAccess.Grant) -> MediaProjection = { error("Projection must not be admitted") },
        private val finish: () -> Unit = {},
    ) : ScreenCaptureAccess {
        val consentRequests = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
        val projectionGrants = CopyOnWriteArrayList<ScreenCaptureAccess.Grant>()
        val projectionCreationFinishes = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
        val savedConsents = CopyOnWriteArrayList<ScreenCaptureAccess.Grant>()

        override suspend fun requestConsent(
            attempt: StreamingModule.CaptureAttemptId,
            attemptJob: kotlinx.coroutines.Job,
            prepareForeground: () -> Boolean,
        ): ScreenCaptureAccess.ConsentResult {
            consentRequests += attempt
            val grant = request() ?: return ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.Declined)
            return if (prepareForeground()) ScreenCaptureAccess.ConsentResult.Granted(grant)
            else ScreenCaptureAccess.ConsentResult.NotGranted(ScreenCaptureAccess.NoGrantReason.ForegroundRejected)
        }

        override fun createProjection(grant: ScreenCaptureAccess.Grant, retryAfterRecentForegroundStart: Boolean): MediaProjection {
            check(!retryAfterRecentForegroundStart)
            projectionGrants += grant
            return projection(grant)
        }

        override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
            projectionCreationFinishes += attempt
            finish()
        }

        override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) {
            savedConsents += grant
        }
    }
}
