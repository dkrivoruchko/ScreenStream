package io.screenstream.mjpeg

import com.elvishew.xlog.XLog
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.core.capture.ScreenCaptureAccess
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.MjpegEngineState.Phase
import io.screenstream.mjpeg.http.HttpDelivery
import io.screenstream.mjpeg.http.media.JpegStore
import io.screenstream.mjpeg.settings.ImageSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
internal class MjpegCaptureSessionContractsTest {
    @Before
    fun initializeLogger() {
        XLog.init(Int.MAX_VALUE)
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-08
    @Test
    fun generationPairsPreserveReconfigurationRevokeSuspensionAndFenceRecoveredFrames() = runTest {
        val engine = ControlledMjpegEngine()
        val delivery = StoreDelivery()
        val inputs = MjpegCaptureSessionInputs()
        val session = inputs.session(backgroundScope, { _, _, _ -> engine }, http = delivery)
        val initial = session.state.value.captureGeneration!!
        delivery.registerCapture(session.id, initial)
        try {
            session.start()
            runCurrent()
            engine.deliver(jpeg(1))
            assertArrayEquals(jpeg(1), delivery.selected())
            assertSame(initial, delivery.store.snapshot().captureGeneration)

            engine.publish(MjpegEngineState(Phase.Reconfiguring))
            runCurrent()
            assertEquals(Status.Reconfiguring, session.state.value.status)
            assertSame(initial, delivery.store.snapshot().captureGeneration)
            assertArrayEquals(jpeg(1), delivery.selected())

            engine.publish(MjpegEngineState(Phase.Suspended, ScreenCaptureProblem.CaptureUnavailable))
            runCurrent()
            assertNull(delivery.store.snapshot().captureGeneration)
            assertEquals(JpegStore.ImageKind.Logo, delivery.store.snapshot().imageKind)
            engine.deliver(jpeg(2))
            assertArrayEquals(byteArrayOf(99), delivery.selected())

            engine.publish(MjpegEngineState(Phase.Reconfiguring))
            runCurrent()
            assertNull(delivery.store.snapshot().captureGeneration)
            engine.deliver(jpeg(3))
            assertArrayEquals(byteArrayOf(99), delivery.selected())

            engine.publish(MjpegEngineState(Phase.Active))
            runCurrent()
            val recovered = session.state.value.captureGeneration!!
            assertNotSame(initial, recovered)
            assertEquals(JpegStore.ImageKind.Logo, delivery.store.snapshot().imageKind)
            delivery.offerJpeg(initial, 6) { fail("Old generation entered copier") }
            engine.deliver(jpeg(4))
            assertArrayEquals(jpeg(4), delivery.selected())
            assertSame(recovered, delivery.store.snapshot().captureGeneration)

            session.requestStop(MjpegCaptureSession.StopReason.User)
            assertNull(delivery.store.snapshot().captureGeneration)
            assertEquals(Status.Stopping, delivery.store.snapshot().captureStatus)
            delivery.offerJpeg(recovered, 6) { fail("Stopped generation entered copier") }
            runCurrent()
            assertTrue(session.awaitCleanup())
        } finally {
            engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup(); delivery.store.dispose()
        }
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-08
    @Test
    fun freshFrameRecoveryCannotBeReversedByAnOlderFlowPayload() = runTest {
        val engine = ControlledMjpegEngine()
        val delivery = StoreDelivery()
        val session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine }, http = delivery)
        delivery.registerCapture(session.id, session.state.value.captureGeneration!!)
        try {
            session.start(); runCurrent()
            val suspended = MjpegEngineState(Phase.Suspended, ScreenCaptureProblem.ResourceExhausted)
            engine.publish(suspended); runCurrent()
            assertNull(session.state.value.captureGeneration)
            engine.publish(MjpegEngineState(Phase.Active), announce = false)
            engine.deliver(jpeg(8))
            val recovered = session.state.value.captureGeneration!!
            assertArrayEquals(jpeg(8), delivery.selected())
            engine.announce(suspended); runCurrent()
            assertEquals(Status.Active, session.state.value.status)
            assertSame(recovered, session.state.value.captureGeneration)
            assertSame(recovered, delivery.store.snapshot().captureGeneration)
        } finally {
            engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup(); delivery.store.dispose()
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-10
    @Test
    fun stopDuringBorrowedCopyRevokesPublicationWithoutWaitingForTheCopier() = runTest {
        val engine = ControlledMjpegEngine()
        val delivery = StoreDelivery()
        lateinit var session: MjpegCaptureSession
        delivery.beforeCopy = {
            session.requestStop(MjpegCaptureSession.StopReason.User)
            assertEquals(Status.Stopping, session.state.value.status)
            assertNull(delivery.store.snapshot().captureGeneration)
        }
        session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine }, http = delivery)
        delivery.registerCapture(session.id, session.state.value.captureGeneration!!)
        try {
            session.start(); runCurrent()
            engine.deliver(jpeg(9))
            assertEquals(JpegStore.ImageKind.Logo, delivery.store.snapshot().imageKind)
            assertArrayEquals(byteArrayOf(99), delivery.selected())
            runCurrent()
            assertTrue(session.awaitCleanup())
            assertNull(session.state.value.captureFailure)
        } finally {
            engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup(); delivery.store.dispose()
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-10
    @Test
    fun stopReturnsWhileAnotherThreadHoldsTheCopierAndCleanupWaitsForThatExactCallback() = runBlocking {
        val engine = ControlledMjpegEngine(holdConsumer = true)
        val mediaStopped = CompletableDeferred<Unit>()
        val sessionEngine = object : MjpegEngineSession by engine {
            override suspend fun stop() {
                engine.stop(); mediaStopped.complete(Unit)
            }
        }
        val delivery = StoreDelivery()
        val copyEntered = CompletableDeferred<Unit>()
        val releaseCopy = java.util.concurrent.CountDownLatch(1)
        val owner = SupervisorJob()
        val session = MjpegCaptureSessionInputs().session(CoroutineScope(owner + Dispatchers.Default), { _, _, _ -> sessionEngine }, http = delivery)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        var callback: java.util.concurrent.Future<*>? = null
        var stop: java.util.concurrent.Future<*>? = null
        delivery.beforeCopy = {
            copyEntered.complete(Unit)
            check(releaseCopy.await(20, java.util.concurrent.TimeUnit.SECONDS))
        }
        delivery.registerCapture(session.id, session.state.value.captureGeneration!!)
        try {
            session.start()
            withTimeout(5.seconds) { session.state.first { it.isStreaming && it.status == Status.Active } }
            val activeCallback = executor.submit {
                try {
                    engine.deliver(jpeg(42))
                } finally {
                    engine.finishConsumer.complete(Unit)
                }
            }
            callback = activeCallback
            withTimeout(5.seconds) { copyEntered.await() }
            val stopReturned = CompletableDeferred<Unit>()
            val activeStop = executor.submit {
                session.requestStop(MjpegCaptureSession.StopReason.User)
                stopReturned.complete(Unit)
            }
            stop = activeStop
            withTimeout(5.seconds) { stopReturned.await(); mediaStopped.await(); engine.consumerCloseEntered.await() }
            activeStop.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(activeCallback.isDone)
            assertEquals(Status.Stopping, session.state.value.status)
            assertNull(delivery.store.snapshot().captureGeneration)
            assertEquals(JpegStore.ImageKind.Logo, delivery.store.snapshot().imageKind)
            assertNull(session.state.value.cleanupSucceeded)
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(cleanup.isCompleted)
            releaseCopy.countDown()
            activeCallback.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(withTimeout(5.seconds) { cleanup.await() })
            assertEquals(JpegStore.ImageKind.Logo, delivery.store.snapshot().imageKind)
            assertArrayEquals(byteArrayOf(99), delivery.selected())
            assertNull(session.state.value.captureFailure)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            releaseCopy.countDown()
            try {
                callback?.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                try {
                    stop?.get(5, java.util.concurrent.TimeUnit.SECONDS)
                } finally {
                    engine.release()
                    session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
                    try {
                        withTimeout(5.seconds) { session.awaitCleanup(); owner.cancelAndJoin() }
                    } finally {
                        executor.shutdownNow(); delivery.store.dispose()
                    }
                }
            }
        }
    }

    // Cases: MJPEG-CAP-14, MJPEG-CAP-09
    @Test
    fun defensiveStateObservationFailureEndsCaptureWhileStatisticsObservationFailureDoesNot() = runTest {
        for (stateFailure in listOf(true, false)) {
            val base = ControlledMjpegEngine()
            val cause = IllegalStateException("Defensive observation failure")
            val engine = object : MjpegEngineSession by base {
                override val states: Flow<MjpegEngineState> = if (stateFailure) flow { throw cause } else base.states
                override val stats: StateFlow<ScreenCaptureStats> = if (!stateFailure) base.stats.onSubscription { throw cause } else base.stats
            }
            val session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine })
            try {
                session.start(); runCurrent()
                if (stateFailure) {
                    assertTrue(session.awaitCleanup())
                    assertEquals("Engine.stateObservation", session.state.value.captureFailure?.stage)
                    assertSame(cause, session.state.value.captureFailure?.cause)
                    assertEquals(MjpegCaptureSession.StopReason.Error, session.state.value.stopReason)
                } else {
                    assertTrue(session.state.value.isStreaming)
                    assertNull(session.state.value.captureFailure)
                    assertNull(session.engineStatistics)
                    session.requestStop(MjpegCaptureSession.StopReason.User); runCurrent()
                    assertTrue(session.awaitCleanup())
                    assertNull(session.state.value.captureFailure)
                    assertEquals(base.stats.value, session.engineStatistics!!.engineStats)
                    assertNull(session.engineStatistics!!.currentFps)
                }
            } finally {
                base.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup()
            }
        }
    }

