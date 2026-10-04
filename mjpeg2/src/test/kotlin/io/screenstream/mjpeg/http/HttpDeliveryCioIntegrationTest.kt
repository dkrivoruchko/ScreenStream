package io.screenstream.mjpeg.http

import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.cio.webSocketRawSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.screenstream.mjpeg.MjpegCaptureSession.State.Status
import io.screenstream.mjpeg.http.HttpDelivery.ServerFailure
import io.screenstream.mjpeg.http.HttpDelivery.ServerState
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.streaming.module.StreamingModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Actual configured CIO sockets under simulated Android; no device/LAN enforcement claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class HttpDeliveryCioIntegrationTest {
    // Cases: MJPEG-WEB-08, MJPEG-IMG-09, MJPEG-OBS-03
    @Test
    fun protectedViewingChecksCredentialsBeforeImageAvailabilityAndRetiresRealPayloads() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(11, 12, 13, 14)
        try {
            delivery.configureServers(listOf(httpTestAddress()), port)
            assertEquals(ServerState.Pending, delivery.info.value.servers.single().state)
            assertTrue(delivery.start(httpTestProtection(), PostStopImage.None, httpTestPage(), logo))
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val token = checkNotNull(delivery.appliedAccessToken).value
            for (path in listOf("/jpeg", "/mjpeg")) {
                RawHttp(port, path).use { assertEquals(path, 403, it.status) }
                RawHttp(port, "$path?accessToken=$token").use {
                    assertEquals(path, 503, it.status)
                    assertEquals(path, "1", it.headers["retry-after"])
                }
            }
            delivery.updateConfiguration(httpTestProtection(), PostStopImage.Placeholder, httpTestPage())
            RawHttp(port, "/jpeg?accessToken=$token").use {
                assertEquals(200, it.status)
                assertEquals("image/jpeg", it.headers["content-type"])
                assertEquals(logo.size.toString(), it.headers["content-length"])
                assertArrayEquals(logo, it.readBody())
            }
            val attempt = attempt()
            delivery.registerCapture(attempt)
            delivery.updateCaptureStatus(attempt, Status.Active)
            val capture = byteArrayOf(21, 22, 23)
            delivery.offerJpeg(attempt, capture.size) { capture.copyInto(it) }
            RawHttp(port, "/jpeg?accessToken=$token").use { assertArrayEquals(capture, it.readBody()) }
            delivery.finishHttpTest()
            val completed = delivery.info.value
            assertEquals(2L, completed.completedJpegs)
            assertEquals((logo.size + capture.size).toLong(), completed.completedJpegBytes)
            assertTrue(completed.servers.isEmpty())
            assertTrue(completed.clients.isEmpty())
            assertPortCanBind(port)
        } finally {
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-IMG-10, MJPEG-SRV-03, MJPEG-SRV-10
    @Test
    fun multipartViewerSurvivesMetadataEditSelectsLatestPublicationAndStopsBeforeRebind() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(31, 32, 33)
        var connection: RawHttp? = null
        try {
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), logo))
            delivery.configureServers(listOf(httpTestAddress()), port)
            val listening = delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            val live = RawHttp(port, "/mjpeg")
            connection = live
            assertEquals(200, live.status)
            assertEquals("multipart/x-mixed-replace; boundary=screenstream-jpeg", live.headers["content-type"])
            val parts = MultipartReader(live.body)
            assertArrayEquals(logo, parts.nextPart())
            delivery.configureServers(listOf(httpTestAddress(name = "updated metadata")), port)
            assertEquals(listening.id, delivery.info.value.servers.single().id)
            val attempt = attempt()
            assertTrue(delivery.registerCapture(attempt))
            delivery.updateCaptureStatus(attempt, Status.Active)
            val latest = byteArrayOf(41, 0, 43, 44, 45)
            delivery.offerJpeg(attempt, latest.size) { latest.copyInto(it) }
            // The first part is a completed publication checkpoint, not a timing delay.
            var next = parts.nextPart()
            var repeats = 0
            while (next.contentEquals(logo) && repeats++ < 8) next = parts.nextPart()
            assertArrayEquals(latest, next)
            delivery.requestStop()
            live.awaitExternalEnd()
            delivery.finishHttpTest()
            assertTrue(delivery.info.value.completedJpegs >= 2L)
            assertTrue(delivery.info.value.completedJpegBytes >= logo.size + latest.size)
            assertTrue(delivery.info.value.clients.isEmpty())
            live.close()
            connection = null
            assertPortCanBind(port)
        } finally {
            connection?.close()
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-SRV-04, MJPEG-SRV-05
    @Test
    fun occupiedBindExhaustsBudgetAndOnlyCurrentRetryRestoresListening() = runBlocking {
        val occupied = loopbackSocket(0)
        val port = occupied.localPort
        val delivery = KtorHttpDelivery(httpTestContext())
        try {
            delivery.configureServers(listOf(httpTestAddress()), port)
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1)))
            delivery.awaitHttpInfo { (it.servers.single().state as? ServerState.Failed)?.attempts == 1 }
            // IO delays do not advance Robolectric's elapsed realtime. Advance the documented
            // Android clock only after observing a failed attempt; public attempt state is the oracle.
            val failure = withTimeout(15.seconds) {
                while ((delivery.info.value.servers.single().state as? ServerState.Failed)?.attempts != 3) {
                    if (delivery.info.value.servers.single().state is ServerState.Failed) {
                        val before = SystemClock.elapsedRealtime()
                        ShadowSystemClock.advanceBy(250, TimeUnit.MILLISECONDS)
                        val expectedElapsedRealtimeMillis = before + 250
                        assertEquals(expectedElapsedRealtimeMillis, SystemClock.elapsedRealtime())
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
            val recovered = delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }.servers.single()
            assertEquals(failure.id, recovered.id)
            RawHttp(port, "/").use { assertEquals(200, it.status) }
            delivery.finishHttpTest()
            assertPortCanBind(port)
        } finally {
            occupied.close()
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-SRV-04
    @Test
    fun occupiedAddressDoesNotPreventAnotherAddressFromServingMedia() = runBlocking {
        // This host fixture uses configured IPv6 and IPv4 loopbacks on the same port.
        val occupiedHost = "::1"
        val occupied = loopbackSocket(0, host = occupiedHost)
        val port = occupied.localPort
        val delivery = KtorHttpDelivery(httpTestContext())
        val logo = byteArrayOf(51, 52, 53)
        try {
            delivery.configureServers(listOf(httpTestAddress(1, occupiedHost), httpTestAddress(2)), port)
            assertTrue(delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), logo))
            val ready = delivery.awaitHttpInfo {
                it.servers.first().state is ServerState.Failed && it.servers.last().state == ServerState.Listening
            }
            // This configured CIO wraps its bind error; host failure classification remains qualified.
            assertEquals(ServerFailure.Unknown, (ready.servers.first().state as ServerState.Failed).reason)
            RawHttp(port, "/jpeg").use {
                assertEquals(200, it.status)
                assertArrayEquals(logo, it.readBody())
            }
            assertTrue(delivery.info.value.servers.first().state is ServerState.Failed)
            assertEquals(ServerState.Listening, delivery.info.value.servers.last().state)
            delivery.finishHttpTest()
            occupied.close()
            assertPortCanBind(port)
            loopbackSocket(port, host = occupiedHost).use { assertEquals(port, it.localPort) }
        } finally {
            occupied.close()
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-ACC-03, MJPEG-OBS-06, MJPEG-IMG-15
    @Test
    fun credentialRotationEndsExistingMultipartViewingAndPreservesCompletedPayloads() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val logo = byteArrayOf(61, 62, 63, 64)
        var connection: RawHttp? = null
        try {
            assertTrue(delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), logo))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val oldToken = checkNotNull(delivery.appliedAccessToken).value
            val oldCookie = RawHttp(port, "/?accessToken=$oldToken").use {
                checkNotNull(it.headers["set-cookie"]).substringBefore(';')
            }
            val live = RawHttp(port, "/mjpeg?accessToken=$oldToken")
            connection = live
            assertEquals(200, live.status)
            assertArrayEquals(logo, MultipartReader(live.body).nextPart())

            // Complete wire payload receipt is the checkpoint; no held acquired-write race is arranged.
            assertTrue(delivery.updateConfiguration(httpTestProtection("654321"), PostStopImage.Placeholder, httpTestPage()))
            val rotated = delivery.info.value
            assertTrue(rotated.clients.isEmpty())
            assertTrue(rotated.completedJpegs >= 1L)
            assertTrue(rotated.completedJpegBytes >= logo.size.toLong())
            live.awaitExternalEnd()
            RawHttp(port, "/jpeg?accessToken=$oldToken").use { assertEquals(403, it.status) }
            RawHttp(port, "/jpeg", listOf("Cookie: $oldCookie")).use { assertEquals(403, it.status) }
            val currentToken = checkNotNull(delivery.appliedAccessToken).value
            RawHttp(port, "/jpeg?accessToken=$currentToken").use {
                assertEquals(200, it.status)
                assertArrayEquals(logo, it.readBody())
            }
            delivery.finishHttpTest()
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
            delivery.finishHttpTest()
        }
    }

    // Cases: MJPEG-WEB-09, MJPEG-WEB-10, MJPEG-WEB-12
    @Test
    fun webSocketRequiresOriginAndCookiePublishesFactsAndRejectsApplicationCommands() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        val sockets = mutableListOf<WsObserver>()
        try {
            delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val token = checkNotNull(delivery.appliedAccessToken).value
            val origin = "http://127.0.0.1:$port"
            val tokenOnly = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws?accessToken=$token", "Origin" to origin)
            sockets += tokenOnly
            assertEquals("pin_required", tokenOnly.nextJson().getValue("access").jsonPrimitive.content)
            assertEquals(1000, tokenOnly.ended.get(15, TimeUnit.SECONDS))
            val cookie = RawHttp(port, "/?accessToken=$token").use {
                assertEquals(303, it.status)
                checkNotNull(it.headers["set-cookie"]).substringBefore(';')
            }
            val noOrigin = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Cookie" to cookie, "Referer" to "$origin/page")
            sockets += noOrigin
            assertEquals(1008, noOrigin.ended.get(15, TimeUnit.SECONDS))
            for (binary in listOf(false, true)) {
                val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to origin, "Cookie" to cookie)
                sockets += observer
                val snapshot = observer.nextJson()
                assertEquals(setOf("access", "notice", "hasImage", "display"), snapshot.keys)
                assertEquals("authorized", snapshot.getValue("access").jsonPrimitive.content)
                assertEquals("Viewing contract", snapshot.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
                if (binary) observer.send(Frame.Binary(true, byteArrayOf(1, 2)))
                else observer.send(Frame.Text("start"))
                assertEquals(1008, observer.ended.get(15, TimeUnit.SECONDS))
            }
            delivery.finishHttpTest()
            assertTrue(delivery.info.value.clients.isEmpty())
        } finally {
            try {
                httpClient.closeSockets(sockets)
            } finally {
                delivery.finishHttpTest()
            }
        }
    }

    // Cases: MJPEG-WEB-11, MJPEG-ACC-03, MJPEG-WEB-12
    @Test
    fun authorizedSocketReceivesChangedFactsSurvivesPolicyEditAndRevokesOldCredentials() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        val sockets = mutableListOf<WsObserver>()
        try {
            delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val token = checkNotNull(delivery.appliedAccessToken).value
            val cookie = RawHttp(port, "/?accessToken=$token").use { checkNotNull(it.headers["set-cookie"]).substringBefore(';') }
            val current = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port", "Cookie" to cookie)
            sockets += current
            assertTrue(current.nextJson().getValue("hasImage").jsonPrimitive.boolean)
            RawHttp(port, "/jpeg", listOf("Cookie: $cookie")).use {
                assertEquals(200, it.status)
                assertArrayEquals(byteArrayOf(1), it.readBody())
            }
            // Independently constructed same-PIN settings preserve this exact authorized session.
            delivery.updateConfiguration(httpTestProtection().copy(blockMinutes = 3), PostStopImage.None, httpTestPage("Edited"))
            val edited = current.nextJson()
            assertEquals("Edited", edited.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
            assertFalse(edited.getValue("hasImage").jsonPrimitive.boolean)
            assertEquals(token, checkNotNull(delivery.appliedAccessToken).value)
            assertFalse(current.ended.isDone)
            val ping = byteArrayOf(7, 0, 9)
            current.send(Frame.Ping(ping))
            assertArrayEquals(ping, current.pong.get(15, TimeUnit.SECONDS))
            val attempt = attempt()
            delivery.registerCapture(attempt)
            delivery.updateCaptureStatus(attempt, Status.Suspended)
            val suspended = current.awaitJson { it.getValue("notice").jsonPrimitive.content == "paused" }
            assertFalse(suspended.getValue("hasImage").jsonPrimitive.boolean)
            delivery.updateCaptureStatus(attempt, Status.Active)
            delivery.offerJpeg(attempt, 2) { it[0] = 11; it[1] = 12 }
            current.awaitJson { it.getValue("hasImage").jsonPrimitive.boolean }
            delivery.updateConfiguration(httpTestProtection("654321"), PostStopImage.Placeholder, httpTestPage("New generation"))
            current.awaitTermination()
            RawHttp(port, "/jpeg?accessToken=$token").use { assertEquals(403, it.status) }
            RawHttp(port, "/jpeg", listOf("Cookie: $cookie")).use { assertEquals(403, it.status) }
            val newToken = checkNotNull(delivery.appliedAccessToken).value
            val newCookie = RawHttp(port, "/?accessToken=$newToken").use { checkNotNull(it.headers["set-cookie"]).substringBefore(';') }
            val successor = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port", "Cookie" to newCookie)
            sockets += successor
            assertEquals("New generation", successor.nextJson().getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
            successor.send(Frame.Close(CloseReason(CloseReason.Codes.NORMAL, "")))
            // Peer Close or transport EOF witnesses termination; no Close code is promised here.
            // Stop/rebind below proves eventual server cleanup.
            successor.awaitTermination()
            delivery.finishHttpTest()
            assertTrue(delivery.info.value.clients.isEmpty())
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(sockets)
            } finally {
                delivery.finishHttpTest()
            }
        }
    }

    // Cases: MJPEG-WEB-13
    @Test
    fun openSocketHonorsEncodedSnapshotLimitIncludingUnicodeAndEscaping() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        var socket: WsObserver? = null
        try {
            delivery.start(AccessSettings(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port")
            socket = observer
            val template = observer.nextJson()
            assertEquals("open", template.getValue("access").jsonPrimitive.content)
            fun snapshotWithTitle(title: String): String {
                val display = JsonObject(template.getValue("display").jsonObject + ("title" to JsonPrimitive(title)))
                return JsonObject(template + ("display" to display)).toString()
            }

            val seed = "雪\"\\\n".repeat(100)
            val seedSize = snapshotWithTitle(seed).toByteArray(Charsets.UTF_8).size
            for (size in listOf(256 * 1024 - 1, 256 * 1024)) {
                val title = seed + "x".repeat(size - seedSize)
                assertEquals(size, snapshotWithTitle(title).toByteArray(Charsets.UTF_8).size)
                delivery.updateConfiguration(AccessSettings(), PostStopImage.Placeholder, httpTestPage(title))
                val message = observer.nextText()
                assertEquals(size, message.toByteArray(Charsets.UTF_8).size)
                assertEquals(title, Json.parseToJsonElement(message).jsonObject.getValue("display").jsonObject.getValue("title").jsonPrimitive.content)
            }
            val oversized = seed + "x".repeat(256 * 1024 + 1 - seedSize)
            assertEquals(256 * 1024 + 1, snapshotWithTitle(oversized).toByteArray(Charsets.UTF_8).size)
            delivery.updateConfiguration(AccessSettings(), PostStopImage.Placeholder, httpTestPage(oversized))
            assertEquals(1011, observer.ended.get(15, TimeUnit.SECONDS))
            assertFalse(observer.hasQueuedMessage())
            delivery.finishHttpTest()
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(listOfNotNull(socket))
            } finally {
                delivery.finishHttpTest()
            }
        }
    }

    // Cases: MJPEG-WEB-09
    @Test
    fun blockedSocketPublishesRemainingPinBlockAndClosesNormally() = runBlocking {
        val delivery = KtorHttpDelivery(httpTestContext())
        val port = unusedLoopbackPort()
        val httpClient = webSocketClient()
        var socket: WsObserver? = null
        try {
            delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
            delivery.configureServers(listOf(httpTestAddress()), port)
            delivery.awaitHttpInfo { it.servers.single().state == ServerState.Listening }
            val block = "pin=000000"
            repeat(5) { index ->
                RawHttp(port, "/pin", listOf("Origin: http://127.0.0.1:$port", "Content-Type: application/x-www-form-urlencoded"), block).use {
                    assertEquals(if (index == 4) 429 else 403, it.status)
                }
            }
            val observer = httpClient.observeWebSocket("ws://127.0.0.1:$port/ws", "Origin" to "http://127.0.0.1:$port")
            socket = observer
            val denied = observer.nextJson()
            assertEquals(setOf("access", "retryAfterMs"), denied.keys)
            assertEquals("blocked", denied.getValue("access").jsonPrimitive.content)
            assertEquals(60_000L, denied.getValue("retryAfterMs").jsonPrimitive.content.toLong())
            assertEquals(1000, observer.ended.get(15, TimeUnit.SECONDS))
            delivery.finishHttpTest()
            assertPortCanBind(port)
        } finally {
            try {
                httpClient.closeSockets(listOfNotNull(socket))
            } finally {
                delivery.finishHttpTest()
            }
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

    private suspend fun HttpClient.observeWebSocket(urlString: String, vararg extraHeaders: Pair<String, String>): WsObserver {
        val session = withTimeout(15.seconds) {
            webSocketRawSession {
                url(urlString)
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
        }
        return WsObserver(this, session)
    }

    private suspend fun HttpClient.closeSockets(sockets: List<WsObserver>) {
        val clientJob = coroutineContext.job
        withContext(NonCancellable) {
            try {
                sockets.forEach(WsObserver::cancel)
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

    private class WsObserver(client: HttpClient, private val session: ClientWebSocketSession) {
        private val messages = LinkedBlockingQueue<String>()
        val ended = CompletableFuture<Int>()
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
                            ended.complete(reason.code.toInt())
                            session.close(reason)
                            return@launch
                        }

                        is Frame.Binary -> Unit
                    }
                }
                currentCoroutineContext().ensureActive()
                ended.completeExceptionally(EOFException("WebSocket ended without a Close frame"))
            } catch (cause: Throwable) {
                // Canceling our receiver during cleanup cannot manufacture a peer termination.
                if (currentCoroutineContext().isActive) ended.completeExceptionally(cause)
                else throw cause
            }
        }

        suspend fun send(frame: Frame) = withTimeout(15.seconds) {
            session.send(frame)
            session.flush()
        }

        fun awaitTermination() {
            try {
                ended.get(15, TimeUnit.SECONDS)
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

        fun hasQueuedMessage(): Boolean = messages.isNotEmpty()

        fun awaitJson(predicate: (JsonObject) -> Boolean): JsonObject {
            repeat(8) {
                val snapshot = nextJson()
                if (predicate(snapshot)) return snapshot
            }
            throw AssertionError("Expected WebSocket facts were not observed")
        }
    }

    /** Independent HTTP/1.1 reader; no Ktor serialization or response parser is reused. */
    private class RawHttp(port: Int, path: String, extraHeaders: List<String> = emptyList(), requestBody: String? = null) : AutoCloseable {
        private val socket = Socket()
        val status: Int
        val headers: Map<String, String>
        val body: InputStream

        init {
            try {
                socket.soTimeout = 15_000
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 15_000)
                val input = socket.getInputStream().buffered()
                socket.getOutputStream().apply {
                    val method = if (requestBody == null) "GET" else "POST"
                    val bodyBytes = requestBody?.toByteArray(Charsets.UTF_8)
                    val request = buildString {
                        append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
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

        fun awaitExternalEnd() {
            try {
                while (body.read() >= 0) {
                    // Drain any completed transport tail until EOF.
                }
            } catch (cause: IOException) {
                if (cause is java.net.SocketTimeoutException) throw cause
                // Connection reset/truncated chunking independently witnesses transport termination.
            }
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

        fun readHeaderLines(input: InputStream): Map<String, String> = buildMap {
            while (true) {
                val line = readCrlfLine(input)
                if (line.isEmpty()) return@buildMap
                val separator = line.indexOf(':')
                check(separator > 0) { "Invalid header line" }
                put(line.substring(0, separator).lowercase(), line.substring(separator + 1).trim())
            }
        }
    }
}
