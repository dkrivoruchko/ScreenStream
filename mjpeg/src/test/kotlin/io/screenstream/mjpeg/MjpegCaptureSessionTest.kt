package io.screenstream.mjpeg

import android.content.Context
import android.content.ContextWrapper
import android.media.projection.MediaProjection
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
internal class MjpegCaptureSessionTest {
    // Cases: MJPEG-CAP-04
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun realEngineFactoryFailureStopsUnownedProjectionAndSettlesRollback() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val platform = Shadow.extract<ProjectionStopShadow>(projection)
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = CaptureAccess(request = { grant }, projection = { projection })
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope, context = missingDisplayContext())

        session.runCapture(ImageSettings())
        runCurrent()

        assertTrue(session.awaitCleanup())
        val failure = session.state.value.captureFailure!!
        assertEquals("Engine.creation", failure.stage)
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals("DisplayManager must be available", failure.cause.message)
        assertEquals(1, platform.stopCalls.get())
        assertEquals(listOf(grant), access.projections)
        assertEquals(listOf("enterForeground", "leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.saved.isEmpty())
        assertNull(session.state.value.cleanupFailure)
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun rawProjectionStopFailureRetainsFactoryFailureAndStillReleasesForegroundAndGuard() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val platform = Shadow.extract<ProjectionStopShadow>(projection)
        val stopFailure = IllegalStateException("Raw projection stop failed")
        platform.onStop = { throw stopFailure }
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = CaptureAccess(request = { grant }, projection = { projection })
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope, context = missingDisplayContext())

        session.runCapture(ImageSettings())
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertEquals("Engine.creation", session.state.value.captureFailure?.stage)
        assertEquals("DisplayManager must be available", session.state.value.captureFailure?.cause?.message)
        assertEquals("Cleanup.media", session.state.value.cleanupFailure?.stage)
        assertSame(stopFailure, session.state.value.cleanupFailure?.cause)
        assertEquals(1, platform.stopCalls.get())
        assertEquals(listOf("enterForeground", "leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.saved.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
    }

    // Cases: MJPEG-CAP-04
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun realUnstartedEngineHandoffFailureRetiresTransferredProjection() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val platform = Shadow.extract<ProjectionStopShadow>(projection)
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var finishCalls = 0
        val access = CaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (++finishCalls == 1) throw handoffFailure
            },
        )
        val events = CopyOnWriteArrayList<String>()
        platform.onStop = { events += "projection.stop" }
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.projectionHandoff", handoffFailure)
        assertNull(session.state.value.cleanupFailure)
        assertEquals(1, platform.stopCalls.get())
        assertEquals(listOf("enterForeground", "projection.stop", "leaveForeground"), events)
        assertEquals(listOf(session.id, session.id), access.finished)
        assertEquals(listOf(grant), access.projections)
        assertTrue(access.saved.isEmpty())
    }

    // Cases: MJPEG-CAP-04, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    @Config(shadows = [ProjectionStopShadow::class])
    fun ownedProjectionStopFailureRetainsHandoffFailureAndForegroundObligation() = runTest {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val platform = Shadow.extract<ProjectionStopShadow>(projection)
        val stopFailure = IllegalStateException("Owned projection stop failed")
        platform.onStop = { throw stopFailure }
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var finishCalls = 0
        val access = CaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (++finishCalls == 1) throw handoffFailure
            },
        )
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertFailure(session, "Startup.projectionHandoff", handoffFailure)
        assertEquals("Cleanup.media", session.state.value.cleanupFailure?.stage)
        assertTrue(
            generateSequence(session.state.value.cleanupFailure!!.cause) { it.cause }.any { it === stopFailure },
        )
        assertEquals(1, platform.stopCalls.get())
        assertEquals(listOf("enterForeground"), events)
        assertEquals(listOf(session.id, session.id), access.finished)
        assertTrue(access.saved.isEmpty())
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
    fun inertSingleUseAttemptWithDeclinedConsentSettlesWithoutProjection() = runTest {
        val access = CaptureAccess()
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        assertEquals(MjpegCaptureSession.State.Status.WaitingForPermission, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertNull(session.state.value.cleanupSucceeded)

        val waiter = backgroundScope.async { session.awaitCleanup() }
        runCurrent()

        assertFalse(waiter.isCompleted)
        assertTrue(access.requested.isEmpty())

        session.runCapture(ImageSettings())
        runCurrent()

        assertTrue(waiter.await())
        assertEquals(listOf(session.id), access.requested)
        assertTrue(access.projections.isEmpty())
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertNull(session.state.value.captureFailure)
        assertNull(session.state.value.cleanupFailure)
        assertTrue(access.saved.isEmpty())
        assertThrows(IllegalStateException::class.java) { session.runCapture(ImageSettings()) }
    }

    // Cases: MJPEG-CAP-02
    @Test
    fun stopBeforeCoroutineEntryAcquiresNothingAndPublishesSuccessfulCleanup() = runTest {
        val access = CaptureAccess()
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        session.stop(MjpegCaptureSession.StopReason.User)
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertTrue(access.requested.isEmpty())
        assertTrue(access.projections.isEmpty())
        assertTrue(access.finished.isEmpty())
        assertTrue(events.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
    }

    // Cases: MJPEG-CAP-02
    @Test
    fun cancelingCleanupWaiterDoesNotStopConsentAndStopRollsBackEnteredAttempt() = runTest {
        val consent = CompletableDeferred<ScreenCaptureAccess.Grant?>()
        val access = CaptureAccess(request = { consent.await() })
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertEquals(listOf(session.id), access.requested)

        val abandonedWaiter = backgroundScope.async { session.awaitCleanup() }
        runCurrent()
        abandonedWaiter.cancelAndJoin()

        assertNull(session.state.value.cleanupSucceeded)
        assertEquals(MjpegCaptureSession.State.Status.WaitingForPermission, session.state.value.status)

        session.stop(MjpegCaptureSession.StopReason.User)
        session.stop(MjpegCaptureSession.StopReason.Shutdown)
        session.updateImageSettings(ImageSettings(jpegQuality = 10))
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertTrue(access.projections.isEmpty())
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.saved.isEmpty())
        assertEquals(MjpegCaptureSession.State.Status.Stopped, session.state.value.status)
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-09
    @Test
    fun consentFailureRetainsItsCauseWhileRollbackStillCompletes() = runTest {
        val cause = IllegalStateException("Consent boundary failed")
        val access = CaptureAccess(request = { throw cause })
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.consent", cause)
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.projections.isEmpty())
        assertTrue(access.saved.isEmpty())
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-09, MJPEG-CAP-11
    @Test
    fun foregroundFailurePreventsProjectionAndRetainsSeparateFirstCleanupFailure() = runTest {
        val acquisitionFailure = IllegalArgumentException("Foreground rejected")
        val foregroundCleanupFailure = IllegalStateException("Foreground rollback failed")
        val guardCleanupFailure = IllegalStateException("Guard rollback failed")
        val events = mutableListOf<String>()
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = CaptureAccess(
            request = { grant },
            finish = { throw guardCleanupFailure },
        )
        val session = session(
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

        session.runCapture(ImageSettings())
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertFailure(session, "Startup.foreground", acquisitionFailure)
        assertEquals("Cleanup.foreground", session.state.value.cleanupFailure?.stage)
        assertSame(foregroundCleanupFailure, session.state.value.cleanupFailure?.cause)
        assertEquals(listOf("enterForeground", "leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.projections.isEmpty())
        assertTrue(access.saved.isEmpty())
    }

    // Cases: MJPEG-CAP-03, MJPEG-CAP-04
    @Test
    fun projectionCreationFollowsForegroundAndItsFailureStillReleasesForegroundAndGuard() = runTest {
        val cause = IllegalStateException("Projection creation failed")
        val events = mutableListOf<String>()
        val grant = object : ScreenCaptureAccess.Grant {}
        val access = CaptureAccess(
            request = { grant },
            projection = {
                events += "createProjection"
                throw cause
            },
        )
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertTrue(session.awaitCleanup())
        assertFailure(session, "Startup.projection", cause)
        assertEquals(listOf("enterForeground", "createProjection", "leaveForeground"), events)
        assertEquals(listOf(grant), access.projections)
        assertEquals(listOf(session.id), access.finished)
        assertTrue(access.saved.isEmpty())
    }

    // Cases: MJPEG-CAP-11
    @Test
    fun projectionGuardCleanupFailureRemainsObservableAfterNullConsent() = runTest {
        val cause = IllegalStateException("Guard release failed")
        val access = CaptureAccess(finish = { throw cause })
        val events = mutableListOf<String>()
        val session = session(access, events, backgroundScope)

        session.runCapture(ImageSettings())
        runCurrent()

        assertFalse(session.awaitCleanup())
        assertNull(session.state.value.captureFailure)
        assertEquals("Cleanup.projectionCreation", session.state.value.cleanupFailure?.stage)
        assertSame(cause, session.state.value.cleanupFailure?.cause)
        assertEquals(MjpegCaptureSession.State.Status.Failed, session.state.value.status)
        assertFalse(session.state.value.isStreaming)
        assertEquals(listOf("leaveForeground"), events)
        assertEquals(listOf(session.id), access.finished)
    }

    private suspend fun CoroutineScope.assertRollbackWaitsForProjectionStop(engineOwnsProjection: Boolean) {
        val projection = Shadow.newInstanceOf(MediaProjection::class.java)
        val platform = Shadow.extract<ProjectionStopShadow>(projection)
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CountDownLatch(1)
        platform.onStop = {
            stopEntered.complete(Unit)
            check(releaseStop.await(20, TimeUnit.SECONDS)) { "Projection stop was not released" }
        }
        val handoffFailure = IllegalStateException("Projection guard handoff failed")
        val grant = object : ScreenCaptureAccess.Grant {}
        var finishCalls = 0
        val access = CaptureAccess(
            request = { grant },
            projection = { projection },
            finish = {
                if (engineOwnsProjection && ++finishCalls == 1) throw handoffFailure
            },
        )
        val events = CopyOnWriteArrayList<String>()
        // Raw stop is synchronous on this adapter lane; Engine-owned stop uses its real worker.
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val owner = SupervisorJob()
        val session = session(
            access, events, CoroutineScope(owner + dispatcher),
            context = if (engineOwnsProjection) RuntimeEnvironment.getApplication() else missingDisplayContext(),
        )
        try {
            session.runCapture(ImageSettings())
            withTimeout(5.seconds) { stopEntered.await() }
            if (engineOwnsProjection) {
                // The adapter has settled or suspended its current task; only its Engine worker is held.
                withTimeout(5.seconds) { withContext(dispatcher) { Unit } }
            }
            val expectedGuardEntries = if (engineOwnsProjection) listOf(session.id) else emptyList()
            val expectedFailureStage = if (engineOwnsProjection) "Startup.projectionHandoff" else "Engine.creation"
            assertEquals(expectedGuardEntries, access.finished)
            assertEquals(listOf("enterForeground"), events)
            assertEquals(expectedFailureStage, session.state.value.captureFailure?.stage)
            val acquisitionFailure = session.state.value.captureFailure!!.cause
            if (engineOwnsProjection) {
                assertSame(handoffFailure, acquisitionFailure)
            } else {
                assertEquals("DisplayManager must be available", acquisitionFailure.message)
            }
            assertNull(session.state.value.cleanupSucceeded)
            val abandoned = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(abandoned.isCompleted)
            abandoned.cancelAndJoin()
            session.stop(MjpegCaptureSession.StopReason.User)
            val remaining = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(remaining.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            assertEquals(expectedGuardEntries, access.finished)
            assertEquals(listOf("enterForeground"), events)

            releaseStop.countDown()
            assertTrue(withTimeout(5.seconds) { remaining.await() })
            assertEquals(1, platform.stopCalls.get())
            assertEquals(listOf("enterForeground", "leaveForeground"), events)
            assertEquals(
                if (engineOwnsProjection) listOf(session.id, session.id) else listOf(session.id),
                access.finished,
            )
            assertNull(session.state.value.cleanupFailure)
            assertFailure(session, expectedFailureStage, acquisitionFailure)
            assertTrue(access.saved.isEmpty())
        } finally {
            releaseStop.countDown()
            try {
                session.stop(MjpegCaptureSession.StopReason.Shutdown)
                withContext(NonCancellable) {
                    withTimeout(5.seconds) {
                        session.awaitCleanup()
                        owner.cancelAndJoin()
                    }
                }
            } finally {
                owner.cancel()
                dispatcher.close()
                assertTrue("Capture adapter executor did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
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

    private fun session(
        access: CaptureAccess,
        events: MutableList<String>,
        scope: CoroutineScope,
        enter: suspend () -> Unit = { events += "enterForeground" },
        leave: () -> Unit = { events += "leaveForeground" },
        context: Context = RuntimeEnvironment.getApplication(),
    ): MjpegCaptureSession = MjpegCaptureSession(
        id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))),
        context = context,
        controllerScope = scope,
        captureAccess = access,
        enterForeground = enter,
        leaveForeground = leave,
        onJpeg = { _, _ -> error("No JPEG is expected before capture starts") },
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

    private class CaptureAccess(
        private val request: suspend () -> ScreenCaptureAccess.Grant? = { null },
        private val projection: (ScreenCaptureAccess.Grant) -> MediaProjection = { error("Projection must not be admitted") },
        private val finish: () -> Unit = {},
    ) : ScreenCaptureAccess {
        val requested = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
        val projections = CopyOnWriteArrayList<ScreenCaptureAccess.Grant>()
        val finished = CopyOnWriteArrayList<StreamingModule.CaptureAttemptId>()
        val saved = CopyOnWriteArrayList<ScreenCaptureAccess.Grant>()

        override suspend fun requestConsent(attempt: StreamingModule.CaptureAttemptId): ScreenCaptureAccess.Grant? {
            requested += attempt
            return request()
        }

        override fun createProjection(grant: ScreenCaptureAccess.Grant): MediaProjection {
            projections += grant
            return projection(grant)
        }

        override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
            finished += attempt
            finish()
        }

        override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) {
            saved += grant
        }
    }
}