    // Cases: MJPEG-CAP-14
    @Test
    fun defensiveUndocumentedSuspensionProblemIsRejectedByTheSessionProjection() {
        for (problem in listOf(null, ScreenCaptureProblem.InternalFailure, ScreenCaptureProblem.UnsupportedColorSpace)) {
            val state = MjpegCaptureSession.State(Any(), engineState = MjpegEngineState(Phase.Suspended, problem))
            assertThrows(IllegalStateException::class.java) { state.suspensionProblem }
        }
    }

    // Cases: MJPEG-CAP-02, MJPEG-CAP-03
    @Test
    fun cancellationAfterForegroundPreparationStillRollsBackBeforeGrantDelivery() = runTest {
        val prepared = CompletableDeferred<Unit>()
        val neverDeliver = CompletableDeferred<ScreenCaptureAccess.ConsentResult>()
        val finished = mutableListOf<StreamingModule.CaptureAttemptId>()
        val events = mutableListOf<String>()
        val access = object : ScreenCaptureAccess {
            override suspend fun requestConsent(
                attempt: StreamingModule.CaptureAttemptId,
                attemptJob: Job,
                prepareForeground: () -> Boolean
            ): ScreenCaptureAccess.ConsentResult {
                assertSame(currentCoroutineContext()[Job], attemptJob)
                assertTrue(prepareForeground())
                prepared.complete(Unit)
                return neverDeliver.await()
            }

            override fun createProjection(
                grant: ScreenCaptureAccess.Grant,
                retryAfterRecentForegroundStart: Boolean
            ): android.media.projection.MediaProjection = error("No grant delivered")

            override fun finishProjectionCreation(attempt: StreamingModule.CaptureAttemptId) {
                finished += attempt
            }

            override fun saveConsentForReuse(grant: ScreenCaptureAccess.Grant) = error("No grant delivered")
        }
        val session = MjpegCaptureSession(
            StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG"))), ImageSettings(), Any(),
            org.robolectric.RuntimeEnvironment.getApplication(), backgroundScope, access, TestHttpDelivery(),
            enterForeground = { events += "promoted"; true }, leaveForeground = { events += "rolled back" },
        )
        session.start(); runCurrent()
        assertTrue(prepared.isCompleted)
        session.requestStop(MjpegCaptureSession.StopReason.User); runCurrent()
        assertTrue(session.awaitCleanup())
        assertEquals(listOf("promoted", "rolled back"), events)
        assertEquals(listOf(session.id), finished)
        assertNull(session.state.value.captureFailure)
    }

