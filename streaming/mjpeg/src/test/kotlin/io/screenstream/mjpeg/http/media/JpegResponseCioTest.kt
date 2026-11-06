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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
            val response = receiveFromCio(writer)
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
            assertArrayEquals(latest, receiveFromCio(late).body)
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
            val response = receiveFromCio(writer, closeConnection = false)
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

    private suspend fun receiveFromCio(writer: JpegResponse, closeConnection: Boolean = true): Response = withContext(Dispatchers.IO) {
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
                val input = socket.getInputStream().buffered()
                val status = readLine(input).split(' ')[1].toInt()
                val headers = readHttpResponseHeaders { readLine(input) }
                // Read actual bytes to EOF, including connection termination after a revoked response.
                Response(status, headers, input.readBytes())
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
