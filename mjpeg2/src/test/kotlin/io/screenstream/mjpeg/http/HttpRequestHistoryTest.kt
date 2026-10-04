package io.screenstream.mjpeg.http

import io.screenstream.mjpeg.http.HttpDelivery.ClientInfo
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.media.JpegResponse
import io.screenstream.mjpeg.http.media.JpegStore
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpRequestHistoryTest {
    // Cases: MJPEG-OBS-02, MJPEG-OBS-04
    @Test
    fun overlappingMediaSharesEndpointAndOnlyFinalRetirementStartsAbsoluteGrace() = runBlocking {
        val fixture = RequestHistoryFixture()
        try {
            val first = fixture.registerMedia()
            val second = fixture.registerMedia(format = MediaFormat.Mjpeg)
            val initial = fixture.sample(0).clients.single()
            assertEquals(MediaFormat.Mjpeg, initial.format)
            fixture.requests.registerWeb(fixture.oldListener.listener, fixture.webJob)
            fixture.requests.retireMedia(first.writer, 100, setOf(fixture.oldListener.listener))
            assertEquals(ClientInfo.State.Active, fixture.sample(20_000).clients.single().state)
            fixture.requests.retireMedia(second.writer, 20_000, setOf(fixture.oldListener.listener))
            assertNull(second.reader.acquireFrame())
            assertEquals(ClientInfo.State.Active, fixture.sample(21_999).clients.single().state)
            assertEquals(ClientInfo.State.Disconnected, fixture.sample(22_000).clients.single().state)
            assertEquals(initial.id, fixture.sample(26_999).clients.single().id)
            assertTrue(fixture.sample(27_000).clients.isEmpty())
            val third = fixture.registerMedia(nowMillis = 27_000)
            val newId = fixture.sample(27_000).clients.single().id
            assertNotEquals(initial.id, newId)
            fixture.requests.retireMedia(third.writer, 27_100, setOf(fixture.oldListener.listener))
            fixture.registerMedia(nowMillis = 28_000)
            assertEquals(ClientInfo.State.Active, fixture.sample(28_000).clients.single().state)
            assertEquals(1, fixture.sample(28_000).clients.size)
            assertEquals(newId, fixture.sample(28_000).clients.single().id)
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-OBS-04
    @Test
    fun registrationExpiresDisconnectedHistoryWithoutAnInterveningSample() = runBlocking {
        for (reconnectTime in listOf(7_099L, 7_100L, 7_101L)) {
            val fixture = RequestHistoryFixture()
            try {
                val original = fixture.registerMedia()
                val originalId = fixture.sample(0).clients.single().id
                fixture.requests.retireMedia(original.writer, 100, setOf(fixture.oldListener.listener))

                // Final retirement at 100 gives two seconds of grace and five of retention.
                // Admission must apply the deadline even when no scheduled sample removed the row.
                fixture.registerMedia(nowMillis = reconnectTime)
                val reconnected = fixture.sample(reconnectTime).clients.single()
                assertEquals(ClientInfo.State.Active, reconnected.state)
                if (reconnectTime < 7_100L) {
                    assertEquals(originalId, reconnected.id)
                } else {
                    assertNotEquals(originalId, reconnected.id)
                }
            } finally {
                fixture.close()
            }
        }
    }

    // Cases: MJPEG-OBS-02, MJPEG-OBS-05
    @Test
    fun distinctRemotePortAndIncarnationStaySeparateAndObsoleteListenerCannotExtendCutoff() = runBlocking {
        val fixture = RequestHistoryFixture()
        try {
            val late = fixture.registerMedia()
            fixture.registerMedia(port = 6001)
            fixture.registerMedia(serverId = Uuid.random())
            fixture.registerMedia(ip = "192.0.2.2")
            assertEquals(4, fixture.sample(0).clients.size)
            val cutoffJobs = fixture.requests.cutoff(fixture.oldListener.listener, fixture.serverId, 100)
            assertTrue(late.job in cutoffJobs)
            assertNull(late.reader.acquireFrame())
            val row = fixture.sample(
                100,
                setOf(fixture.currentListener.listener)
            ).clients.first { it.remotePort == 6000 && it.ip == "192.0.2.1" && it.serverId == fixture.serverId }
            assertEquals(ClientInfo.State.Disconnected, row.state)
            fixture.requests.retireMedia(late.writer, 4_000, setOf(fixture.currentListener.listener))
            assertEquals(ClientInfo.State.Disconnected, fixture.sample(4_999, setOf(fixture.currentListener.listener)).clients.first { it.id == row.id }.state)
            fixture.sample(5_100, setOf(fixture.currentListener.listener))
            assertTrue(fixture.sample(5_100).clients.none { it.serverId == fixture.serverId })
            fixture.registerMedia(listener = fixture.currentListener.listener, nowMillis = 5_100)
            assertNotEquals(
                row.id,
                fixture.sample(
                    5_100,
                    setOf(fixture.currentListener.listener)
                ).clients.first { it.serverId == fixture.serverId && it.remotePort == 6000 && it.ip == "192.0.2.1" }.id
            )
        } finally {
            fixture.close()
        }
    }

    // Cases: MJPEG-OBS-05, MJPEG-SRV-08
    @Test
    fun detachedHistoryDoesNotResurrectAndCutoffReturnsOnlyExactListenerJobs() = runBlocking {
        val fixture = RequestHistoryFixture()
        try {
            val old = fixture.registerMedia()
            val current = fixture.registerMedia(listener = fixture.currentListener.listener, port = 6001)
            fixture.requests.registerWeb(fixture.oldListener.listener, fixture.webJob)
            val otherWeb = Job()
            fixture.jobs += otherWeb
            fixture.requests.registerWeb(fixture.currentListener.listener, otherWeb)
            assertEquals(setOf(old.job, fixture.webJob), fixture.requests.cutoff(fixture.oldListener.listener, fixture.serverId, 100).toSet())
            assertEquals(setOf(fixture.webJob, otherWeb), fixture.requests.clearEndpointsAndGetWebJobs().toSet())
            fixture.requests.closeMediaReaders()
            assertNull(current.reader.acquireFrame())
            fixture.requests.retireMedia(old.writer, 200, setOf(fixture.currentListener.listener))
            assertTrue(fixture.sample(200).clients.isEmpty())
            assertEquals(setOf(current.job, fixture.webJob, otherWeb), fixture.requests.closeAll().toSet())
            fixture.requests.retireMedia(current.writer, 300, setOf(fixture.currentListener.listener))
            fixture.requests.retireMedia(current.writer, 400, setOf(fixture.currentListener.listener))
            assertTrue(fixture.sample(400).clients.isEmpty())
            fixture.requests.removeWeb(fixture.webJob)
            fixture.requests.removeWeb(otherWeb)
            assertTrue(fixture.requests.closeAll().isEmpty())
        } finally {
            fixture.close()
        }
    }

    private class RequestHistoryFixture {
        val requests = HttpRequests()
        val oldListener = UnstartedHttpListener()
        val currentListener = UnstartedHttpListener()
        val serverId: Uuid = Uuid.random()
        val webJob: Job = Job()
        val jobs = mutableListOf(webJob)
        private val stores = mutableListOf<JpegStore>()
        private val writers = mutableListOf<JpegResponse>()

        fun registerMedia(
            listener: HttpListener = oldListener.listener,
            serverId: Uuid = this.serverId,
            port: Int = 6000,
            ip: String = "192.0.2.1",
            format: MediaFormat = MediaFormat.Jpeg,
            nowMillis: Long = 0,
        ): Media {
            val store = JpegStore().apply {
                installLogo(byteArrayOf(1, 2, 3))
                updateStoppedImagePolicy(PostStopImage.Placeholder)
            }
            stores += store
            val reader = checkNotNull(store.openReader())
            val writer = JpegResponse(reader, format)
            writers += writer
            val job = Job().also(jobs::add)
            requests.registerMedia(writer, listener, serverId, ip, port, format, job, nowMillis)
            return Media(writer, reader, job)
        }

        fun sample(nowMillis: Long, listeners: Set<HttpListener> = setOf(oldListener.listener)): HttpRequests.Sample =
            requests.sample(nowMillis, null, listeners)

        suspend fun close() {
            writers.forEach(JpegResponse::close)
            stores.forEach(JpegStore::dispose)
            jobs.forEach(Job::cancel)
            jobs.joinAll()
            try {
                oldListener.close()
            } finally {
                currentListener.close()
            }
        }
    }

    private data class Media(val writer: JpegResponse, val reader: JpegStore.Reader, val job: Job)
}