    // Cases: MJPEG-CAP-02, MJPEG-CAP-04, MJPEG-CAP-11
    @Test
    fun projectionReturnedAfterStopStillTransfersToAnEngineAndItsCleanupIsAwaited() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val engine = ControlledMjpegEngine(holdStop = true)
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + Dispatchers.Default)
        val projection = org.robolectric.shadow.api.Shadow.newInstanceOf(android.media.projection.MediaProjection::class.java)
        val inputs = MjpegCaptureSessionInputs(projection = projection, projectionCreation = {
            entered.complete(Unit)
            check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            projection
        })
        val transferred = java.util.concurrent.atomic.AtomicReference<android.media.projection.MediaProjection>()
        val session = inputs.session(scope, { _, projection, _ -> transferred.set(projection); engine })
        try {
            session.start()
            withTimeout(5.seconds) { entered.await() }
            session.requestStop(MjpegCaptureSession.StopReason.User)
            release.countDown()
            withTimeout(5.seconds) { engine.stopEntered.await() }
            assertSame(projection, transferred.get())
            assertEquals(listOf(session.id), inputs.finished)
            assertEquals(MjpegCaptureSession.StopReason.User, session.state.value.stopReason)
            assertNull(session.state.value.cleanupSucceeded)
            assertTrue(inputs.saved.isEmpty())
            engine.finishStop.complete(Unit)
            assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
            assertEquals(listOf(session.id, session.id), inputs.finished)
            assertNull(session.state.value.captureFailure)
        } finally {
            release.countDown(); engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
            withTimeout(5.seconds) { session.awaitCleanup(); owner.cancelAndJoin() }
        }
    }

    // Cases: MJPEG-CAP-06, MJPEG-CAP-07
    @Test
    fun aHeldSynchronousParameterSubmissionCannotDelayAcceptanceOfTerminalState() = runBlocking {
        val base = ControlledMjpegEngine()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val owner = SupervisorJob()
        val engine = object : MjpegEngineSession by base {
            override fun updateParameters(parameters: io.screenstream.capture.ScreenCaptureParameters) {
                entered.complete(Unit)
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                throw IllegalStateException("Engine parameter admission closed while submission was pending")
            }
        }
        val session = MjpegCaptureSessionInputs().session(CoroutineScope(owner + Dispatchers.Default), { _, _, _ -> engine })
        try {
            session.start()
            withTimeout(5.seconds) { session.state.first { it.isStreaming } }
            session.updateImageSettings(ImageSettings(jpegQuality = 41))
            withTimeout(5.seconds) { entered.await() }
            base.publish(MjpegEngineState(Phase.Stopped, stopReason = io.screenstream.capture.ScreenCaptureStopReason.Requested))
            withTimeout(5.seconds) { session.state.first { it.isStopRequested } }
            assertEquals(MjpegCaptureSession.StopReason.System, session.state.value.stopReason)
            assertFalse(session.state.value.isStreaming)
            assertNull(session.state.value.captureGeneration)
            assertNull(session.state.value.captureFailure)
            release.countDown()
            assertTrue(withTimeout(5.seconds) { session.awaitCleanup() })
        } finally {
            release.countDown(); base.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
            withTimeout(5.seconds) { session.awaitCleanup(); owner.cancelAndJoin() }
        }
    }

    // Cases: MJPEG-CAP-06, MJPEG-CAP-09
    @Test
    fun unexpectedParameterSubmissionFailureEndsCaptureAndPreservesItsCauseAfterCleanup() = runTest {
        val engine = ControlledMjpegEngine()
        val cause = IllegalArgumentException("Unexpected image update failure")
        val session = MjpegCaptureSessionInputs().session(backgroundScope, { _, _, _ -> engine })
        try {
            session.start(); runCurrent()
            engine.updateFailure = cause
            session.updateImageSettings(ImageSettings(jpegQuality = 41)); runCurrent()
            assertTrue(session.awaitCleanup())
            assertEquals("Engine.start", session.state.value.captureFailure?.stage)
            assertTrue(generateSequence(session.state.value.captureFailure?.cause) { it.cause }.any { it === cause })
            assertEquals(MjpegCaptureSession.StopReason.Error, session.state.value.stopReason)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            engine.release(); session.requestStop(MjpegCaptureSession.StopReason.Shutdown); runCurrent(); session.awaitCleanup()
        }
    }

    private class StoreDelivery : HttpDelivery by TestHttpDelivery() {
        val store = JpegStore().also { it.installLogo(byteArrayOf(99)); it.updateFallbackPolicy(PostStopImage.Placeholder) }
        var beforeCopy: () -> Unit = {}
        override fun registerCapture(id: StreamingModule.CaptureAttemptId, initialCaptureGeneration: Any): Boolean =
            store.registerCapture(id, initialCaptureGeneration)

        override fun updateCaptureStatus(id: StreamingModule.CaptureAttemptId, state: Status, captureGeneration: Any?) =
            store.updateCaptureStatus(id, state, captureGeneration)

        override fun offerJpeg(expectedCaptureGeneration: Any, byteCount: Int, copyTo: (ByteArray) -> Unit) {
            store.offerJpeg(expectedCaptureGeneration, byteCount) { beforeCopy(); copyTo(it) }
        }

        fun selected(): ByteArray {
            val reader = checkNotNull(store.openReader())
            try {
                val frame = checkNotNull(reader.acquireFrame())
                try {
                    return frame.bytes.copyOf(frame.byteCount)
                } finally {
                    reader.releaseFrame(frame)
                }
            } finally {
                reader.close()
            }
        }
    }

    private fun jpeg(payload: Byte): ByteArray = byteArrayOf(-1, -40, payload, 0x22, -1, -39)
}
