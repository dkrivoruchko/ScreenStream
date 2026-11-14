package io.screenstream.mjpeg

import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], shadows = [InertMjpegProjectionShadow::class])
// This SDK-invariant adapter boundary does not prove platform capture, graphics or JPEG validity.
internal class MjpegCaptureSessionFramesTest {
    @get:Rule
    val koin = MjpegKoinTestRule()

    // Cases: MJPEG-CAP-08
    @Test
    fun copiedFramesRemainOwnedWhenBorrowedSourceStorageIsReused() = runTest(StandardTestDispatcher()) {
        val engine = FrameEngineSession()
        val inputs = MjpegCaptureSessionInputs()
        val copiedFrames = mutableListOf<ByteArray>()
        val offeredByteCounts = mutableListOf<Int>()
        val session = inputs.createFrameSession(backgroundScope, engine) { count, copyTo ->
            offeredByteCounts += count
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            copiedFrames += destination
        }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            val source = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)
            engine.deliver(BorrowedJpegInput(source))
            source.fill(0)
            byteArrayOf(-1, -40, 0x33, 0x44, -1, -39).copyInto(source)
            engine.deliver(BorrowedJpegInput(source))
            source.fill(0)

            assertEquals(listOf(6, 6), offeredByteCounts)
            assertContentEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), copiedFrames[0])
            assertContentEquals(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39, 0x55, 0x55), copiedFrames[1])
            assertStreaming(session)
        } finally {
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08
    @Test
    fun ignoringAnOfferAllowsTheNextDistinctFrameToBeCopied() = runTest(StandardTestDispatcher()) {
        val engine = FrameEngineSession()
        val inputs = MjpegCaptureSessionInputs()
        var acceptOffer = false
        var copiedJpeg: ByteArray? = null
        var copiedCount: Int? = null
        val session = inputs.createFrameSession(backgroundScope, engine) { count, copyTo ->
            if (acceptOffer) {
                val destination = ByteArray(8) { 0x55 }
                copyTo(destination)
                copiedCount = count
                copiedJpeg = destination
            }
        }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)) {
                error("An ignored offer must not request its bytes")
            })
            assertNull(copiedJpeg)
            assertStreaming(session)

            acceptOffer = true
            engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39)))
            assertEquals(6, copiedCount)
            assertContentEquals(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39, 0x55, 0x55), copiedJpeg)
            assertStreaming(session)
        } finally {
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun outputFailureBeforeCopyEndsCaptureAndSurvivesSuccessfulCleanup() = runTest(StandardTestDispatcher()) {
        val engine = FrameEngineSession()
        val cause = IllegalStateException("Output rejected the offer")
        val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { _, _ -> throw cause }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)))
            assertFrameFailure(session, cause)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(true, session.state.value.cleanupSucceeded)
        } finally {
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09, MJPEG-CAP-10
    @Test
    fun outputFailureAfterCopyRetainsOwnedBytesAndWaitsForExactRegistrationReceipt() = runTest(StandardTestDispatcher()) {
        val allowUnregisterToReturn = CompletableDeferred<Unit>()
        val unregisterEntered = CompletableDeferred<Unit>()
        val engine = FrameEngineSession(onUnregister = {
            unregisterEntered.complete(Unit)
            allowUnregisterToReturn.await()
        })
        val cause = IllegalStateException("Output failed after copying")
        var copiedJpeg: ByteArray? = null
        val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { _, copyTo ->
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            copiedJpeg = destination
            throw cause
        }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)
            val source = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)

            engine.deliver(BorrowedJpegInput(source))
            source.fill(0)
            runCurrent()

            assertContentEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), copiedJpeg)
            assertFrameFailure(session, cause)
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(unregisterEntered.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(cleanup.isCompleted)

            allowUnregisterToReturn.complete(Unit)
            runCurrent()
            assertTrue(withTimeout(5.seconds) { cleanup.await() })
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(true, session.state.value.cleanupSucceeded)
            assertContentEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), copiedJpeg)
        } finally {
            allowUnregisterToReturn.complete(Unit)
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun borrowedByteCountFailureBecomesTheRetainedOutputFailure() = runTest(StandardTestDispatcher()) {
        // Defensive boundary fault: a conforming Engine permits byte-count access in this callback.
        val engine = FrameEngineSession()
        val cause = IllegalStateException("Borrowed byte count unavailable")
        var outputEntered = false
        val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { _, _ -> outputEntered = true }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            engine.deliver(
                BorrowedJpegInput(
                    payload = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39),
                    count = { throw cause },
                )
            )
            assertFalse(outputEntered)
            assertFrameFailure(session, cause)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun copyFailureBecomesTheRetainedOutputFailure() = runTest(StandardTestDispatcher()) {
        val engine = FrameEngineSession()
        val cause = IllegalStateException("Borrowed JPEG copy failed")
        val destination = ByteArray(8) { 0x55 }
        var copyEntered = false
        var offeredCount: Int? = null
        var copiedJpeg: ByteArray? = null
        val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { count, copyTo ->
            offeredCount = count
            copyTo(destination)
            copiedJpeg = destination
        }
        try {
            session.start()
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)) {
                copyEntered = true
                throw cause
            })
            assertEquals(6, offeredCount)
            assertTrue(copyEntered)
            assertNull(copiedJpeg)
            assertFrameFailure(session, cause)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            stopAndAwaitCleanup(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun shortAndExcessiveCopyResultsFailWithoutPublishingAnOwnedFrame() = runTest(StandardTestDispatcher()) {
        // Defensive boundary faults: a conforming Engine copy always returns the declared byte count.
        for (returnedCount in listOf(5, 7)) {
            val engine = FrameEngineSession()
            var copiedJpeg: ByteArray? = null
            val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { _, copyTo ->
                val destination = ByteArray(8) { 0x55 }
                copyTo(destination)
                copiedJpeg = destination
            }
            try {
                session.start()
                runCurrent()
                assertStreaming(session)

                engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)) { returnedCount })
                assertNull(copiedJpeg)
                val failure = checkNotNull(session.state.value.captureFailure)
                assertEquals("Output.frame", failure.stage)
                assertIs<IllegalStateException>(failure.cause)
                assertEquals("Incomplete JPEG copy", failure.cause.message)
                assertFrameFailure(session, failure.cause)
                runCurrent()

                assertTrue(awaitCleanup(session))
                assertFrameFailure(session, failure.cause)
                assertNull(session.state.value.cleanupFailure)
            } finally {
                stopAndAwaitCleanup(session)
            }
        }
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-08
    @Test
    fun activeFrameCanBeCopiedBeforeStartReturnsWithoutConfirmingStreaming() = runTest(StandardTestDispatcher()) {
        val allowStartToReturn = CompletableDeferred<Unit>()
        val startEntered = CompletableDeferred<Unit>()
        val engine = FrameEngineSession(onStart = {
            startEntered.complete(Unit)
            allowStartToReturn.await()
        })
        var copiedJpeg: ByteArray? = null
        var count: Int? = null
        val session = MjpegCaptureSessionInputs().createFrameSession(backgroundScope, engine) { byteCount, copyTo ->
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            copiedJpeg = destination
            count = byteCount
        }
        try {
            session.start()
            runCurrent()
            assertTrue(startEntered.isCompleted)
            assertActive(session)
            assertFalse(session.state.value.isStreaming)

            engine.deliver(BorrowedJpegInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)))
            assertEquals(6, count)
            assertContentEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), copiedJpeg)
            assertFalse(session.state.value.isStreaming)
            assertNull(session.state.value.captureFailure)
            assertNull(session.state.value.cleanupSucceeded)

            allowStartToReturn.complete(Unit)
            runCurrent()
            assertStreaming(session)
            assertContentEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), copiedJpeg)
        } finally {
            allowStartToReturn.complete(Unit)
            stopAndAwaitCleanup(session)
        }
    }

    private fun MjpegCaptureSessionInputs.createFrameSession(
        scope: CoroutineScope,
        engine: FrameEngineSession,
        onJpeg: (Int, (ByteArray) -> Unit) -> Unit,
    ): MjpegCaptureSession = createSession(
        scope = scope,
        engineFactory = { actualContext, actualProjection, _ ->
            assertSame(context, actualContext)
            assertSame(projection, actualProjection)
            engine
        },
        onJpeg = onJpeg,
    )

    private fun assertStreaming(session: MjpegCaptureSession) {
        assertActive(session)
        val state = session.state.value
        assertTrue(state.isStreaming)
        assertNull(state.captureFailure)
        assertNull(state.cleanupFailure)
        assertNull(state.cleanupSucceeded)
        assertNull(state.suspensionProblem)
    }

    private fun assertActive(session: MjpegCaptureSession) {
        val state = session.state.value
        assertEquals(
            MjpegCaptureSession.State.Status.Active,
            state.status,
            "captureFailure=${state.captureFailure}; cleanupFailure=${state.cleanupFailure}",
        )
    }

    private fun assertFrameFailure(session: MjpegCaptureSession, cause: Throwable) {
        val state = session.state.value
        assertEquals(MjpegCaptureSession.State.Status.Failed, state.status)
        assertFalse(state.isStreaming)
        assertNull(state.suspensionProblem)
        assertEquals("Output.frame", state.captureFailure?.stage)
        assertSame(cause, state.captureFailure?.cause)
    }

    private suspend fun stopAndAwaitCleanup(session: MjpegCaptureSession) {
        withContext(NonCancellable) {
            session.requestStop(MjpegCaptureSession.StopReason.Shutdown)
            awaitCleanup(session)
        }
    }

    private suspend fun awaitCleanup(session: MjpegCaptureSession): Boolean = withTimeout(5.seconds) { session.awaitCleanup() }

    /** Supplies only the observations and operation outcomes needed by these frame histories. */
    private class FrameEngineSession(
        private val onStart: suspend () -> Unit = {},
        private val onUnregister: suspend () -> Unit = {},
    ) : MjpegEngineSession {
        private val observedState = MutableStateFlow(MjpegEngineState(MjpegEngineState.Phase.NotStarted))
        private var consumer: ((MjpegBorrowedJpeg) -> Unit)? = null
        val mediaStopped = CompletableDeferred<Unit>()

        override val currentState: MjpegEngineState
            get() = observedState.value

        override val states: Flow<MjpegEngineState> = observedState
        override val stats: kotlinx.coroutines.flow.StateFlow<ScreenCaptureStats> = MutableStateFlow(initialEngineStatistics())

        override suspend fun start(parameters: ScreenCaptureParameters) {
            observedState.value = MjpegEngineState(MjpegEngineState.Phase.Active)
            onStart()
        }

        override fun updateParameters(parameters: ScreenCaptureParameters) = Unit

        override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
            this.consumer = consumer
            return MjpegFrameRegistration {
                this.consumer = null
                onUnregister()
            }
        }

        override suspend fun stop() {
            observedState.value = MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = ScreenCaptureStopReason.Requested)
            mediaStopped.complete(Unit)
        }

        fun deliver(frame: BorrowedJpegInput) {
            val callback = checkNotNull(consumer)
            frame.duringCallback { callback(frame) }
        }
    }

    /** Callback/thread borrowing is enforced, without pretending to encode or validate JPEG contents. */
    private class BorrowedJpegInput(
        private val payload: ByteArray,
        private val count: () -> Int = { payload.size },
        private val copy: (ByteArray) -> Int = { destination ->
            payload.copyInto(destination)
            payload.size
        },
    ) : MjpegBorrowedJpeg {
        private var callbackThread: Thread? = null

        override val byteCount: Int
            get() {
                checkCallbackAccess()
                return count()
            }

        override fun copyTo(destination: ByteArray): Int {
            checkCallbackAccess()
            return copy(destination)
        }

        fun duringCallback(body: () -> Unit) {
            check(callbackThread == null)
            callbackThread = Thread.currentThread()
            try {
                body()
            } finally {
                callbackThread = null
            }
        }

        private fun checkCallbackAccess() {
            check(callbackThread === Thread.currentThread()) { "Borrowed input accessed outside its callback/thread" }
        }
    }
}
