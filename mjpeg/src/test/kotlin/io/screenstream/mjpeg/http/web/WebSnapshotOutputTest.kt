package io.screenstream.mjpeg.http.web

import io.ktor.server.application.ApplicationCall
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.readReason
import io.ktor.websocket.readText
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class WebSnapshotOutputTest {
    // Cases: MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun blockedSendAndBlockedFlushAreCanceledAtTheFiveSecondBudget() = runTest(timeout = 5.seconds) {
        val routes = routes()
        for (stage in listOf("send", "flush")) {
            val session = Session(coroutineContext).apply {
                if (stage == "send") sendGate = CompletableDeferred() else flushGate = CompletableDeferred()
            }
            val writing = async { runCatching { with(routes) { session.sendStateSnapshot("{\"access\":\"open\"}") } } }
            try {
                runCurrent()
                assertTrue(session.sendEntered.isCompleted)
                assertEquals(stage == "flush", session.flushEntered.isCompleted)
                advanceTimeBy(4_999)
                runCurrent()
                assertFalse(writing.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(writing.isCompleted)
                assertTrue(writing.await().exceptionOrNull() is TimeoutCancellationException)
                assertTrue(if (stage == "send") session.sendExited.isCompleted else session.flushExited.isCompleted)
                assertEquals(stage == "flush", session.flushEntered.isCompleted)
            } finally {
                writing.cancelAndJoin()
                session.dispose()
            }
        }
    }

    // Cases: MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun sendAndFlushShareOneBudgetRatherThanReceivingFiveSecondsEach() = runTest(timeout = 5.seconds) {
        val routes = routes()
        val sendGate = CompletableDeferred<Unit>()
        val session = Session(coroutineContext).apply {
            this.sendGate = sendGate
            flushGate = CompletableDeferred()
        }
        val writing = async { runCatching { with(routes) { session.sendStateSnapshot("{}") } } }
        try {
            runCurrent()
            advanceTimeBy(3_000)
            sendGate.complete(Unit)
            runCurrent()
            assertTrue(session.flushEntered.isCompleted)
            assertEquals(1, session.accepted.size)
            advanceTimeBy(1_999)
            runCurrent()
            assertFalse(writing.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(writing.isCompleted)
            assertTrue(writing.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(session.flushExited.isCompleted)
        } finally {
            writing.cancelAndJoin()
            session.dispose()
        }
    }

    // Cases: MJPEG-WEB-12, MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun callerCancellationSettlesEitherBlockedOperationWithoutWaitingForItsBudget() = runTest(timeout = 5.seconds) {
        val routes = routes()
        for (stage in listOf("send", "flush")) {
            val session = Session(coroutineContext).apply {
                if (stage == "send") sendGate = CompletableDeferred() else flushGate = CompletableDeferred()
            }
            val writing = async { with(routes) { session.sendStateSnapshot("{}") } }
            try {
                runCurrent()
                val beforeCancel = testScheduler.currentTime
                writing.cancel()
                runCurrent()
                assertTrue(writing.isCompleted)
                assertTrue(writing.isCancelled)
                assertTrue(if (stage == "send") session.sendExited.isCompleted else session.flushExited.isCompleted)
                assertEquals(beforeCancel, testScheduler.currentTime)
            } finally {
                writing.cancelAndJoin()
                session.dispose()
            }
        }
    }

    // Cases: MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun literalUtf8SnapshotsAtAndBelowTheByteCapAreSentAndFlushed() = runTest(timeout = 5.seconds) {
        val routes = routes()
        for (size in listOf(262_143, 262_144)) {
            val session = Session(coroutineContext)
            val json = snapshotWithByteCount(size)
            try {
                with(routes) { session.sendStateSnapshot(json) }
                val frame = session.accepted.single() as Frame.Text
                assertEquals(size, frame.data.size)
                assertArrayEquals(json.toByteArray(Charsets.UTF_8), frame.data)
                assertEquals(json, frame.readText())
                assertTrue(session.flushExited.isCompleted)
                assertTrue(session.owner.isActive)
            } finally {
                session.dispose()
            }
        }
    }

    // Cases: MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun oversizedSnapshotSendsOnlyTheDocumentedCloseAndWaitsForSessionTermination() = runTest(timeout = 5.seconds) {
        val routes = routes()
        val session = Session(coroutineContext).apply { completeOnClose = true }
        try {
            val beforeClose = testScheduler.currentTime
            val outcome = runCatching { with(routes) { session.sendStateSnapshot(snapshotWithByteCount(262_145)) } }
            assertEquals(beforeClose, testScheduler.currentTime)
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            val close = session.accepted.single() as Frame.Close
            assertEquals(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Page settings exceed snapshot limit"), close.readReason())
            assertTrue(session.flushExited.isCompleted)
            assertTrue(session.owner.isCompleted)
        } finally {
            session.dispose()
        }
    }

    // Cases: MJPEG-WEB-13
    @Test(timeout = 15_000)
    fun oversizedSnapshotCloseCannotWaitBeyondFiveSecondsForTheDefaultSessionJob() = runTest(timeout = 5.seconds) {
        val routes = routes()
        val session = Session(coroutineContext)
        val writing = async { runCatching { with(routes) { session.sendStateSnapshot(snapshotWithByteCount(262_145)) } } }
        try {
            runCurrent()
            assertTrue(session.accepted.single() is Frame.Close)
            assertTrue(session.flushExited.isCompleted)
            advanceTimeBy(4_999)
            runCurrent()
            assertFalse(writing.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(writing.isCompleted)
            assertTrue(writing.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(session.owner.isActive)
        } finally {
            writing.cancelAndJoin()
            session.dispose()
        }
    }

    private fun snapshotWithByteCount(size: Int): String {
        val prefix = """{"title":"雪\"\\\n"""
        val suffix = "\"}"
        return prefix + "x".repeat(size - prefix.toByteArray(Charsets.UTF_8).size - suffix.length) + suffix
    }

    private fun routes(): WebRoutes = WebRoutes(httpTestContext(), object : WebRoutes.RequestHandler {
        override val webState = MutableStateFlow<WebState?>(null)
        override fun isListenerActive(listener: HttpListener): Boolean = false
        override fun exchangeToken(listener: HttpListener, token: String?): SecretValue? = null
        override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? = null
        override suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, token: String?, accessCookie: String?) =
            error("Unexpected media request")

        override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebRoutes.WebSocketAdmission =
            error("Unexpected admission")

        override fun isWebSocketCurrent(listener: HttpListener, credentialGeneration: Long): Boolean = false
    })

    private class Session(parentContext: CoroutineContext) : DefaultWebSocketSession {
        val owner = Job(parentContext[Job])
        override val coroutineContext = parentContext + owner
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override var pingIntervalMillis = 0L
        override var timeoutMillis = 0L
        override val extensions = emptyList<WebSocketExtension<*>>()
        override val incoming = Channel<Frame>()
        override val outgoing = Channel<Frame>()
        override val closeReason = CompletableDeferred<CloseReason?>()
        val accepted = mutableListOf<Frame>()
        val sendEntered = CompletableDeferred<Unit>()
        val sendExited = CompletableDeferred<Unit>()
        val flushEntered = CompletableDeferred<Unit>()
        val flushExited = CompletableDeferred<Unit>()
        var sendGate: CompletableDeferred<Unit>? = null
        var flushGate: CompletableDeferred<Unit>? = null
        var completeOnClose = false

        override suspend fun send(frame: Frame) {
            sendEntered.complete(Unit)
            try {
                sendGate?.await()
                accepted += frame
                if (frame is Frame.Close && completeOnClose) {
                    closeReason.complete(frame.readReason())
                    owner.complete()
                }
            } finally {
                sendExited.complete(Unit)
            }
        }

        override suspend fun flush() {
            flushEntered.complete(Unit)
            try {
                flushGate?.await()
            } finally {
                flushExited.complete(Unit)
            }
        }

        @OptIn(io.ktor.utils.io.InternalAPI::class)
        override fun start(negotiatedExtensions: List<WebSocketExtension<*>>) = Unit

        @Deprecated("Use cancel() instead.", level = DeprecationLevel.ERROR)
        override fun terminate() {
            owner.cancel()
        }

        suspend fun dispose() {
            incoming.cancel()
            outgoing.cancel()
            closeReason.cancel()
            owner.cancelAndJoin()
        }
    }
}
