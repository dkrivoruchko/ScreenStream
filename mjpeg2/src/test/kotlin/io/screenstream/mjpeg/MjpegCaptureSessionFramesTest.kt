package io.screenstream.mjpeg

import com.elvishew.xlog.LogLevel
import com.elvishew.xlog.XLog
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureStats
import io.screenstream.capture.ScreenCaptureStopReason
import io.screenstream.mjpeg.settings.ImageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
// This SDK-invariant adapter boundary does not prove platform capture, graphics or JPEG validity.
internal class MjpegCaptureSessionFramesTest {
    @Before
    fun initializeLogger() {
        XLog.init(LogLevel.NONE)
    }

    // Cases: MJPEG-CAP-08
    @Test
    fun copiedFramesRemainOwnedWhenBorrowedSourceStorageIsReused() = runTest(StandardTestDispatcher()) {
        val engine = EngineInput()
        val inputs = MjpegCaptureSessionInputs()
        val owned = mutableListOf<ByteArray>()
        val counts = mutableListOf<Int>()
        val session = inputs.frameSession(backgroundScope, engine) { count, copyTo ->
            counts += count
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            owned += destination
        }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)

            val source = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)
            engine.deliver(BorrowedInput(source))
            source.fill(0)
            byteArrayOf(-1, -40, 0x33, 0x44, -1, -39).copyInto(source)
            engine.deliver(BorrowedInput(source))
            source.fill(0)

            assertEquals(listOf(6, 6), counts)
            assertArrayEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), owned[0])
            assertArrayEquals(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39, 0x55, 0x55), owned[1])
            assertStreaming(session)
        } finally {
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08
    @Test
    fun ignoringAnOfferAllowsTheNextDistinctFrameToBeCopied() = runTest(StandardTestDispatcher()) {
        val engine = EngineInput()
        val inputs = MjpegCaptureSessionInputs()
        var acceptOffer = false
        var owned: ByteArray? = null
        var copiedCount: Int? = null
        val session = inputs.frameSession(backgroundScope, engine) { count, copyTo ->
            if (acceptOffer) {
                val destination = ByteArray(8) { 0x55 }
                copyTo(destination)
                copiedCount = count
                owned = destination
            }
        }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)) {
                error("An ignored offer must not request its bytes")
            })
            assertNull(owned)
            assertStreaming(session)

            acceptOffer = true
            engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39)))
            assertEquals(6, copiedCount)
            assertArrayEquals(byteArrayOf(-1, -40, 0x33, 0x44, -1, -39, 0x55, 0x55), owned)
            assertStreaming(session)
        } finally {
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun outputFailureBeforeCopyEndsCaptureAndSurvivesSuccessfulCleanup() = runTest(StandardTestDispatcher()) {
        val engine = EngineInput()
        val cause = IllegalStateException("Output rejected the offer")
        val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { _, _ -> throw cause }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)))
            assertFrameFailure(session, cause)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(true, session.state.value.cleanupSucceeded)
        } finally {
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09, MJPEG-CAP-10
    @Test
    fun outputFailureAfterCopyRetainsOwnedBytesAndWaitsForExactRegistrationReceipt() = runTest(StandardTestDispatcher()) {
        val registrationReleased = CompletableDeferred<Unit>()
        val unregisterEntered = CompletableDeferred<Unit>()
        val engine = EngineInput(unregister = {
            unregisterEntered.complete(Unit)
            registrationReleased.await()
        })
        val cause = IllegalStateException("Output failed after copying")
        var owned: ByteArray? = null
        val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { _, copyTo ->
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            owned = destination
            throw cause
        }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)
            val source = byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)

            engine.deliver(BorrowedInput(source))
            source.fill(0)
            runCurrent()

            assertArrayEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), owned)
            assertFrameFailure(session, cause)
            assertTrue(engine.mediaStopped.isCompleted)
            assertTrue(unregisterEntered.isCompleted)
            assertNull(session.state.value.cleanupSucceeded)
            val cleanup = async(start = CoroutineStart.UNDISPATCHED) { session.awaitCleanup() }
            assertFalse(cleanup.isCompleted)

            registrationReleased.complete(Unit)
            runCurrent()
            assertTrue(withTimeout(5.seconds) { cleanup.await() })
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
            assertEquals(true, session.state.value.cleanupSucceeded)
            assertArrayEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), owned)
        } finally {
            registrationReleased.complete(Unit)
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun borrowedByteCountFailureBecomesTheRetainedOutputFailure() = runTest(StandardTestDispatcher()) {
        // Defensive boundary fault: a conforming Engine permits byte-count access in this callback.
        val engine = EngineInput()
        val cause = IllegalStateException("Borrowed byte count unavailable")
        var outputEntered = false
        val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { _, _ -> outputEntered = true }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)

            engine.deliver(
                BorrowedInput(
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
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun undersizedOutputDestinationRemainsUnchangedAndBecomesTheRetainedOutputFailure() = runTest(StandardTestDispatcher()) {
        val engine = EngineInput()
        val destination = ByteArray(5) { 0x55 }
        var offeredCount: Int? = null
        var owned: ByteArray? = null
        val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { count, copyTo ->
            offeredCount = count
            copyTo(destination)
            owned = destination
        }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertStreaming(session)

            engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)))
            assertEquals(6, offeredCount)
            assertNull(owned)
            assertArrayEquals(byteArrayOf(0x55, 0x55, 0x55, 0x55, 0x55), destination)
            val cause = checkNotNull(session.state.value.captureFailure).cause
            assertTrue(cause is IndexOutOfBoundsException)
            assertFrameFailure(session, cause)
            runCurrent()

            assertTrue(awaitCleanup(session))
            assertFrameFailure(session, cause)
            assertNull(session.state.value.cleanupFailure)
        } finally {
            settle(session)
        }
    }

    // Cases: MJPEG-CAP-08, MJPEG-CAP-09
    @Test
    fun shortAndExcessiveCopyResultsFailWithoutPublishingAnOwnedFrame() = runTest(StandardTestDispatcher()) {
        // Defensive boundary faults: a conforming Engine copy always returns the declared byte count.
        for (returnedCount in listOf(5, 7)) {
            val engine = EngineInput()
            var owned: ByteArray? = null
            val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { _, copyTo ->
                val destination = ByteArray(8) { 0x55 }
                copyTo(destination)
                owned = destination
            }
            try {
                session.runCapture(ImageSettings())
                runCurrent()
                assertStreaming(session)

                engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)) { returnedCount })
                assertNull(owned)
                val failure = checkNotNull(session.state.value.captureFailure)
                assertEquals("Output.frame", failure.stage)
                assertTrue(failure.cause is IllegalStateException)
                assertEquals("Incomplete JPEG copy", failure.cause.message)
                assertFrameFailure(session, failure.cause)
                runCurrent()

                assertTrue(awaitCleanup(session))
                assertFrameFailure(session, failure.cause)
                assertNull(session.state.value.cleanupFailure)
            } finally {
                settle(session)
            }
        }
    }

    // Cases: MJPEG-CAP-07, MJPEG-CAP-08
    @Test
    fun activeFrameCanBeCopiedBeforeStartReturnsWithoutConfirmingStreaming() = runTest(StandardTestDispatcher()) {
        val startReleased = CompletableDeferred<Unit>()
        val startEntered = CompletableDeferred<Unit>()
        val engine = EngineInput(start = {
            startEntered.complete(Unit)
            startReleased.await()
        })
        var owned: ByteArray? = null
        var count: Int? = null
        val session = MjpegCaptureSessionInputs().frameSession(backgroundScope, engine) { byteCount, copyTo ->
            val destination = ByteArray(8) { 0x55 }
            copyTo(destination)
            owned = destination
            count = byteCount
        }
        try {
            session.runCapture(ImageSettings())
            runCurrent()
            assertTrue(startEntered.isCompleted)
            assertActive(session)
            assertFalse(session.state.value.isStreaming)

            engine.deliver(BorrowedInput(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39)))
            assertEquals(6, count)
            assertArrayEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), owned)
            assertFalse(session.state.value.isStreaming)
            assertNull(session.state.value.captureFailure)
            assertNull(session.state.value.cleanupSucceeded)

            startReleased.complete(Unit)
            runCurrent()
            assertStreaming(session)
            assertArrayEquals(byteArrayOf(-1, -40, 0x11, 0x22, -1, -39, 0x55, 0x55), owned)
        } finally {
            startReleased.complete(Unit)
            settle(session)
        }
    }

    private fun MjpegCaptureSessionInputs.frameSession(
        scope: CoroutineScope,
        engine: EngineInput,
        onJpeg: (Int, (ByteArray) -> Unit) -> Unit,
    ): MjpegCaptureSession = session(
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
            "captureFailure=${state.captureFailure}; cleanupFailure=${state.cleanupFailure}",
            MjpegCaptureSession.State.Status.Active,
            state.status,
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

    private suspend fun settle(session: MjpegCaptureSession) {
        withContext(NonCancellable) {
            session.stop(MjpegCaptureSession.StopReason.Shutdown)
            awaitCleanup(session)
        }
    }

    private suspend fun awaitCleanup(session: MjpegCaptureSession): Boolean = withTimeout(5.seconds) { session.awaitCleanup() }

    /** Supplies only the observations and operation outcomes needed by these frame histories. */
    private class EngineInput(
        private val start: suspend () -> Unit = {},
        private val unregister: suspend () -> Unit = {},
    ) : MjpegEngineSession {
        private val observed = MutableStateFlow(MjpegEngineState(MjpegEngineState.Phase.NotStarted))
        private var consumer: ((MjpegBorrowedJpeg) -> Unit)? = null
        val mediaStopped = CompletableDeferred<Unit>()

        override val currentState: MjpegEngineState
            get() = observed.value

        override val states: Flow<MjpegEngineState> = observed
        override val stats: Flow<ScreenCaptureStats> = flow { awaitCancellation() }

        override suspend fun start(parameters: ScreenCaptureParameters) {
            observed.value = MjpegEngineState(MjpegEngineState.Phase.Active)
            start()
        }

        override fun updateParameters(parameters: ScreenCaptureParameters) = Unit

        override fun registerFrameConsumer(consumer: (MjpegBorrowedJpeg) -> Unit): MjpegFrameRegistration {
            this.consumer = consumer
            return MjpegFrameRegistration {
                this.consumer = null
                unregister()
            }
        }

        override suspend fun stop() {
            observed.value = MjpegEngineState(MjpegEngineState.Phase.Stopped, stopReason = ScreenCaptureStopReason.Requested)
            mediaStopped.complete(Unit)
        }

        fun deliver(frame: BorrowedInput) {
            val callback = checkNotNull(consumer)
            frame.duringCallback { callback(frame) }
        }
    }

    /** Callback/thread borrowing is enforced, without pretending to encode or validate JPEG contents. */
    private class BorrowedInput(
        private val payload: ByteArray,
        private val count: () -> Int = { payload.size },
        private val copy: (ByteArray) -> Int = { destination ->
            // The public Engine validates the complete destination range before writing any bytes.
            if (destination.size < payload.size) {
                throw IndexOutOfBoundsException("Complete JPEG does not fit in destination")
            }
            payload.copyInto(destination)
            payload.size
        },
    ) : MjpegBorrowedJpeg {
        private var callbackThread: Thread? = null

        override val byteCount: Int
            get() {
                checkAccess()
                return count()
            }

        override fun copyTo(destination: ByteArray): Int {
            checkAccess()
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

        private fun checkAccess() {
            check(callbackThread === Thread.currentThread()) { "Borrowed input accessed outside its callback/thread" }
        }
    }
}
