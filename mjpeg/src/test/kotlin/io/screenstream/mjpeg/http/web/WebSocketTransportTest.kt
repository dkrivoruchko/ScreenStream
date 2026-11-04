package io.screenstream.mjpeg.http.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.screenstream.mjpeg.http.CioHttpListener
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.media.JpegStore.ImageKind
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class WebSocketTransportTest {
    // Cases: MJPEG-WEB-10, MJPEG-WEB-11, MJPEG-WEB-12
    @Test
    fun heldSendKeepsOneImmutableSnapshotThenReadsLatestStateAndFencesNewGeneration() = runBlocking {
        val fixture = Fixture()
        val checked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holdOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        fixture.handler.beforeCurrentCheck = {
            if (holdOnce.compareAndSet(true, false)) {
                checked.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        try {
            fixture.start()
            RawSocket(fixture.port).use { peer ->
                assertTrue(checked.await(5, java.util.concurrent.TimeUnit.SECONDS))
                fixture.handler.webState.value = fixture.handler.state("Skipped")
                fixture.handler.webState.value = fixture.handler.state("Latest").copy(notice = "paused", imageKind = ImageKind.Retained)
                release.countDown()
                val first = org.json.JSONObject(peer.readFrame().second.decodeToString())
                assertEquals("Transport contract", first.getJSONObject("display").getString("title"))
                assertEquals("stopped", first.getString("notice"))
                assertEquals("logo", first.getString("imageKind"))
                val latest = org.json.JSONObject(peer.readFrame().second.decodeToString())
                assertEquals("Latest", latest.getJSONObject("display").getString("title"))
                assertEquals("paused", latest.getString("notice"))
                assertEquals("retained", latest.getString("imageKind"))
                fixture.handler.webState.value = fixture.handler.state("Successor secret").copy(credentialGeneration = 1)
                withTimeout(5.seconds) { fixture.completed.await() }
                assertTrue(peer.textUntilEnd().none { it.contains("Successor secret") })
            }
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    // Cases: MJPEG-WEB-12, MJPEG-WEB-13
    @Test
    fun oversizedIncomingFrameAndAbruptDisconnectSettleTheActualCall() = runBlocking {
        for (oversized in listOf(false, true)) {
            val fixture = Fixture()
            try {
                fixture.start()
                RawSocket(fixture.port).use { peer ->
                    assertEquals(1, peer.readFrame().first)
                    if (oversized) {
                        peer.send(2, ByteArray(4_097) { 1 })
                        withTimeout(12.seconds) { fixture.completed.await() }
                        peer.drainToEnd()
                    } else {
                        peer.close()
                        withTimeout(5.seconds) { fixture.completed.await() }
                    }
                }
            } finally {
                fixture.close()
            }
        }
    }

    // Cases: MJPEG-WEB-12, MJPEG-WEB-13
    @Test
    fun queuedApplicationDataIsRejectedAsReadOnlyWhenAdmissionCompletes() = runBlocking {
        val fixture = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fixture.handler.admissionAction = {
            entered.countDown()
            check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            fixture.start()
            RawSocket(fixture.port).use { peer ->
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                repeat(5) { peer.send(2, byteArrayOf(it.toByte())) }
                release.countDown()
                peer.deadlineAfter(5_000)
                var frame = peer.readFrame()
                while (frame.first != 8) frame = peer.readFrame()
                val closeCode = ((frame.second[0].toInt() and 255) shl 8) or (frame.second[1].toInt() and 255)
                assertEquals(1008, closeCode)
                peer.send(8, frame.second)
                withTimeout(5.seconds) { fixture.completed.await() }
                peer.drainToEnd()
            }
        } finally {
            release.countDown()
            fixture.close()
        }
    }

    // Cases: MJPEG-WEB-09, MJPEG-WEB-13
    @Test
    fun deniedPeerThatNeverAnswersCloseCannotHoldTheDefaultSessionBeyondCloseBudget() = runBlocking {
        val fixture = Fixture(denied = true)
        try {
            fixture.start()
            RawSocket(fixture.port).use { peer ->
                val denial = peer.readFrame()
                assertEquals(1, denial.first)
                assertEquals("{\"access\":\"pin_required\"}", denial.second.decodeToString())
                val close = peer.readFrame()
                assertEquals(8, close.first)
                assertEquals(1000, ((close.second[0].toInt() and 255) shl 8) or (close.second[1].toInt() and 255))
                withTimeout(12.seconds) { fixture.completed.await() }
                peer.drainToEnd()
            }
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-WEB-11, MJPEG-WEB-13
    @Test
    fun stalledPeerWithLargeChangingSnapshotsSettlesWithoutListenerStop() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.handler.webState.value = fixture.handler.state("雪".repeat(80_000))
            fixture.start()
            RawSocket(fixture.port, receiveBuffer = 1_024).use { peer ->
                withTimeout(14.seconds) {
                    var revision = 0
                    while (!fixture.completed.isCompleted) {
                        val finished = select {
                            fixture.completed.onAwait { true }
                            fixture.handler.sendCycles.onReceive { false }
                        }
                        if (finished) break
                        revision++
                        fixture.handler.webState.value = fixture.handler.state("雪".repeat(80_000) + revision)
                    }
                    fixture.completed.await()
                }
                peer.drainToEnd()
            }
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-WEB-11, MJPEG-WEB-12, MJPEG-WEB-13
    @Test
    fun peerThatReadsButNeverAnswersPingIsDisconnectedAndSettled() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.start()
            RawSocket(fixture.port).use { peer ->
                var pingSeen = false
                val overallDeadline = peer.deadlineAfter(38_000)
                withContext(Dispatchers.IO) {
                    fun readTextFrame(): ByteArray {
                        while (true) {
                            val (opcode, payload) = peer.readFrame()
                            when (opcode) {
                                1 -> return payload
                                9 -> pingSeen = true
                                else -> throw AssertionError("Expected state snapshot, received opcode $opcode")
                            }
                        }
                    }

                    val initialPayload = readTextFrame()
                    val repeatDeadline = minOf(
                        overallDeadline,
                        java.lang.management.ManagementFactory.getRuntimeMXBean().uptime + 5_000,
                    )
                    peer.deadlineAt(repeatDeadline)
                    val initial = Json.parseToJsonElement(initialPayload.decodeToString()).jsonObject
                    assertEquals(setOf("access", "notice", "hasImage", "imageKind", "display"), initial.keys)
                    assertEquals("open", initial.getValue("access").jsonPrimitive.content)
                    val repeated = Json.parseToJsonElement(readTextFrame().decodeToString()).jsonObject
                    assertEquals(initial, repeated)
                    peer.deadlineAt(overallDeadline)
                    try {
                        while (true) {
                            val frame = peer.readFrame()
                            if (frame.first == 9) pingSeen = true
                            if (frame.first == 8) break
                        }
                    } catch (_: EOFException) {
                    } catch (cause: java.io.IOException) {
                        if (cause is java.net.SocketTimeoutException) throw cause
                    }
                }
                assertTrue(pingSeen)
                withTimeout(5.seconds) { fixture.completed.await() }
            }
        } finally {
            fixture.close()
        }
    }

    private class Fixture(denied: Boolean = false) {
        val port: Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val parent = Job()
        val completed = CompletableDeferred<Unit>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val handler = Handler(denied)
        val listener = CioHttpListener(parent, "127.0.0.1", port) { current ->
            intercept(ApplicationCallPipeline.Monitoring) {
                checkNotNull(call.coroutineContext[Job]).invokeOnCompletion { cause ->
                    if (cause != null) failures += cause
                    completed.complete(Unit)
                }
                try {
                    proceed()
                } catch (cause: Throwable) {
                    failures += cause
                    throw cause
                }
            }
            WebRoutes(httpTestContext(), handler).installRoutes(this, current)
        }

        suspend fun start() {
            listener.startListening()
        }

        suspend fun close() {
            try {
                withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
            } finally {
                parent.cancel()
                withTimeout(5.seconds) { parent.join() }
            }
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress("127.0.0.1", port))
                assertEquals(port, it.localPort)
            }
        }
    }

    private class Handler(private val denied: Boolean) : WebRoutes.RequestHandler {
        val sendCycles = Channel<Unit>(Channel.UNLIMITED)

        @Volatile
        var beforeCurrentCheck: () -> Unit = {}

        @Volatile
        var admissionAction: () -> Unit = {}
        override val webState = MutableStateFlow<WebState?>(state("Transport contract"))
        fun state(title: String): WebState = WebState(0, true, "stopped", ImageKind.Logo, WebPageSettings(title = title))
        override fun isListenerActive(listener: HttpListener): Boolean = true
        override fun exchangeToken(listener: HttpListener, token: String?): SecretValue? = null
        override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? = null
        override suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, token: String?, accessCookie: String?) = Unit
        override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebRoutes.WebSocketAdmission {
            admissionAction()
            return if (denied) WebRoutes.WebSocketAdmission.Denied(null) else WebRoutes.WebSocketAdmission.Accepted(0)
        }

        override fun isWebSocketCurrent(listener: HttpListener, credentialGeneration: Long): Boolean {
            beforeCurrentCheck()
            sendCycles.trySend(Unit)
            return true
        }
    }

    private class RawSocket(port: Int, receiveBuffer: Int = 8_192) : AutoCloseable {
        private val socket = Socket().also { connection ->
            try {
                connection.receiveBufferSize = receiveBuffer
                connection.soTimeout = 40_000
                connection.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            } catch (cause: Throwable) {
                connection.close()
                throw cause
            }
        }
        private val input = socket.getInputStream().buffered()
        private val output = socket.getOutputStream()
        private var deadlineMillis: Long? = null

        fun deadlineAfter(millis: Long): Long {
            val deadline = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime + millis
            deadlineAt(deadline)
            return deadline
        }

        fun deadlineAt(deadline: Long) {
            deadlineMillis = deadline
        }

        private fun beforeRead() {
            val remaining = deadlineMillis?.minus(java.lang.management.ManagementFactory.getRuntimeMXBean().uptime)
            if (remaining != null && remaining <= 0) throw java.net.SocketTimeoutException("Overall peer read deadline expired")
            socket.soTimeout = minOf(40_000L, remaining ?: 40_000L).toInt().coerceAtLeast(1)
        }

        init {
            try {
                output.write(
                    ("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: http://127.0.0.1:$port\r\n" +
                            "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray()
                )
                output.flush()
                assertEquals("HTTP/1.1 101 Switching Protocols", line(input))
                while (line(input).isNotEmpty()) {
                }
            } catch (cause: Throwable) {
                socket.close()
                throw cause
            }
        }

        fun readFrame(): Pair<Int, ByteArray> {
            val first = readByte()
            val second = readByte()
            assertEquals(0, second and 128)
            val size = when (val short = second and 127) {
                126 -> (readByte() shl 8) or readByte()
                127 -> {
                    var length = 0L
                    repeat(8) { length = (length shl 8) or readByte().toLong() }
                    check(length <= 300_000)
                    length.toInt()
                }

                else -> short
            }
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                beforeRead()
                val read = input.read(bytes, offset, size - offset)
                if (read < 0) throw EOFException()
                offset += read
            }
            return (first and 15) to bytes
        }

        fun send(opcode: Int, payload: ByteArray) {
            output.write(128 or opcode)
            if (payload.size < 126) output.write(128 or payload.size) else {
                output.write(128 or 126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 255)
            }
            val mask = byteArrayOf(1, 2, 3, 4)
            output.write(mask)
            output.write(ByteArray(payload.size) { index -> (payload[index].toInt() xor mask[index % 4].toInt()).toByte() })
            output.flush()
        }

        fun textUntilEnd(): List<String> {
            val texts = mutableListOf<String>()
            deadlineAfter(5_000)
            try {
                while (true) {
                    val frame = readFrame()
                    if (frame.first == 1) texts += frame.second.decodeToString()
                }
            } catch (_: EOFException) {
            } catch (cause: java.io.IOException) {
                if (cause is java.net.SocketTimeoutException) throw cause
            }
            return texts
        }

        fun drainToEnd() {
            deadlineAfter(5_000)
            try {
                while (true) {
                    beforeRead(); if (input.read() < 0) break
                }
            } catch (cause: java.io.IOException) {
                if (cause is java.net.SocketTimeoutException) throw cause
            }
        }

        private fun readByte(): Int {
            beforeRead()
            return input.read().also { if (it < 0) throw EOFException() }
        }

        override fun close() {
            socket.close()
        }
    }

    companion object {
        private fun line(input: InputStream): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) throw EOFException()
                if (next == 13) {
                    check(input.read() == 10)
                    return bytes.toString(Charsets.US_ASCII)
                }
                bytes.write(next)
            }
        }
    }
}
