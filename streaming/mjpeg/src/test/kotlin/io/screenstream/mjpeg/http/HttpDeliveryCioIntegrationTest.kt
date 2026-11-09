package io.screenstream.mjpeg.http

import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.server.routing.RoutingRoot
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.screenstream.core.module.StreamingModule
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.HttpDelivery.ServerFailure
import io.screenstream.mjpeg.http.HttpDelivery.ServerState
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic.ValueTimeMark
import kotlin.uuid.Uuid

/** Actual configured CIO sockets under simulated Android; no device/LAN enforcement claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpDeliveryCioIntegrationTest {
    // Cases: MJPEG-WEB-08, MJPEG-IMG-09, MJPEG-OBS-01, MJPEG-OBS-03, MJPEG-OBS-04
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun protectedViewingChecksCredentialsAndRetiresCompletedMediaCalls(): Unit = runBlocking {
        val calls = Channel<ObservedMediaCall>(Channel.UNLIMITED)
        val delivery = mediaObservingDelivery(calls)
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(11, 12, 13, 14)
        try {
            delivery.configureServers(listOf(httpTestAddress()), port)
            assertEquals(ServerState.Pending, delivery.info.value.servers.single().state)
            assertTrue(delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), logo))
            val listening = delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            val token = checkNotNull(delivery.appliedToken).value
            for (path in listOf("/jpeg", "/mjpeg")) {
                RawHttpConnection(port, path).use { assertEquals(403, it.status, path) }
            }
            delivery.updateConfiguration(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings())
            val logoResponse = requestHttpJpeg(port, "/jpeg?token=$token&retirement=logo")
            assertEquals(200, logoResponse.status)
            assertEquals("image/jpeg", logoResponse.headers["content-type"])
            assertEquals(logo.size.toString(), logoResponse.headers["content-length"])
            assertContentEquals(logo, logoResponse.bytes)
            val logoCall = withTimeout(5.seconds) { calls.receive() }
            assertEquals("logo", logoCall.marker)
            val attempt = attempt()
            delivery.registerCapture(attempt, attempt)
            delivery.updateCaptureStatus(attempt, Status.Active, attempt)
            val capture = byteArrayOf(21, 22, 23)
            delivery.offerJpeg(attempt, capture.size) { capture.copyInto(it) }
            val captureResponse = requestHttpJpeg(port, "/jpeg?token=$token&retirement=capture")
            assertEquals(200, captureResponse.status)
            assertContentEquals(capture, captureResponse.bytes)
            val captureCall = withTimeout(5.seconds) { calls.receive() }
            assertEquals("capture", captureCall.marker)
            withTimeout(5.seconds) {
                logoCall.job.join()
                captureCall.job.join()
            }

            fun assertOpenSample(sample: HttpDelivery.Info) {
                assertEquals(listOf(listening), sample.servers)
                assertNull(sample.fatalCause)
                assertEquals(2L, sample.completedJpegs)
                assertEquals(7L, sample.completedJpegBytes)
            }

            fun markedRow(sample: HttpDelivery.Info): HttpDelivery.ClientInfo? = sample.clients.singleOrNull {
                it.serverId == listening.id && it.ip == captureCall.remoteAddress && it.remotePort == captureCall.remotePort
            }

            ShadowSystemClock.advanceBy(1_999, TimeUnit.MILLISECONDS)
            val graceAt = SystemClock.elapsedRealtime()
            val grace = delivery.awaitHttpInfo { it.sampledAtElapsedRealtimeMillis >= graceAt }
            assertOpenSample(grace)
            val marked = checkNotNull(markedRow(grace))
            assertEquals(HttpDelivery.ClientInfo.State.Active, marked.state)
            assertTrue(grace.hasConsumer)
            for ((_, remoteAddress, remotePort) in listOf(logoCall, captureCall)) {
                assertTrue(grace.clients.any {
                    it.serverId == listening.id && it.ip == remoteAddress && it.remotePort == remotePort &&
                            it.state == HttpDelivery.ClientInfo.State.Active
                })
            }
            val observedRows = grace.clients.map { it.id }.toSet()
            var disconnected = grace
            withTimeout(10.seconds) {
                while (markedRow(disconnected)?.state != HttpDelivery.ClientInfo.State.Disconnected || disconnected.hasConsumer) {
                    ShadowSystemClock.advanceBy(2_000, TimeUnit.MILLISECONDS)
                    val sampledAfter = SystemClock.elapsedRealtime()
                    disconnected = delivery.awaitHttpInfo { it.sampledAtElapsedRealtimeMillis >= sampledAfter }
                    assertOpenSample(disconnected)
                    assertNotNull(markedRow(disconnected))
                }
            }
            assertEquals(marked.id, checkNotNull(markedRow(disconnected)).id)
            assertFalse(disconnected.hasConsumer)
            assertTrue(disconnected.clients.none { it.state == HttpDelivery.ClientInfo.State.Active })

            val expiryAt = maxOf(SystemClock.elapsedRealtime(), disconnected.sampledAtElapsedRealtimeMillis + 5_000L)
            ShadowSystemClock.advanceBy(expiryAt - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
            val expired = delivery.awaitHttpInfo { it.sampledAtElapsedRealtimeMillis >= expiryAt }
            assertOpenSample(expired)
            assertNull(markedRow(expired))
            assertTrue(expired.clients.none { it.id in observedRows })
            assertFalse(expired.hasConsumer)
            assertTrue(expired.clients.isEmpty())
            val healthy = requestHttpJpeg(port, "/jpeg?token=$token")
            assertEquals(200, healthy.status)
            assertContentEquals(capture, healthy.bytes)
            delivery.stopHttpTestDelivery()
            val completed = delivery.info.value
            assertEquals(3L, completed.completedJpegs)
            assertEquals(10L, completed.completedJpegBytes)
            assertTrue(completed.servers.isEmpty())
            assertTrue(completed.clients.isEmpty())
            assertPortCanBind(port)
        } finally {
            try {
                delivery.stopHttpTestDelivery()
            } finally {
                calls.cancel()
            }
        }
    }

    // Cases: MJPEG-IMG-10, MJPEG-OBS-03, MJPEG-OBS-04
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun peerResetRetiresItsMultipartCallWhileListenerKeepsServing() = runBlocking {
        val calls = Channel<ObservedMediaCall>(Channel.UNLIMITED)
        val delivery = mediaObservingDelivery(calls)
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(71, 72, 73)
        var connection: RawHttpConnection? = null
        try {
            delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), logo)
            delivery.configureServers(listOf(httpTestAddress()), port)
            val listening = delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            val peer = RawHttpConnection(port, "/mjpeg?retirement=peer-reset", closeConnection = false)
            connection = peer
            assertEquals(200, peer.status)
            assertContentEquals(logo, MultipartReader(peer.body).nextPart())
            val call = withTimeout(5.seconds) { calls.receive() }
            assertEquals("peer-reset", call.marker)
            assertTrue(call.job.isActive)
            peer.abort()
            // Server stop cannot manufacture this checkpoint: the exact peer call must settle
            // while its configured listener and a subsequent media request remain usable.
            withTimeout(5.seconds) { call.job.join() }
            assertEquals(listOf(listening), delivery.info.value.servers)
            assertNull(delivery.info.value.fatalCause)

            fun row(sample: HttpDelivery.Info): HttpDelivery.ClientInfo = sample.clients.single {
                it.serverId == listening.id && it.ip == call.remoteAddress && it.remotePort == call.remotePort
            }

            ShadowSystemClock.advanceBy(1_999, TimeUnit.MILLISECONDS)
            val graceAt = SystemClock.elapsedRealtime()
            val grace = delivery.awaitHttpInfo { it.sampledAtElapsedRealtimeMillis >= graceAt }
            assertEquals(HttpDelivery.ClientInfo.State.Active, row(grace).state)
            assertEquals(logo.size.toLong(), row(grace).completedJpegBytes)
            assertEquals(1L, grace.completedJpegs)
            assertEquals(logo.size.toLong(), grace.completedJpegBytes)
            assertTrue(grace.hasConsumer)
            ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS)
            val disconnectedAt = SystemClock.elapsedRealtime()
            val disconnected = delivery.awaitHttpInfo { it.sampledAtElapsedRealtimeMillis >= disconnectedAt }
            assertEquals(row(grace).id, row(disconnected).id)
            assertEquals(HttpDelivery.ClientInfo.State.Disconnected, row(disconnected).state)
            assertFalse(disconnected.hasConsumer)
            assertEquals(listOf(listening), disconnected.servers)
            val healthy = requestHttpJpeg(port)
            assertEquals(200, healthy.status)
            assertContentEquals(logo, healthy.bytes)
        } finally {
            connection?.close()
            try {
                delivery.stopHttpTestDelivery()
            } finally {
                calls.cancel()
            }
        }
    }

    private fun mediaObservingDelivery(calls: Channel<ObservedMediaCall>): KtorHttpDelivery =
        KtorHttpDelivery(httpTestContext()) { parentJob, host, listenerPort, configureApplication ->
            CioHttpListener(parentJob, host, listenerPort) { listener ->
                monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
                    call.request.queryParameters["retirement"]?.let { marker ->
                        calls.trySend(ObservedMediaCall(marker, call.request.local.remoteAddress, call.request.local.remotePort, call.coroutineContext.job))
                    }
                }
                configureApplication(this, listener)
            }
        }

    private data class ObservedMediaCall(val marker: String, val remoteAddress: String, val remotePort: Int, val job: Job)

    // Cases: MJPEG-IMG-10, MJPEG-SRV-03, MJPEG-SRV-10
    @Test
    fun multipartViewerSurvivesMetadataEditSelectsLatestPublicationAndStopsBeforeRebind() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(31, 32, 33)
        var connection: RawHttpConnection? = null
        try {
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), logo))
            delivery.configureServers(listOf(httpTestAddress()), port)
            val listening = delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            val live = RawHttpConnection(port, "/mjpeg")
            connection = live
            assertEquals(200, live.status)
            assertEquals("multipart/x-mixed-replace; boundary=screenstream-jpeg", live.headers["content-type"])
            val parts = MultipartReader(live.body)
            assertContentEquals(logo, parts.nextPart())
            delivery.configureServers(listOf(httpTestAddress(interfaceName = "updated metadata")), port)
            assertEquals(listening.id, delivery.info.value.servers.single().id)
            val attempt = attempt()
            assertTrue(delivery.registerCapture(attempt, attempt))
            delivery.updateCaptureStatus(attempt, Status.Active, attempt)
            val latest = byteArrayOf(41, 0, 43, 44, 45)
            delivery.offerJpeg(attempt, latest.size) { latest.copyInto(it) }
            // The first part is a completed publication checkpoint, not a timing delay.
            var next = parts.nextPart()
            var repeats = 0
            while (next.contentEquals(logo) && repeats++ < 8) next = parts.nextPart()
            assertContentEquals(latest, next)
            delivery.requestStop()
            live.awaitExternalEnd()
            delivery.stopHttpTestDelivery()
            assertTrue(delivery.info.value.completedJpegs >= 2L)
            assertTrue(delivery.info.value.completedJpegBytes >= logo.size + latest.size)
            assertTrue(delivery.info.value.clients.isEmpty())
            live.close()
            connection = null
            assertPortCanBind(port)
        } finally {
            connection?.close()
            delivery.stopHttpTestDelivery()
        }
    }

    // Cases: MJPEG-SRV-04, MJPEG-SRV-05
    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun occupiedBindExhaustsBudgetAndOnlyCurrentRetryRestoresListening() = runBlocking {
        val occupied = loopbackSocket(0)
        val port = occupied.localPort
        val delivery = KtorHttpDelivery(httpTestContext())
        try {
            delivery.configureServers(listOf(httpTestAddress()), port)
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1)))
            delivery.awaitHttpInfo { (it.servers.single().state as? ServerState.Failed)?.attempts == 1 }
            // IO delays do not advance Robolectric's elapsed realtime. Advance the documented
            // Android clock only after observing a failed attempt; public attempt state is the oracle.
            val failure = withTimeout(15.seconds) {
                while ((delivery.info.value.servers.single().state as? ServerState.Failed)?.attempts != 3) {
                    if (delivery.info.value.servers.single().state is ServerState.Failed) {
                        val before = SystemClock.elapsedRealtime()
                        ShadowSystemClock.advanceBy(250, TimeUnit.MILLISECONDS)
                        val expectedElapsedRealtimeMillis = before + 250
                        assertEquals(expected = expectedElapsedRealtimeMillis, actual = SystemClock.elapsedRealtime())
                    }
                    delay(25.milliseconds) // Yield to the actual IO timer/cleanup; elapsed waiting is not proof.
                }
                delivery.info.value.servers.single()
            }
            // Public startListening diagnosis on this CIO version observes an outer
            // JobCancellationException with BindException as cause; the contract maps
            // the caught outer exception, so this physical failure is Unknown.
            assertEquals(ServerState.Failed(ServerFailure.Unknown, 3), failure.state)
            delivery.retryServer(Uuid.random())
            assertEquals(failure, delivery.info.value.servers.single())
            occupied.close()
            delivery.retryServer(failure.id)
            // Failed is published before physical cleanup. A retry admitted during cleanup
            // can acquire its 250 ms deadline afterwards, so keep driving Android time
            // until actual Listening; real IO delays alone cannot advance this clock.
            val recovered = withTimeout(15.seconds) {
                while (delivery.info.value.servers.single().state != ServerState.Listening) {
                    ShadowSystemClock.advanceBy(25, TimeUnit.MILLISECONDS)
                    delay(25.milliseconds) // Yield to real CIO work; the state remains the oracle.
                }
                delivery.info.value.servers.single()
            }
            assertEquals(failure.id, recovered.id)
            RawHttpConnection(port, "/").use { assertEquals(200, it.status) }
            delivery.stopHttpTestDelivery()
            assertPortCanBind(port)
        } finally {
            occupied.close()
            delivery.stopHttpTestDelivery()
        }
    }

    // Cases: MJPEG-SRV-04
    @Test
    fun occupiedAddressDoesNotPreventAnotherAddressFromServingMedia(): Unit = runBlocking {
        // This host fixture uses configured IPv6 and IPv4 loopbacks on the same port.
        val occupiedHost = "::1"
        val occupied = loopbackSocket(0, host = occupiedHost)
        val port = occupied.localPort
        val delivery = KtorHttpDelivery(httpTestContext())
        val logo = byteArrayOf(51, 52, 53)
        try {
            delivery.configureServers(listOf(httpTestAddress(1, occupiedHost), httpTestAddress(2)), port)
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), logo))
            val ready = delivery.awaitHttpInfo {
                it.servers.first().state is ServerState.Failed && it.servers.last().state == ServerState.Listening
            }
            // This configured CIO wraps its bind error; host failure classification remains qualified.
            assertEquals(ServerFailure.Unknown, assertIs<ServerState.Failed>(ready.servers.first().state).reason)
            RawHttpConnection(port, "/jpeg").use {
                assertEquals(200, it.status)
                assertContentEquals(logo, it.readBody())
            }
            assertIs<ServerState.Failed>(delivery.info.value.servers.first().state)
            assertEquals(ServerState.Listening, delivery.info.value.servers.last().state)
            delivery.stopHttpTestDelivery()
            occupied.close()
            assertPortCanBind(port)
            loopbackSocket(port, host = occupiedHost).use { assertEquals(port, it.localPort) }
        } finally {
            occupied.close()
            delivery.stopHttpTestDelivery()
        }
    }

    // Cases: MJPEG-ACC-03, MJPEG-OBS-06, MJPEG-IMG-15
    @Test
    fun credentialRotationEndsExistingMultipartViewingAndPreservesCompletedPayloads() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(61, 62, 63, 64)
        var connection: RawHttpConnection? = null
        try {
            assertTrue(delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), logo))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val oldToken = checkNotNull(delivery.appliedToken).value
            val oldCookie = RawHttpConnection(port, "/?token=$oldToken").use {
                checkNotNull(it.headers["set-cookie"]).substringBefore(';')
            }
            val live = RawHttpConnection(port, "/mjpeg?token=$oldToken")
            connection = live
            assertEquals(200, live.status)
            assertContentEquals(logo, MultipartReader(live.body).nextPart())

            // Complete wire payload receipt is the checkpoint; no held acquired-write race is arranged.
            assertTrue(delivery.updateConfiguration(httpTestProtectedAccessSettings("654321"), PostStopImage.Placeholder, httpTestPageSettings()))
            val rotated = delivery.info.value
            assertTrue(rotated.clients.isEmpty())
            assertTrue(rotated.completedJpegs >= 1L)
            assertTrue(rotated.completedJpegBytes >= logo.size.toLong())
            live.awaitExternalEnd()
            RawHttpConnection(port, "/jpeg?token=$oldToken").use { assertEquals(403, it.status) }
            RawHttpConnection(port, "/jpeg", listOf("Cookie: $oldCookie")).use { assertEquals(403, it.status) }
            val currentToken = checkNotNull(delivery.appliedToken).value
            RawHttpConnection(port, "/jpeg?token=$currentToken").use {
                assertEquals(200, it.status)
                assertContentEquals(logo, it.readBody())
            }
            delivery.stopHttpTestDelivery()
            val completed = delivery.info.value
            assertTrue(completed.completedJpegs >= rotated.completedJpegs + 1)
            assertTrue(completed.completedJpegBytes >= rotated.completedJpegBytes + logo.size)
            assertTrue(completed.clients.isEmpty())
            assertTrue(completed.servers.isEmpty())
            live.close()
            connection = null
            assertPortCanBind(port)
        } finally {
            connection?.close()
            delivery.stopHttpTestDelivery()
        }
    }

    // Cases: MJPEG-WEB-09, MJPEG-WEB-10, MJPEG-WEB-12
    @Test
    fun webSocketRequiresOriginAndCookiePublishesFactsAndRejectsApplicationCommands() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        val sockets = mutableListOf<WebSocketObserver>()
        try {
            delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val token = checkNotNull(delivery.appliedToken).value
            val origin = "http://127.0.0.1:$port"
            val tokenOnly = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws?token=$token", "Origin" to origin)
            sockets += tokenOnly
            assertEquals("pin_required", tokenOnly.nextJson().getValue("access").jsonPrimitive.content)
            assertEquals(1000, tokenOnly.peerCloseCode.get(15, TimeUnit.SECONDS))
            val cookie = RawHttpConnection(port, "/?token=$token").use {
                assertEquals(303, it.status)
                checkNotNull(it.headers["set-cookie"]).substringBefore(';')
            }
            val noOrigin = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Cookie" to cookie, "Referer" to "$origin/page")
            sockets += noOrigin
            assertEquals(1008, noOrigin.peerCloseCode.get(15, TimeUnit.SECONDS))
            for (binary in listOf(false, true)) {
                val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to origin, "Cookie" to cookie)
                sockets += observer
                val snapshot = observer.nextJson()
                assertEquals(setOf("access", "notice", "hasImage", "imageKind", "display"), snapshot.keys)
                assertEquals("authorized", snapshot.getValue("access").jsonPrimitive.content)
                assertEquals("Viewing contract", snapshot.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
                if (binary) observer.send(Frame.Binary(true, byteArrayOf(1, 2)))
                else observer.send(Frame.Text("start"))
                assertEquals(1008, observer.peerCloseCode.get(15, TimeUnit.SECONDS))
            }
            delivery.stopHttpTestDelivery()
            assertTrue(delivery.info.value.clients.isEmpty())
        } finally {
            try {
                httpClient.closeSockets(sockets)
            } finally {
                delivery.stopHttpTestDelivery()
            }
        }
    }

    // Cases: MJPEG-WEB-11, MJPEG-ACC-03, MJPEG-WEB-12
    @Test
    fun authorizedSocketReceivesChangedFactsSurvivesPolicyEditAndRevokesOldCredentials() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        val sockets = mutableListOf<WebSocketObserver>()
        try {
            delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val token = checkNotNull(delivery.appliedToken).value
            val cookie = RawHttpConnection(port, "/?token=$token").use { checkNotNull(it.headers["set-cookie"]).substringBefore(';') }
            val current = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port", "Cookie" to cookie)
            sockets += current
            assertTrue(current.nextJson().getValue("hasImage").jsonPrimitive.boolean)
            RawHttpConnection(port, "/jpeg", listOf("Cookie: $cookie")).use {
                assertEquals(200, it.status)
                assertContentEquals(byteArrayOf(1), it.readBody())
            }
            // Independently constructed same-PIN settings preserve this exact authorized session.
            delivery.updateConfiguration(httpTestProtectedAccessSettings().copy(blockMinutes = 3), PostStopImage.Placeholder, httpTestPageSettings("Edited"))
            val edited = current.awaitJson { it.getValue("display").jsonObject.getValue("title").jsonPrimitive.content == "Edited" }
            assertEquals("Edited", edited.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
            assertTrue(edited.getValue("hasImage").jsonPrimitive.boolean)
            assertEquals(token, checkNotNull(delivery.appliedToken).value)
            assertFalse(current.peerCloseCode.isDone)
            val ping = byteArrayOf(7, 0, 9)
            current.send(Frame.Ping(ping))
            assertContentEquals(ping, current.pong.get(15, TimeUnit.SECONDS))
            val attempt = attempt()
            delivery.registerCapture(attempt, attempt)
            delivery.updateCaptureStatus(attempt, Status.Suspended, null)
            val suspended = current.awaitJson { it.getValue("notice").jsonPrimitive.content == "paused" }
            assertTrue(suspended.getValue("hasImage").jsonPrimitive.boolean)
            val recoveredGeneration = Any()
            delivery.updateCaptureStatus(attempt, Status.Active, recoveredGeneration)
            delivery.offerJpeg(recoveredGeneration, 2) { it[0] = 11; it[1] = 12 }
            current.awaitJson {
                it.getValue("notice") == JsonNull && it.getValue("imageKind").jsonPrimitive.content == "live" &&
                        it.getValue("hasImage").jsonPrimitive.boolean
            }
            delivery.updateConfiguration(httpTestProtectedAccessSettings("654321"), PostStopImage.Placeholder, httpTestPageSettings("New generation"))
            current.awaitTermination()
            RawHttpConnection(port, "/jpeg?token=$token").use { assertEquals(403, it.status) }
            RawHttpConnection(port, "/jpeg", listOf("Cookie: $cookie")).use { assertEquals(403, it.status) }
            val newToken = checkNotNull(delivery.appliedToken).value
            val newCookie = RawHttpConnection(port, "/?token=$newToken").use { checkNotNull(it.headers["set-cookie"]).substringBefore(';') }
            val successor = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port", "Cookie" to newCookie)
            sockets += successor
            assertEquals("New generation", successor.nextJson().getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
            successor.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))
            // Peer Close or transport EOF witnesses termination; no Close code is promised here.
            // Stop/rebind below proves eventual server cleanup.
            successor.awaitTermination()
            delivery.stopHttpTestDelivery()
            assertTrue(delivery.info.value.clients.isEmpty())
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(sockets)
            } finally {
                delivery.stopHttpTestDelivery()
            }
        }
    }

    // Cases: MJPEG-WEB-13
    @Test
    fun openSocketHonorsEncodedSnapshotLimitIncludingUnicodeAndEscaping() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        var socket: WebSocketObserver? = null
        try {
            delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port")
            socket = observer
            val template = observer.nextJson()
            assertEquals("open", template.getValue("access").jsonPrimitive.content)
            val permittedSnapshots = mutableSetOf(template)
            fun snapshotWithTitle(title: String): String {
                val display = JsonObject(template.getValue("display").jsonObject + ("title" to JsonPrimitive(title)))
                return JsonObject(template + ("display" to display)).toString()
            }

            val seed = "雪\"\\\n".repeat(100)
            val seedSize = snapshotWithTitle(seed).toByteArray(Charsets.UTF_8).size
            for (size in listOf(256 * 1024 - 1, 256 * 1024)) {
                val title = seed + "x".repeat(size - seedSize)
                assertEquals(size, snapshotWithTitle(title).toByteArray(Charsets.UTF_8).size)
                delivery.updateConfiguration(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(title))
                val message = observer.awaitText {
                    it.getValue("display").jsonObject.getValue("title").jsonPrimitive.content == title
                }
                assertEquals(size, message.toByteArray(Charsets.UTF_8).size)
                val snapshot = Json.parseToJsonElement(message).jsonObject
                assertEquals(title, snapshot.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
                permittedSnapshots += snapshot
            }
            val oversized = seed + "x".repeat(256 * 1024 + 1 - seedSize)
            assertEquals(256 * 1024 + 1, snapshotWithTitle(oversized).toByteArray(Charsets.UTF_8).size)
            delivery.updateConfiguration(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(oversized))
            assertEquals(1011, observer.peerCloseCode.get(15, TimeUnit.SECONDS))
            for (message in observer.drainMessages()) {
                assertTrue(message.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
                assertTrue(Json.parseToJsonElement(message).jsonObject in permittedSnapshots)
            }
            delivery.stopHttpTestDelivery()
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(listOfNotNull(socket))
            } finally {
                delivery.stopHttpTestDelivery()
            }
        }
    }

    // Cases: MJPEG-WEB-09
    @Test
    fun blockedSocketPublishesRemainingPinBlockAndClosesNormally() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        var socket: WebSocketObserver? = null
        try {
            delivery.start(httpTestProtectedAccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val block = "pin=000000"
            repeat(5) { index ->
                RawHttpConnection(port, "/pin", listOf("Origin: http://127.0.0.1:$port", "Content-Type: application/x-www-form-urlencoded"), block).use {
                    assertEquals(if (index == 4) 429 else 403, it.status)
                }
            }
            val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port")
            socket = observer
            val denied = observer.nextJson()
            assertEquals(setOf("access", "retryAfterMs"), denied.keys)
            assertEquals("blocked", denied.getValue("access").jsonPrimitive.content)
            assertEquals(60_000L, denied.getValue("retryAfterMs").jsonPrimitive.content.toLong())
            assertEquals(1000, observer.peerCloseCode.get(15, TimeUnit.SECONDS))
            delivery.stopHttpTestDelivery()
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(listOfNotNull(socket))
            } finally {
                delivery.stopHttpTestDelivery()
            }
        }
    }

    // Cases: MJPEG-WEB-01, MJPEG-WEB-02, MJPEG-WEB-14, MJPEG-WEB-17
    @Test
    fun actualCioAuthorityMethodsAndRangeKeepTheDirectWireContract() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val image = byteArrayOf(1, 0, 2, 3, 4)
        try {
            delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPageSettings(), image)
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            for (host in listOf(null, "127.0.0.1:$port/path", "user@127.0.0.1:$port", "127.0.0.1:wrong")) {
                RawHttpConnection(port, "/unknown?token=", hostHeader = host).use {
                    assertEquals(400, it.status, "Host=$host")
                }
            }
            RawHttpConnection(port, "/", listOf("Host: 127.0.0.1:$port")).use { assertEquals(400, it.status) }
            RawHttpConnection(
                port,
                "/jpeg",
                listOf("Forwarded: host=127.0.0.1:$port", "X-Forwarded-Host: 127.0.0.1:$port"),
                hostHeader = "foreign.example:$port"
            ).use {
                assertEquals(403, it.status)
            }
            for ((path, allow) in mapOf("/" to "GET", "/pin" to "POST", "/jpeg" to "GET, OPTIONS", "/mjpeg" to "GET, OPTIONS", "/ws" to "GET")) {
                RawHttpConnection(port, path, method = "HEAD").use {
                    assertEquals(405, it.status, path)
                    assertEquals(allow, it.headers["allow"])
                }
            }
            RawHttpConnection(port, "/jpeg?ignored=start", listOf("Range: bytes=1-2", "Accept-Encoding: gzip", "X-Forwarded-For: 198.51.100.7")).use {
                assertEquals(200, it.status)
                assertEquals(null, it.headers["content-range"])
                assertEquals(null, it.headers["content-encoding"])
                assertContentEquals(image, it.readBody())
            }
            RawHttpConnection(port, "/mjpeg", listOf("Range: bytes=1-2", "Accept-Encoding: gzip")).use {
                assertEquals(200, it.status)
                assertEquals(null, it.headers["content-range"])
                assertEquals(null, it.headers["content-encoding"])
                assertContentEquals(image, MultipartReader(it.body).nextPart())
            }
            delivery.stopHttpTestDelivery()
            assertPortCanBind(port)
        } finally {
            delivery.stopHttpTestDelivery()
        }
    }

    // Cases: MJPEG-SRV-10
    @Test
    fun constructedButNeverStartedCioListenerRetiresAndLeavesItsSocketAvailable() = runBlocking {
        val port = unusedLoopbackPort()
        val parent = Job()
        val listener = CioHttpListener(parent, "127.0.0.1", port) { }
        try {
            withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
            assertPortCanBind(port)
        } finally {
            listener.requestStop()
            parent.cancel()
            withTimeout(15.seconds) { parent.join() }
        }
    }

    private fun attempt(): StreamingModule.CaptureAttemptId =
        StreamingModule.CaptureAttemptId(StreamingModule.InstanceId(StreamingModule.Id("MJPEG")))

    private fun loopbackSocket(port: Int, host: String = "127.0.0.1"): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(java.net.InetSocketAddress(InetAddress.getByName(host), port))
    }

    private fun unusedLoopbackPort(): Int = loopbackSocket(0).use { it.localPort }

    private fun assertPortCanBind(port: Int) {
        loopbackSocket(port).use { assertEquals(port, it.localPort) }
    }

    private fun webSocketClient(): HttpClient = HttpClient(CIO) {
        // The client default exceeds the server's 256 KiB snapshot limit.
        install(WebSockets)
    }

    private suspend fun HttpClient.observeWebSocket(urlString: String, vararg extraHeaders: Pair<String, String>): WebSocketObserver {
        val session = withTimeout(15.seconds) {
            webSocketRawSession {
                url(urlString)
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
        }
        return WebSocketObserver(this, session)
    }

    private suspend fun HttpClient.closeSockets(sockets: List<WebSocketObserver>) {
        val clientJob = coroutineContext.job
        withContext(NonCancellable) {
            try {
                sockets.forEach(WebSocketObserver::cancel)
                withTimeout(15.seconds) { sockets.forEach { it.join() } }
            } finally {
                close()
                clientJob.cancel()
                withTimeout(15.seconds) {
                    clientJob.join()
                    engine.coroutineContext.job.join()
                }
            }
        }
    }

    private class WebSocketObserver(client: HttpClient, private val session: ClientWebSocketSession) {
        private val messages = LinkedBlockingQueue<String>()
        val peerCloseCode = CompletableFuture<Int>()
        val pong = CompletableFuture<ByteArray>()
        private val receiver = client.launch(Dispatchers.IO) {
            val partial = ByteArrayOutputStream()
            try {
                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            // UTF-8 characters may span fragments; decode the complete message only.
                            partial.write(frame.data)
                            if (frame.fin) {
                                messages.add(partial.toByteArray().decodeToString(throwOnInvalidSequence = true))
                                partial.reset()
                            }
                        }

                        is Frame.Ping -> session.send(Frame.Pong(frame.data))
                        is Frame.Pong -> pong.complete(frame.data.copyOf())
                        is Frame.Close -> {
                            val reason = checkNotNull(frame.readReason()) { "Peer Close omitted its status code" }
                            // Raw sessions need a Close reply. Only this received frame proves peer closure.
                            peerCloseCode.complete(reason.code.toInt())
                            session.close(reason)
                            return@launch
                        }

                        is Frame.Binary -> Unit
                    }
                }
                currentCoroutineContext().ensureActive()
                peerCloseCode.completeExceptionally(EOFException("WebSocket ended without a Close frame"))
            } catch (cause: Throwable) {
                // Canceling our receiver during cleanup cannot manufacture a peer termination.
                if (currentCoroutineContext().isActive) peerCloseCode.completeExceptionally(cause)
                else throw cause
            }
        }

        suspend fun send(frame: Frame) = withTimeout(15.seconds) {
            session.send(frame)
            session.flush()
        }

        fun awaitTermination() {
            try {
                peerCloseCode.get(15, TimeUnit.SECONDS)
            } catch (cause: ExecutionException) {
                // Credential revocation may end the transport without a promised Close code.
                if (cause.cause !is IOException && cause.cause !is CancellationException) throw cause
            } catch (_: CancellationException) {
                // CompletableFuture exposes a recorded peer/channel cancellation directly.
                // Cleanup never cancels or completes this future.
            }
        }

        fun cancel() {
            receiver.cancel()
            session.cancel()
        }

        suspend fun join() {
            receiver.join()
            session.coroutineContext.job.join()
        }

        fun nextText(): String = checkNotNull(messages.poll(15, TimeUnit.SECONDS)) { "No WebSocket snapshot" }

        fun nextJson(): JsonObject = Json.parseToJsonElement(nextText()).jsonObject

        fun drainMessages(): List<String> {
            val queued = mutableListOf<String>()
            messages.drainTo(queued)
            return queued
        }

        fun awaitText(predicate: (JsonObject) -> Boolean): String {
            val deadline = TimeSource.Monotonic.markNow() + 15.seconds
            while (true) {
                val remaining = -deadline.elapsedNow()
                if (remaining <= Duration.ZERO) throw AssertionError("Expected WebSocket facts were not observed")
                val message = messages.poll(remaining.inWholeNanoseconds, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Expected WebSocket facts were not observed")
                val matches = predicate(Json.parseToJsonElement(message).jsonObject)
                if (deadline.hasPassedNow()) throw AssertionError("Expected WebSocket facts were not observed before the deadline")
                if (matches) return message
            }
        }

        fun awaitJson(predicate: (JsonObject) -> Boolean): JsonObject = Json.parseToJsonElement(awaitText(predicate)).jsonObject
    }

    /** Independent HTTP/1.1 reader; no Ktor serialization or response parser is reused. */
    private class RawHttpConnection(
        port: Int,
        path: String,
        extraHeaders: List<String> = emptyList(),
        requestBody: String? = null,
        method: String? = null,
        hostHeader: String? = "127.0.0.1:$port",
        closeConnection: Boolean = true,
    ) : AutoCloseable {
        private val socket = Socket()
        private var readDeadline: ValueTimeMark? = null
        val status: Int
        val headers: HttpResponseHeaders
        val body: InputStream

        init {
            try {
                socket.soTimeout = 15_000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 15_000)
                val wireInput = socket.getInputStream().buffered()
                val input = object : InputStream() {
                    override fun read(): Int {
                        boundReadToDeadline()
                        return try {
                            wireInput.read()
                        } finally {
                            checkReadCompletionDeadline()
                        }
                    }

                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        boundReadToDeadline()
                        return try {
                            wireInput.read(bytes, offset, length)
                        } finally {
                            checkReadCompletionDeadline()
                        }
                    }
                }
                socket.getOutputStream().apply {
                    val requestMethod = method ?: if (requestBody == null) "GET" else "POST"
                    val bodyBytes = requestBody?.toByteArray(Charsets.UTF_8)
                    val request = buildString {
                        append("$requestMethod $path HTTP/1.1\r\n")
                        hostHeader?.let { append("Host: $it\r\n") }
                        if (closeConnection) append("Connection: close\r\n")
                        extraHeaders.forEach { append(it).append("\r\n") }
                        bodyBytes?.let { append("Content-Length: ${it.size}\r\n") }
                        append("\r\n")
                    }
                    write(request.toByteArray(Charsets.US_ASCII))
                    bodyBytes?.let { write(it) }
                    flush()
                }
                status = readCrlfLine(input).split(' ')[1].toInt()
                headers = readHeaderLines(input)
                body = if (headers["transfer-encoding"]?.lowercase() == "chunked") ChunkedBody(input) else input
            } catch (cause: Throwable) {
                socket.close()
                throw cause
            }
        }

        fun readBody(): ByteArray = headers["content-length"]?.toInt()?.let { readExact(body, it) } ?: body.readBytes()

        private fun boundReadToDeadline() {
            val remaining = readDeadline?.let { -it.elapsedNow() }
            if (remaining != null && remaining <= Duration.ZERO) throw java.net.SocketTimeoutException("Overall HTTP drain deadline expired")
            socket.soTimeout = minOf(15.seconds, remaining ?: 15.seconds).inWholeMilliseconds.coerceAtLeast(1).toInt()
        }

        private fun checkReadCompletionDeadline() {
            if (readDeadline?.hasPassedNow() == true) throw java.net.SocketTimeoutException("Overall HTTP drain deadline expired")
        }

        fun awaitExternalEnd() {
            readDeadline = TimeSource.Monotonic.markNow() + 15.seconds
            try {
                while (body.read() >= 0) {
                    // Drain any completed transport tail until EOF.
                }
            } catch (cause: IOException) {
                if (cause is java.net.SocketTimeoutException) throw cause
                // Connection reset/truncated chunking independently witnesses transport termination.
            } finally {
                readDeadline = null
            }
        }

        fun abort() {
            socket.setSoLinger(true, 0)
            socket.close()
        }

        override fun close() {
            socket.close()
        }
    }

    private class MultipartReader(private val input: InputStream) {
        private var first = true

        fun nextPart(): ByteArray {
            if (first) {
                assertEquals("--screenstream-jpeg", readCrlfLine(input))
                first = false
            }
            val headers = readHeaderLines(input)
            assertEquals(setOf("content-type", "content-length"), headers.keys)
            assertEquals("image/jpeg", headers["content-type"])
            val payload = readExact(input, checkNotNull(headers["content-length"]).toInt())
            assertEquals("", readCrlfLine(input))
            assertEquals("--screenstream-jpeg", readCrlfLine(input))
            return payload
        }
    }

    private class ChunkedBody(private val input: InputStream) : InputStream() {
        private var remaining = 0
        private var needFooter = false
        private var ended = false

        override fun read(): Int {
            if (ended) return -1
            if (remaining == 0) {
                if (needFooter) check(readCrlfLine(input).isEmpty()) { "Invalid HTTP chunk footer" }
                remaining = readCrlfLine(input).substringBefore(';').toInt(16)
                if (remaining == 0) {
                    readHeaderLines(input)
                    ended = true
                    return -1
                }
                needFooter = true
            }
            val value = input.read()
            if (value < 0) throw EOFException("Truncated HTTP chunk")
            remaining--
            return value
        }
    }

    private companion object {
        fun readExact(input: InputStream, size: Int): ByteArray {
            require(size >= 0)
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(result, offset, size - offset)
                if (count < 0) throw EOFException("Payload ended at $offset of $size bytes")
                offset += count
            }
            return result
        }

        fun readCrlfLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value < 0) throw EOFException("Incomplete CRLF line")
                if (value == 13) {
                    check(input.read() == 10) { "Missing line feed" }
                    return bytes.toString(Charsets.US_ASCII)
                }
                check(bytes.size() < 16_384) { "Unexpectedly long protocol line" }
                bytes.write(value)
            }
        }

        fun readHeaderLines(input: InputStream): HttpResponseHeaders = readHttpResponseHeaders { readCrlfLine(input) }
    }
}
