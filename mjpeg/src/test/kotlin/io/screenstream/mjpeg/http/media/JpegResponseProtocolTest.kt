package io.screenstream.mjpeg.http.media

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.HttpRequests
import io.screenstream.mjpeg.http.UnstartedHttpListener
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** CIO drives JPEG output; independent raw bytes complement explicit registry retirement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class JpegResponseProtocolTest {
    // Cases: MJPEG-IMG-09, MJPEG-OBS-03, MJPEG-OBS-06
    @Test
    fun jpegUsesAdmissionPinnedValidPrefixAndCompletedBytesRetireExactlyOnce() = runBlocking {
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.installLogo(byteArrayOf(1))
        store.registerCapture(attempt)
        store.updateCaptureStatus(attempt, Status.Active)
        store.offerJpeg(attempt, 16) { it.fill(0x55.toByte()) }
        store.offerJpeg(attempt, 16) { it.fill(0x66.toByte()) }
        val expected = byteArrayOf(0x7f, 0, 1, 2, 3, 4, 5, 6)
        store.offerJpeg(attempt, expected.size) { expected.copyInto(it) }
        val reader = checkNotNull(store.openReader())
        val writer = JpegResponse(reader, MediaFormat.Jpeg)
        val latest = byteArrayOf(9, 10)
        store.offerJpeg(attempt, latest.size) { latest.copyInto(it) }
        val owner = UnstartedHttpListener()
        val requests = HttpRequests()
        val firstJob = Job()
        val secondJob = Job()
        var lateWriter: JpegResponse? = null
        val server = Uuid.random()
        requests.registerMedia(writer, owner.listener, server, "192.0.2.1", 8000, MediaFormat.Jpeg, firstJob, 0)
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
            requests.registerMedia(late, owner.listener, server, "192.0.2.1", 8000, MediaFormat.Jpeg, secondJob, 300)
            requests.clearEndpointsAndGetWebJobs()
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
    fun readerCutoffBeforeWriterHandoffLeavesPinnedLengthButNoCompletedPayload() = runBlocking {
        val store = JpegStore().apply {
            installLogo(byteArrayOf(1, 2, 3, 4))
            updateStoppedImagePolicy(PostStopImage.Placeholder)
        }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg)
        writer.close()
        try {
            val response = receiveFromCio(writer)
            assertEquals("4", response.headers["content-length"])
            assertArrayEquals(byteArrayOf(), response.body)
            assertEquals(0L, writer.progress.completedJpegs)
            assertEquals(0L, writer.progress.completedJpegBytes)
        } finally {
            writer.close()
            store.dispose()
        }
    }

    // Cases: MJPEG-OBS-03
    @Test
    fun overlappingCompletedWritersContributeEveryPayloadBeforeAndAfterRetirement() = runBlocking {
        val store = JpegStore().apply {
            installLogo(byteArrayOf(3, 4, 5))
            updateStoppedImagePolicy(PostStopImage.Placeholder)
        }
        val owner = UnstartedHttpListener()
        val requests = HttpRequests()
        val server = Uuid.random()
        val jobs = listOf(Job(), Job())
        val writers = jobs.map { JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg) }
        try {
            writers.forEachIndexed { index, writer ->
                requests.registerMedia(writer, owner.listener, server, "192.0.2.1", 8000, MediaFormat.Jpeg, jobs[index], 0)
                val output = RecordingSink()
                assertNull(driveWriter(writer, output.asByteWriteChannel()))
                assertArrayEquals(byteArrayOf(3, 4, 5), output.bytes())
            }
            fun assertTotals() {
                val sample = requests.sample(100, null, setOf(owner.listener))
                assertEquals(2L, sample.completedJpegs)
                assertEquals(6L, sample.completedJpegBytes)
                assertEquals(6L, sample.clients.single().completedJpegBytes)
            }
            assertTotals()
            requests.retireMedia(writers[0], 100, setOf(owner.listener))
            requests.retireMedia(writers[0], 100, setOf(owner.listener))
            assertTotals()
            requests.retireMedia(writers[1], 100, setOf(owner.listener))
            requests.retireMedia(writers[1], 100, setOf(owner.listener))
            assertTotals()
        } finally {
            writers.forEach(JpegResponse::close)
            store.dispose()
            jobs.forEach(Job::cancel)
            jobs.joinAll()
            owner.close()
        }
    }

    // Cases: MJPEG-IMG-12
    @Test
    fun partialPayloadAndFooterFaultsAreDistinguishedFromFailureAfterCompletedPayload() = runBlocking {
        val payload = byteArrayOf(21, 22, 23, 24)
        val prefix = "--screenstream-jpeg\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n".toByteArray()
        val footer = "\r\n--screenstream-jpeg\r\n".toByteArray()
        for (stage in listOf("payload", "footer", "flush")) {
            val store = JpegStore().apply {
                installLogo(payload)
                updateStoppedImagePolicy(PostStopImage.Placeholder)
            }
            val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
            val fault = IOException("Arranged $stage failure")
            val expected = when (stage) {
                "payload" -> prefix + payload.take(2).toByteArray()
                "footer" -> prefix + payload + footer.take(2).toByteArray()
                else -> prefix + payload + footer
            }
            val sink = RecordingSink(
                fault = fault,
                failAt = if (stage == "flush") null else expected.size,
                failOnFlush = stage == "flush",
            )
            val buffered = sink.asByteWriteChannel()
            val channel = if (stage == "flush") buffered else object : ByteWriteChannel by buffered {
                override val autoFlush: Boolean = true
            }
            try {
                assertSame(stage, fault, driveWriter(writer, channel))
                assertTrue(stage, sink.failed)
                assertArrayEquals(stage, expected, sink.bytes())
                assertEquals(stage, if (stage == "payload") 0L else 1L, writer.progress.completedJpegs)
                assertEquals(stage, if (stage == "payload") 0L else payload.size.toLong(), writer.progress.completedJpegBytes)
            } finally {
                channel.cancel(null)
                writer.close()
                store.dispose()
            }
        }
    }

    // Cases: MJPEG-IMG-14
    @Test
    fun slowThresholdAccumulatesSkippedPublicationsRecoversAndFencesReplacedSource() = runBlocking {
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.installLogo(byteArrayOf(0))
        store.registerCapture(attempt)
        store.updateCaptureStatus(attempt, Status.Active)
        fun publish(value: Int) {
            store.offerJpeg(attempt, 1) { it[0] = value.toByte() }
        }
        publish(1)
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val sink = RecordingSink()
        val buffered = sink.asByteWriteChannel()
        val payloads = mutableListOf<Int>()
        var previousSource: Any? = null
        val channel = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                val part = sink.bytes().toString(Charsets.ISO_8859_1)
                val marker = "Content-Length: 1\r\n\r\n"
                payloads += part.substringAfterLast(marker).first().code
                val completed = writer.progress.completedJpegs
                assertEquals(payloads.size.toLong(), completed)
                when (completed) {
                    1L -> {
                        assertFalse(writer.progress.isSlow)
                        previousSource = writer.progress.observedCaptureSource
                        repeat(3) { publish(2 + it) } // Two skipped publications.
                    }

                    2L -> {
                        assertFalse(writer.progress.isSlow)
                        repeat(4) { publish(5 + it) } // Three more: accumulated threshold is five.
                    }

                    3L -> {
                        assertTrue(writer.progress.isSlow)
                        publish(9) // Consecutive publication clears slow.
                    }

                    4L -> {
                        assertFalse(writer.progress.isSlow)
                        store.registerCapture(attempt)
                        store.updateCaptureStatus(attempt, Status.Active)
                        repeat(7) { publish(10 + it) }
                    }

                    5L -> {
                        assertFalse(writer.progress.isSlow)
                        assertTrue(writer.progress.observedCaptureSource !== previousSource)
                        writer.close()
                    }

                    else -> throw AssertionError("Unexpected extra part")
                }
            }
        }
        try {
            assertNull(driveWriter(writer, channel))
            assertEquals(listOf(1, 4, 8, 9, 16), payloads)
            assertEquals(5L, writer.progress.completedJpegBytes)
        } finally {
            channel.cancel(null)
            writer.close()
            store.dispose()
        }
    }

    // Cases: MJPEG-OBS-07, MJPEG-IMG-14
    @Test
    fun sampledSlowRequiresCurrentSourceAndListenerAndClearsAfterRecoveryOrRetirement() = runBlocking {
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.registerCapture(attempt)
        store.updateCaptureStatus(attempt, Status.Active)
        fun publish(value: Int) {
            store.offerJpeg(attempt, 1) { it[0] = value.toByte() }
        }
        publish(1)
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val owner = UnstartedHttpListener()
        val obsoleteOwner = UnstartedHttpListener()
        val job = Job()
        val requests = HttpRequests()
        requests.registerMedia(writer, owner.listener, Uuid.random(), "192.0.2.1", 8000, MediaFormat.Mjpeg, job, 0)
        fun sampledSlow(source: Any? = store.snapshot().sourceIdentity, listeners: Set<HttpListener> = setOf(owner.listener)): Boolean =
            requests.sample(0, source, listeners).clients.single().isSlow

        val sink = RecordingSink()
        val buffered = sink.asByteWriteChannel()
        val payloads = mutableListOf<Int>()
        var sourceBeforeReplacement: Any? = null
        val channel = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                val output = sink.bytes().toString(Charsets.ISO_8859_1)
                payloads += output.substringAfterLast("Content-Length: 1\r\n\r\n").first().code
                when (payloads.size) {
                    1 -> repeat(6) { publish(2 + it) } // Five skipped publications.
                    2 -> {
                        assertTrue(writer.progress.isSlow)
                        assertTrue(sampledSlow())
                        assertFalse(sampledSlow(source = null))
                        assertFalse(sampledSlow(listeners = setOf(obsoleteOwner.listener)))
                        publish(8)
                    }

                    3 -> {
                        assertFalse(writer.progress.isSlow)
                        assertFalse(sampledSlow())
                        repeat(6) { publish(9 + it) }
                    }

                    4 -> {
                        assertTrue(writer.progress.isSlow)
                        assertTrue(sampledSlow())
                        sourceBeforeReplacement = checkNotNull(store.snapshot().sourceIdentity)
                        store.registerCapture(attempt)
                        assertTrue(writer.progress.isSlow) // The observation still belongs to the previous source.
                        assertFalse(sampledSlow())
                        writer.close()
                    }

                    else -> throw AssertionError("Unexpected extra part")
                }
            }
        }
        try {
            assertNull(driveWriter(writer, channel))
            assertEquals(listOf(1, 7, 8, 14), payloads)
            requests.retireMedia(writer, 0, setOf(owner.listener))
            // With even its former source supplied, a retired response cannot contribute slow state.
            assertFalse(sampledSlow(source = checkNotNull(sourceBeforeReplacement)))
        } finally {
            channel.cancel(null)
            writer.close()
            store.dispose()
            job.cancel()
            job.join()
            try {
                owner.close()
            } finally {
                obsoleteOwner.close()
            }
        }
    }

    /** Replaces only the engine's output at the public send boundary; respond remains alive throughout. */
    private fun driveWriter(writer: JpegResponse, output: ByteWriteChannel): Throwable? {
        var failure: Throwable? = null
        testApplication {
            application {
                sendPipeline.intercept(ApplicationSendPipeline.After) {
                    val content = subject
                    if (content is OutgoingContent.WriteChannelContent) {
                        try {
                            content.writeTo(output)
                        } catch (cause: Throwable) {
                            failure = cause
                        }
                        proceedWith(object : OutgoingContent.NoContent() {
                            override val status: HttpStatusCode = HttpStatusCode.NoContent
                        })
                    }
                }
                routing { get("/jpeg") { writer.respond(call) } }
            }
            client.get("/jpeg")
        }
        return failure
    }

    private class RecordingSink(
        private val fault: IOException = IOException("Unused fault"),
        private val failAt: Int? = null,
        private val failOnFlush: Boolean = false,
    ) : RawSink {
        private val received = ByteArrayOutputStream()
        var failed: Boolean = false
            private set

        fun bytes(): ByteArray = received.toByteArray()
        override fun write(source: Buffer, byteCount: Long) {
            val count = minOf(byteCount, failAt?.let { (it - received.size()).toLong() } ?: byteCount)
            received.write(source.readByteArray(count.toInt()))
            if (failAt != null && received.size() == failAt) {
                failed = true
                throw fault
            }
        }

        override fun flush() {
            if (failOnFlush) {
                failed = true
                throw fault
            }
        }

        override fun close() = Unit
    }

    private suspend fun receiveFromCio(writer: JpegResponse): Response = withContext(Dispatchers.IO) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val parent = Job()
        val listener = HttpListener(parent, "127.0.0.1", port) {
            routing { get("/jpeg") { writer.respond(call) } }
        }
        try {
            withTimeout(15.seconds) { listener.startListening() }
            Socket().use { socket ->
                socket.soTimeout = 15_000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 15_000)
                socket.getOutputStream().apply {
                    write("GET /jpeg HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val input = socket.getInputStream().buffered()
                val status = readLine(input).split(' ')[1].toInt()
                val headers = buildMap {
                    while (true) {
                        val line = readLine(input)
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        check(colon > 0)
                        put(line.substring(0, colon).lowercase(), line.substring(colon + 1).trim())
                    }
                }
                // Read actual bytes to EOF, allowing the explicitly arranged truncated response.
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

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)
}
