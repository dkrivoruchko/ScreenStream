package io.screenstream.mjpeg.http.media

import android.os.SystemClock
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.runTestApplication
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.asByteWriteChannel
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.HttpRequests
import io.screenstream.mjpeg.http.UnstartedHttpListenerFixture
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.readByteArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Deterministic writer output, storage ownership, timing and request accounting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class JpegResponseWriterTest {
    // Cases: MJPEG-IMG-09
    @Test
    fun readerCutoffBeforeWriterHandoffCancelsWithoutCompletingPayload(): Unit = runBlocking {
        val store = JpegStore().apply {
            installLogo(byteArrayOf(1, 2, 3, 4))
            updateFallbackPolicy(PostStopImage.Placeholder)
        }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg)
        writer.close()
        try {
            val output = RecordingSink()
            val failure = writeResponseAndCaptureFailure(writer, output.asByteWriteChannel())
            assertIs<CancellationException>(failure)
            assertEquals("JPEG response revoked", failure.message)
            assertContentEquals(byteArrayOf(), output.bytes())
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
            updateFallbackPolicy(PostStopImage.Placeholder)
        }
        val owner = UnstartedHttpListenerFixture()
        val requests = HttpRequests()
        val server = Uuid.random()
        val jobs = listOf(Job(), Job())
        val writers = jobs.map { JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg) }
        try {
            writers.forEachIndexed { index, writer ->
                requests.registerMedia(writer, owner.listener, server, "192.0.2.1", 8000, jobs[index], 0)
                val output = RecordingSink()
                assertNull(writeResponseAndCaptureFailure(writer, output.asByteWriteChannel()))
                assertContentEquals(byteArrayOf(3, 4, 5), output.bytes())
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
                updateFallbackPolicy(PostStopImage.Placeholder)
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
                assertSame(fault, writeResponseAndCaptureFailure(writer, channel), stage)
                assertTrue(sink.failed, stage)
                assertContentEquals(expected, sink.bytes(), stage)
                assertEquals(if (stage == "payload") 0L else 1L, writer.progress.completedJpegs, stage)
                assertEquals(if (stage == "payload") 0L else payload.size.toLong(), writer.progress.completedJpegBytes, stage)
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
        var generation = Any()
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.installLogo(byteArrayOf(0))
        store.registerCapture(attempt, generation)
        store.updateCaptureStatus(attempt, Status.Active, generation)
        fun publish(value: Int) {
            store.offerJpeg(generation, 1) { it[0] = value.toByte() }
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
                        previousSource = writer.progress.observedCaptureGeneration
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
                        generation = Any()
                        store.registerCapture(attempt, generation)
                        store.updateCaptureStatus(attempt, Status.Active, generation)
                        repeat(7) { publish(10 + it) }
                    }

                    5L -> {
                        assertFalse(writer.progress.isSlow)
                        assertTrue(writer.progress.observedCaptureGeneration !== previousSource)
                        writer.close()
                    }

                    else -> throw AssertionError("Unexpected extra part")
                }
            }
        }
        try {
            assertNull(writeResponseAndCaptureFailure(writer, channel))
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
        var generation = Any()
        val store = JpegStore()
        val attempt = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.registerCapture(attempt, generation)
        store.updateCaptureStatus(attempt, Status.Active, generation)
        fun publish(value: Int) {
            store.offerJpeg(generation, 1) { it[0] = value.toByte() }
        }
        publish(1)
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val owner = UnstartedHttpListenerFixture()
        val obsoleteOwner = UnstartedHttpListenerFixture()
        val job = Job()
        val requests = HttpRequests()
        requests.registerMedia(writer, owner.listener, Uuid.random(), "192.0.2.1", 8000, job, 0)
        fun sampledSlow(source: Any? = store.snapshot().captureGeneration, listeners: Set<HttpListener> = setOf(owner.listener)): Boolean =
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
                        sourceBeforeReplacement = checkNotNull(store.snapshot().captureGeneration)
                        generation = Any()
                        store.registerCapture(attempt, generation)
                        assertTrue(writer.progress.isSlow) // The observation still belongs to the previous source.
                        assertFalse(sampledSlow())
                        writer.close()
                    }

                    else -> throw AssertionError("Unexpected extra part")
                }
            }
        }
        try {
            assertNull(writeResponseAndCaptureFailure(writer, channel))
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

    // Cases: MJPEG-IMG-10, MJPEG-IMG-11, MJPEG-IMG-14
    @Test(timeout = 15_000)
    fun publicationAndReaderCutoffInterruptPendingRepeatWaitWithoutAdvancingClocks() {
        for (change in listOf("publication", "close")) {
            val store = JpegStore()
            val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
            val generation = Any()
            store.registerCapture(id, generation)
            store.offerJpeg(generation, 2) { byteArrayOf(1, 2).copyInto(it) }
            val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
            val sink = RecordingSink()
            val buffered = sink.asByteWriteChannel()
            var flushes = 0
            val channel = object : ByteWriteChannel by buffered {
                override suspend fun flush() {
                    buffered.flush()
                    if (++flushes == 2) writer.close()
                }
            }
            try {
                withWriterOnTestScheduler(writer, channel) { writing, scheduler ->
                    // The initial write has returned from flush; the unchanged publication now
                    // suspends on its repeat deadline, rather than receiving a change inside flush.
                    assertEquals(1, flushes)
                    assertFalse(writing.isCompleted)
                    val elapsedBeforeChange = SystemClock.elapsedRealtime()
                    val virtualBeforeChange = scheduler.currentTime
                    if (change == "publication") {
                        store.offerJpeg(generation, 3) { byteArrayOf(3, 4, 5).copyInto(it) }
                    } else writer.close()
                    scheduler.runCurrent()
                    assertTrue(writing.isCompleted)
                    writing.await()
                    assertEquals(elapsedBeforeChange, SystemClock.elapsedRealtime())
                    assertEquals(virtualBeforeChange, scheduler.currentTime)
                }
                val payloads = if (change == "publication") listOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5)) else listOf(byteArrayOf(1, 2))
                assertContentEquals(multipartBytes(payloads), sink.bytes())
                assertEquals(payloads.size, flushes)
                assertEquals(payloads.size.toLong(), writer.progress.completedJpegs)
                assertEquals(if (change == "close") 2L else 5L, writer.progress.completedJpegBytes)
                assertFalse(writer.progress.isSlow)
            } finally {
                channel.cancel(null)
                writer.close()
                store.dispose()
            }
        }
    }

    // Cases: MJPEG-IMG-06, MJPEG-IMG-12, MJPEG-OBS-09
    @Test
    fun failedWriterReleasesAcquiredStorageEvenWhileItsNumericHistoryRemains() = runBlocking {
        val store = JpegStore()
        val generation = Any()
        val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.registerCapture(id, generation)
        var initialStorage: ByteArray? = null
        store.offerJpeg(generation, 16_384) {
            initialStorage = it
            it.fill(42)
        }
        val owner = UnstartedHttpListenerFixture()
        val job = Job()
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Jpeg)
        val requests = HttpRequests()
        requests.registerMedia(writer, owner.listener, Uuid.random(), "192.0.2.1", 8000, job, 0)
        val failure = IOException("acquired output failed")
        val sink = object : RawSink {
            override fun write(source: Buffer, byteCount: Long) {
                store.offerJpeg(generation, 1) { it[0] = 7 }
                throw failure
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val output = sink.asByteWriteChannel()
        try {
            assertSame(failure, writeResponseAndCaptureFailure(writer, output))
            requests.retireMedia(writer, 10, setOf(owner.listener))
            assertEquals(1, requests.sample(11, generation, setOf(owner.listener)).clients.size)
            store.offerJpeg(generation, 16_384) {
                assertSame(initialStorage, it)
                it.fill(9)
            }
            assertEquals(0L, requests.sample(11, generation, setOf(owner.listener)).completedJpegBytes)
        } finally {
            output.cancel(null)
            writer.close()
            store.dispose()
            job.cancel()
            job.join()
            owner.close()
        }
    }

    // Cases: MJPEG-IMG-13, MJPEG-IMG-14
    @Test
    fun slowObservationPrecedesFailureOfItsPayload() = runBlocking {
        val store = JpegStore()
        val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        val generation = Any()
        store.registerCapture(id, generation)
        store.offerJpeg(generation, 1) { it[0] = 1 }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val failure = IOException("payload failed")
        val expected = multipartBytes(listOf(byteArrayOf(1))) +
                "Content-Type: image/jpeg\r\nContent-Length: 16384\r\n\r\n".toByteArray(Charsets.US_ASCII) + byteArrayOf(7, 7, 7)
        val sink = RecordingSink(fault = failure, failAt = expected.size)
        var flushedInitial = false
        val buffered = sink.asByteWriteChannel()
        val channel = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                if (flushedInitial) throw AssertionError("Failed payload must not flush")
                flushedInitial = true
                repeat(6) { value -> store.offerJpeg(generation, 16_384) { it.fill((value + 2).toByte()) } }
            }
        }
        try {
            assertSame(failure, writeResponseAndCaptureFailure(writer, channel))
            assertTrue(sink.failed)
            assertContentEquals(expected, sink.bytes())
            assertTrue(writer.progress.isSlow)
            assertSame(generation, writer.progress.observedCaptureGeneration)
            assertEquals(1L, writer.progress.completedJpegs)
            assertEquals(1L, writer.progress.completedJpegBytes)
        } finally {
            channel.cancel(null)
            writer.close()
            store.dispose()
        }
    }

    // Cases: MJPEG-IMG-08, MJPEG-IMG-15
    @Test(timeout = 15_000)
    fun actualWriterLeavesEmptySelectionWaitOnPublicationReaderCutoffOrDisposal() {
        for (change in listOf("before-next-acquisition", "waiting-publication", "waiting-close", "waiting-dispose")) {
            val store = JpegStore()
            val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
            val generation = Any()
            val successor = Any()
            store.registerCapture(id, generation)
            store.offerJpeg(generation, 1) { it[0] = 1 }
            val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
            val sink = RecordingSink()
            val buffered = sink.asByteWriteChannel()
            var flushes = 0
            fun publishSuccessor() {
                store.updateCaptureStatus(id, Status.Active, successor)
                store.offerJpeg(successor, 2) { byteArrayOf(7, 8).copyInto(it) }
            }

            val output = object : ByteWriteChannel by buffered {
                override suspend fun flush() {
                    buffered.flush()
                    flushes++
                    if (flushes == 1) {
                        store.updateCaptureStatus(id, Status.Suspended, null)
                        assertEquals(JpegStore.ImageKind.None, store.snapshot().imageKind)
                        if (change == "before-next-acquisition") publishSuccessor()
                    } else if (flushes == 2) writer.close() else throw AssertionError("Unexpected part")
                }
            }
            try {
                withWriterOnTestScheduler(writer, output) { writing, scheduler ->
                    assertEquals(if (change == "before-next-acquisition") 2 else 1, flushes)
                    if (change != "before-next-acquisition") {
                        assertFalse(writing.isCompleted)
                        assertEquals(1L, writer.progress.completedJpegs)
                        assertEquals(JpegStore.ImageKind.None, store.snapshot().imageKind)
                        when (change) {
                            "waiting-publication" -> publishSuccessor()
                            "waiting-close" -> writer.close()
                            else -> store.dispose()
                        }
                        scheduler.runCurrent()
                    }
                    writing.await()
                }
                val payloads = if (change.endsWith("publication") || change == "before-next-acquisition") {
                    listOf(byteArrayOf(1), byteArrayOf(7, 8))
                } else listOf(byteArrayOf(1))
                assertContentEquals(multipartBytes(payloads), sink.bytes())
                assertEquals(payloads.size.toLong(), writer.progress.completedJpegs)
            } finally {
                output.cancel(null)
                writer.close()
                store.dispose()
            }
        }
    }

    // Cases: MJPEG-IMG-06, MJPEG-IMG-10
    @Test
    fun publicationDuringPartHeadersOrPayloadCannotReplaceTheAcquiredImage() = runBlocking {
        for (stage in listOf("headers", "payload")) {
            val store = JpegStore()
            val generation = Any()
            val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
            store.registerCapture(id, generation)
            val pinned = ByteArray(16_384) { (it % 251).toByte() }
            val newer = byteArrayOf(7, 0, 8)
            store.offerJpeg(generation, pinned.size) { pinned.copyInto(it) }
            val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
            val received = ByteArrayOutputStream()
            val expected = multipartBytes(listOf(pinned, newer))
            val headerLength = multipartBytes(listOf(pinned)).size - pinned.size - "\r\n--screenstream-jpeg\r\n".length
            val changeAt = headerLength + if (stage == "payload") pinned.size / 2 else 0
            var changed = false
            val sink = object : RawSink {
                override fun write(source: Buffer, byteCount: Long) {
                    var remaining = byteCount
                    if (!changed) {
                        val beforeChange = minOf(remaining, (changeAt - received.size()).toLong())
                        received.write(source.readByteArray(beforeChange.toInt()))
                        remaining -= beforeChange
                        if (received.size() == changeAt) {
                            changed = true
                            store.offerJpeg(generation, newer.size) { newer.copyInto(it) }
                        }
                    }
                    received.write(source.readByteArray(remaining.toInt()))
                    if (received.size() == expected.size) writer.close()
                }

                override fun flush() = Unit
                override fun close() = Unit
            }
            val buffered = sink.asByteWriteChannel()
            val output = object : ByteWriteChannel by buffered {
                override val autoFlush = true
            }
            try {
                assertNull(writeResponseAndCaptureFailure(writer, output))
                assertTrue(changed)
                assertContentEquals(expected, received.toByteArray())
                assertEquals(2L, writer.progress.completedJpegs)
                assertEquals((pinned.size + newer.size).toLong(), writer.progress.completedJpegBytes)
            } finally {
                output.cancel(null)
                writer.close()
                store.dispose()
            }
        }
    }

    // Cases: MJPEG-IMG-11, MJPEG-IMG-14
    @Test(timeout = 15_000)
    fun unchangedSelectionWakeupsNeverRepeatBeforeTheElapsedRealtimeDeadline() {
        val store = JpegStore().apply { installLogo(byteArrayOf(5, 6)) }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val sink = RecordingSink()
        val buffered = sink.asByteWriteChannel()
        var flushes = 0
        val output = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                if (++flushes == 2) writer.close()
            }
        }
        try {
            withWriterOnTestScheduler(writer, output) { writing, scheduler ->
                assertEquals(1, flushes)
                store.updateFallbackPolicy(PostStopImage.Placeholder)
                scheduler.runCurrent()
                assertEquals(1, flushes)
                ShadowSystemClock.advanceBy(999, TimeUnit.MILLISECONDS)
                scheduler.advanceTimeBy(999.milliseconds)
                scheduler.runCurrent()
                store.updateFallbackPolicy(PostStopImage.LastAvailableFrame)
                scheduler.runCurrent()
                assertEquals(1, flushes)
                assertFalse(writing.isCompleted)
                ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS)
                scheduler.advanceTimeBy(1.milliseconds)
                scheduler.runCurrent()
                writing.await()
            }
            assertEquals(2, flushes)
            assertEquals(2L, writer.progress.completedJpegs)
            assertEquals(4L, writer.progress.completedJpegBytes)
            assertFalse(writer.progress.isSlow)
            assertNull(writer.progress.observedCaptureGeneration)
            assertContentEquals(multipartBytes(listOf(byteArrayOf(5, 6), byteArrayOf(5, 6))), sink.bytes())
        } finally {
            output.cancel(null)
            writer.close()
            store.dispose()
        }
    }

    // Cases: MJPEG-IMG-11, MJPEG-IMG-14
    @Test(timeout = 15_000)
    fun slowObservationSurvivesLogoAndSameGenerationRetainedRepeatsButResetsForAnotherGeneration() {
        val store = JpegStore()
        val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        val generation = Any()
        val successor = Any()
        store.installLogo(byteArrayOf(0))
        store.registerCapture(id, generation)
        store.offerJpeg(generation, 1) { it[0] = 1 }
        val writer = JpegResponse(checkNotNull(store.openReader()), MediaFormat.Mjpeg)
        val sink = RecordingSink()
        val buffered = sink.asByteWriteChannel()
        var flushes = 0
        val output = object : ByteWriteChannel by buffered {
            override suspend fun flush() {
                buffered.flush()
                when (++flushes) {
                    1 -> repeat(6) { value -> store.offerJpeg(generation, 1) { it[0] = (value + 2).toByte() } }
                    2 -> {
                        assertTrue(writer.progress.isSlow)
                        store.updateCaptureStatus(id, Status.Suspended, null)
                    }

                    3 -> {
                        assertTrue(writer.progress.isSlow)
                        assertSame(generation, writer.progress.observedCaptureGeneration)
                        store.updateFallbackPolicy(PostStopImage.LastAvailableFrame)
                    }

                    4 -> assertTrue(writer.progress.isSlow)
                    5 -> {
                        assertTrue(writer.progress.isSlow)
                        store.updateCaptureStatus(id, Status.Active, successor)
                        store.offerJpeg(successor, 1) { it[0] = 9 }
                        store.updateCaptureStatus(id, Status.Suspended, null)
                    }

                    6 -> {
                        assertFalse(writer.progress.isSlow)
                        assertSame(successor, writer.progress.observedCaptureGeneration)
                        assertEquals(JpegStore.ImageKind.Retained, store.snapshot().imageKind)
                        writer.close()
                    }

                    else -> throw AssertionError("Unexpected part")
                }
            }
        }
        try {
            withWriterOnTestScheduler(writer, output) { writing, scheduler ->
                assertEquals(4, flushes)
                assertTrue(writer.progress.isSlow)
                ShadowSystemClock.advanceBy(1_000, TimeUnit.MILLISECONDS)
                scheduler.advanceTimeBy(1_000.milliseconds)
                scheduler.runCurrent()
                writing.await()
            }
            assertContentEquals(multipartBytes(listOf(1, 7, 0, 7, 7, 9).map { byteArrayOf(it.toByte()) }), sink.bytes())
            assertEquals(6L, writer.progress.completedJpegs)
            assertEquals(6L, writer.progress.completedJpegBytes)
        } finally {
            output.cancel(null)
            writer.close()
            store.dispose()
        }
    }

    // Cases: MJPEG-IMG-06, MJPEG-IMG-15, MJPEG-OBS-03, MJPEG-OBS-09
    @Test
    fun failureBeforeBodyInvocationReleasesInitialPinBeforeTheActualCallCompletes() = runBlocking {
        val store = JpegStore()
        val generation = Any()
        val id = StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))
        store.registerCapture(id, generation)
        var initialStorage: ByteArray? = null
        store.offerJpeg(generation, 8) { initialStorage = it; it.fill(1) }
        val reader = checkNotNull(store.openReader())
        val writer = JpegResponse(reader, MediaFormat.Jpeg)
        store.offerJpeg(generation, 1) { it[0] = 2 }
        val owner = UnstartedHttpListenerFixture()
        val requests = HttpRequests()
        val gate = Any()
        val returned = CompletableDeferred<Throwable>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val failure = IOException("Engine failed before body invocation")
        try {
            testApplication {
                application {
                    sendPipeline.intercept(ApplicationSendPipeline.After) {
                        if (subject is OutgoingContent.WriteChannelContent) throw failure
                    }
                    routing {
                        get("/jpeg") {
                            val job = checkNotNull(call.coroutineContext[Job])
                            synchronized(gate) {
                                requests.registerMedia(writer, owner.listener, Uuid.random(), "192.0.2.1", 8000, job, 0)
                            }
                            job.invokeOnCompletion {
                                synchronized(gate) { requests.retireMedia(writer, 10_000, setOf(owner.listener)) }
                                completed.complete(Unit)
                            }
                            try {
                                writer.respond(call)
                                throw AssertionError("Arranged send failure was not observed")
                            } catch (cause: Throwable) {
                                returned.complete(cause)
                            }
                            release.await()
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }
                coroutineScope {
                    val response = async { client.get("/jpeg") }
                    try {
                        assertSame(failure, withTimeout(5.seconds) { returned.await() })
                        assertFalse(completed.isCompleted)
                        assertNull(reader.acquireFrame())
                        store.offerJpeg(generation, 8) {
                            assertSame(initialStorage, it)
                            it.fill(3)
                        }
                        val active = synchronized(gate) { requests.sample(10_000, generation, setOf(owner.listener)) }
                        assertEquals(io.screenstream.mjpeg.http.HttpDelivery.ClientInfo.State.Active, active.clients.single().state)
                        assertEquals(0L, active.completedJpegs)
                    } finally {
                        release.complete(Unit)
                    }
                    response.await()
                    withTimeout(5.seconds) { completed.await() }
                    val retired = synchronized(gate) { requests.sample(12_000, generation, setOf(owner.listener)) }
                    assertEquals(io.screenstream.mjpeg.http.HttpDelivery.ClientInfo.State.Disconnected, retired.clients.single().state)
                    assertEquals(0L, retired.completedJpegBytes)
                }
            }
        } finally {
            release.complete(Unit)
            writer.close()
            store.dispose()
            owner.close()
        }
    }

    private fun withWriterOnTestScheduler(
        writer: JpegResponse,
        output: ByteWriteChannel,
        assertions: suspend CoroutineScope.(Deferred<Unit>, TestCoroutineScheduler) -> Unit,
    ): Unit = runBlocking {
        check(currentCoroutineContext()[TestCoroutineScheduler] == null)
        runTestApplication {
            val ready = CompletableDeferred<OutgoingContent.WriteChannelContent>()
            val written = CompletableDeferred<Unit>()
            application {
                sendPipeline.intercept(ApplicationSendPipeline.After) {
                    val content = subject
                    if (content is OutgoingContent.WriteChannelContent) {
                        ready.complete(content)
                        written.await()
                        proceedWith(object : OutgoingContent.NoContent() {
                            override val status = HttpStatusCode.NoContent
                        })
                    }
                }
                routing { get("/jpeg") { writer.respond(call) } }
            }
            coroutineScope {
                val response = async { client.get("/jpeg") }
                try {
                    val content = withTimeout(5.seconds) { ready.await() }
                    val scheduler = TestCoroutineScheduler()
                    val writing = async(StandardTestDispatcher(scheduler) + scheduler, start = CoroutineStart.UNDISPATCHED) {
                        check(currentCoroutineContext()[TestCoroutineScheduler] === scheduler)
                        content.writeTo(output)
                    }
                    try {
                        withTimeout(5.seconds) { assertions(writing, scheduler) }
                    } finally {
                        writer.close()
                        writing.cancel()
                        scheduler.runCurrent()
                        withContext(NonCancellable + Dispatchers.Default) {
                            withTimeout(5.seconds) { writing.join() }
                        }
                    }
                } finally {
                    written.complete(Unit)
                }
                response.await()
            }
        }
    }

    private fun multipartBytes(payloads: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write("--screenstream-jpeg\r\n".toByteArray(Charsets.US_ASCII))
        for (payload in payloads) {
            write("Content-Type: image/jpeg\r\nContent-Length: ${payload.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            write(payload)
            write("\r\n--screenstream-jpeg\r\n".toByteArray(Charsets.US_ASCII))
        }
    }.toByteArray()

    /** Replaces only the engine's output at the public send boundary; respond remains alive throughout. */
    private fun writeResponseAndCaptureFailure(writer: JpegResponse, output: ByteWriteChannel): Throwable? {
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
}
