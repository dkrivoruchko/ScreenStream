package io.screenstream.mjpeg.http.media

import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.CioHttpListener
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpRequests
import io.screenstream.mjpeg.http.HttpResponseHeaders
import io.screenstream.mjpeg.http.UnstartedHttpListenerFixture
import io.screenstream.mjpeg.http.readHttpResponseHeaders
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Physical CIO handoff and wire termination, independent of deterministic writer interception. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class JpegResponseCioTest {
    // Cases: MJPEG-IMG-09, MJPEG-OBS-03, MJPEG-OBS-06
    @Test
    fun jpegUsesAdmissionPinnedValidPrefixAndCompletedBytesRetireExactlyOnce() = runBlocking {
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.installLogo(byteArrayOf(1))
        store.registerCapture(attempt, attempt)
        store.updateCaptureStatus(attempt, Status.Active, attempt)
        store.offerJpeg(attempt, 16) { it.fill(0x55.toByte()) }
        store.offerJpeg(attempt, 16) { it.fill(0x66.toByte()) }
        val expected = byteArrayOf(0x7f, 0, 1, 2, 3, 4, 5, 6)
        store.offerJpeg(attempt, expected.size) { expected.copyInto(it) }
        val reader = checkNotNull(store.openReader())
        val writer = JpegResponse(reader, MediaFormat.Jpeg)
        val latest = byteArrayOf(9, 10)
        store.offerJpeg(attempt, latest.size) { latest.copyInto(it) }
        val owner = UnstartedHttpListenerFixture()
        val requests = HttpRequests()
        val firstJob = Job()
        val secondJob = Job()
        var lateWriter: JpegResponse? = null
        val server = Uuid.random()
        requests.registerMedia(writer, owner.listener, server, "192.0.2.1", 8000, firstJob, 0)
        try {
            val response = receiveFromCio(writer, maximumBodyBytes = expected.size)
            assertEquals(200, response.status)
            assertEquals("image/jpeg", response.headers["content-type"])
            assertEquals(expected.size.toString(), response.headers["content-length"])
            assertArrayEquals(expected, response.body)
            assertEquals(1L, writer.progress.completedJpegs)
            assertEquals(expected.size.toLong(), writer.progress.completedJpegBytes)
            val active = requests.sample(0, null, setOf(owner.listener))
            assertEquals(1L, active.completedJpegs)
            assertEquals(expected.size.toLong(), active.clients.single().completedJpegBytes)
            requests.retireMedia(writer, 100, setOf(owner.listener))
            requests.retireMedia(writer, 200, setOf(owner.listener))
            val retiredRow = requests.sample(200, null, setOf(owner.listener))
            assertEquals(1L, retiredRow.completedJpegs)
            assertEquals(expected.size.toLong(), retiredRow.completedJpegBytes)
            assertEquals(expected.size.toLong(), retiredRow.clients.single().completedJpegBytes)
            assertNull(reader.acquireFrame())

            // A different real writer completes after history detaches; its bytes remain global.
            val late = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg)
            lateWriter = late
            requests.registerMedia(late, owner.listener, server, "192.0.2.1", 8000, secondJob, 300)
            requests.clearEndpointsAndGetWebSocketJobs()
            assertArrayEquals(latest, receiveFromCio(late, maximumBodyBytes = latest.size).body)
            requests.retireMedia(late, 400, setOf(owner.listener))
            requests.retireMedia(late, 500, setOf(owner.listener))
            val detached = requests.sample(500, null, setOf(owner.listener))
            assertEquals(2L, detached.completedJpegs)
            assertEquals((expected.size + latest.size).toLong(), detached.completedJpegBytes)
            assertTrue(detached.clients.isEmpty())
        } finally {
            writer.close()
            lateWriter?.close()
            store.dispose()
            firstJob.cancel()
            secondJob.cancel()
            firstJob.join()
            secondJob.join()
            owner.close()
        }
    }

    // Cases: MJPEG-IMG-09
    @Test
    fun readerCutoffBeforeCioWriterHandoffAbortsItsPinnedLengthResponse() = runBlocking {
        val store = JpegStore().apply {
            installLogo(byteArrayOf(1, 2, 3, 4))
            updateFallbackPolicy(PostStopImage.Placeholder)
        }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg)
        writer.close()
        try {
            val response = receiveFromCio(writer, maximumBodyBytes = 0, closeConnection = false)
            assertEquals(200, response.status)
            assertEquals("4", response.headers["content-length"])
            assertArrayEquals(byteArrayOf(), response.body)
            assertEquals(0L, writer.progress.completedJpegs)
            assertEquals(0L, writer.progress.completedJpegBytes)
        } finally {
            writer.close()
            store.dispose()
        }
    }

    @Test
    fun responseReaderRejectsAnExtraByteWithoutWaitingForPeerEof() {
        withResponsePeer({ socket, stop ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nXY".toByteArray())
                flush()
            }
            check(stop.await(5, TimeUnit.SECONDS))
        }) { socket ->
            assertThrows(IllegalStateException::class.java) { readResponse(socket, maximumBodyBytes = 1) }
        }
    }

    @Test
    fun responseReaderDeadlineIncludesProgressingHeadersAndBody() {
        for (stage in listOf("headers", "body")) {
            val writes = AtomicInteger()
            withResponsePeer({ socket, stop ->
                val output = socket.getOutputStream()
                output.write(
                    if (stage == "headers") "HTTP/1.1 200 OK\r\nX-Progress: ".toByteArray()
                    else "HTTP/1.1 200 OK\r\nContent-Length: 1024\r\n\r\n".toByteArray()
                )
                try {
                    while (!stop.await(25, TimeUnit.MILLISECONDS)) {
                        output.write('x'.code)
                        output.flush()
                        writes.incrementAndGet()
                    }
                } catch (_: java.net.SocketException) {
                    // The timed-out reader owns and closes this connection.
                }
            }) { socket ->
                val started = hostUptimeMillis()
                assertThrows(SocketTimeoutException::class.java) { readResponse(socket, maximumBodyBytes = 1024, timeoutMillis = 400) }
                assertTrue("The peer must make progress during the aggregate budget", writes.get() >= 2)
                assertTrue("Progress extended the reader budget", hostUptimeMillis() - started < 3_000)
            }
        }
    }

    private suspend fun receiveFromCio(writer: JpegResponse, maximumBodyBytes: Int, closeConnection: Boolean = true): Response = withContext(Dispatchers.IO) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val parent = Job()
        val listener = CioHttpListener(parent, "127.0.0.1", port) {
            routing { get("/jpeg") { writer.respond(call) } }
        }
        try {
            withTimeout(15.seconds) { listener.startListening() }
            Socket().use { socket ->
                socket.soTimeout = 15_000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 15_000)
                socket.getOutputStream().apply {
                    val connectionHeader = if (closeConnection) "Connection: close\r\n" else ""
                    write("GET /jpeg HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$connectionHeader\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                // Read actual bytes to EOF, including connection termination after a revoked response.
                readResponse(socket, maximumBodyBytes)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
                } finally {
                    parent.cancel()
                    withTimeout(15.seconds) { parent.join() }
                }
            }
        }
    }

    /** One host-time budget includes status, headers and the EOF needed by the cutoff history. */
    private fun readResponse(socket: Socket, maximumBodyBytes: Int, timeoutMillis: Long = 15_000): Response {
        require(maximumBodyBytes in 0 until Int.MAX_VALUE)
        val deadline = hostUptimeMillis() + timeoutMillis
        val wireInput = socket.getInputStream().buffered()
        fun beforeRead() {
            val remaining = deadline - hostUptimeMillis()
            if (remaining <= 0) throw SocketTimeoutException("Overall CIO response read deadline expired")
            socket.soTimeout = minOf(15_000L, remaining).toInt()
        }

        fun afterRead() {
            if (hostUptimeMillis() >= deadline) throw SocketTimeoutException("Overall CIO response read deadline expired")
        }

        val input = object : InputStream() {
            override fun read(): Int {
                beforeRead()
                val value = wireInput.read()
                afterRead()
                return value
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                beforeRead()
                val count = wireInput.read(bytes, offset, length)
                afterRead()
                return count
            }
        }
        val status = readLine(input).split(' ')[1].toInt()
        val headers = readHttpResponseHeaders { readLine(input) }
        val body = input.readNBytes(maximumBodyBytes + 1)
        check(body.size <= maximumBodyBytes) { "CIO response exceeded $maximumBodyBytes body bytes" }
        return Response(status, headers, body)
    }

    private fun withResponsePeer(serve: (Socket, CountDownLatch) -> Unit, assertions: (Socket) -> Unit) {
        val stop = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit { server.accept().use { serve(it, stop) } }
            try {
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 5_000)
                    assertions(socket)
                }
            } finally {
                stop.countDown()
                try {
                    peer.get(5, TimeUnit.SECONDS)
                } finally {
                    server.close()
                    executor.shutdownNow()
                    assertTrue("Response peer did not terminate", executor.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun hostUptimeMillis(): Long = ManagementFactory.getRuntimeMXBean().uptime

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException("Incomplete HTTP line")
            if (value == 13) {
                check(input.read() == 10)
                return bytes.toString(Charsets.US_ASCII)
            }
            check(bytes.size() < 16_384)
            bytes.write(value)
        }
    }

    private class Response(val status: Int, val headers: HttpResponseHeaders, val body: ByteArray)
}
