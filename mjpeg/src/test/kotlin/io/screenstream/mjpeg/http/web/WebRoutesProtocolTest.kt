package io.screenstream.mjpeg.http.web

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.UnstartedHttpListener
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.httpTestProtection
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class WebRoutesProtocolTest {
    // Cases: MJPEG-WEB-01, MJPEG-WEB-02, MJPEG-WEB-14
    @Test
    fun pipelineRequiresDirectListenerAuthorityAndAppliesHeadersBeforeRouting() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener)
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val client = createClient { followRedirects = false }
            for (host in listOf("127.0.0.1:8080", "localhost:8080")) {
                val response = client.get("http://localhost/missing") { header(HttpHeaders.Host, host) }
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertCommonHeaders(
                    response.headers[HttpHeaders.CacheControl],
                    response.headers["X-Content-Type-Options"],
                    response.headers["Referrer-Policy"],
                    false
                )
            }
            for (host in listOf(
                "127.0.0.2:8080",
                "example.com:8080",
                "127.0.0.1:8081",
                "user@127.0.0.1:8080",
                "127.0.0.1:8080/path",
                "127.0.0.1:8080?query",
                "127.0.0.1:8080#fragment"
            )) {
                val response = client.get("http://localhost/?accessToken=") {
                    header(HttpHeaders.Host, host)
                    header("X-Forwarded-Host", "127.0.0.1:8080")
                    header("Forwarded", "host=127.0.0.1:8080")
                }
                assertEquals(host, HttpStatusCode.Forbidden, response.status)
                assertCommonHeaders(
                    response.headers[HttpHeaders.CacheControl],
                    response.headers["X-Content-Type-Options"],
                    response.headers["Referrer-Policy"],
                    true
                )
            }
            handler.active = false
            assertEquals(HttpStatusCode.Forbidden, client.get("http://localhost/") { header(HttpHeaders.Host, "127.0.0.1:8080") }.status)
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-02
    @Test
    fun localhostHostIsRejectedForANonLoopbackListener() = testApplication {
        val owner = UnstartedHttpListener(host = "192.0.2.1")
        application { WebRoutes(httpTestContext(), AccessHandler(owner.listener)).installRoutes(this, owner.listener) }
        try {
            assertEquals(HttpStatusCode.OK, client.get("http://localhost/") { header(HttpHeaders.Host, "192.0.2.1:8080") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("http://localhost/") { header(HttpHeaders.Host, "localhost:8080") }.status)
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-02, MJPEG-WEB-03
    @Test
    fun ipv6AndLocalhostAllowOmittedPortForPort80Listener() = testApplication {
        val owner = UnstartedHttpListener(host = "::1", port = 80)
        application { WebRoutes(httpTestContext(), AccessHandler(owner.listener)).installRoutes(this, owner.listener) }
        try {
            assertEquals(HttpStatusCode.OK, client.get("http://localhost/") { header(HttpHeaders.Host, "[::1]") }.status)
            assertEquals(HttpStatusCode.OK, client.get("http://localhost/") { header(HttpHeaders.Host, "localhost") }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("http://localhost/") { header(HttpHeaders.Host, "[::2]") }.status)
            for ((origin, expected) in listOf(
                "http://[::1]" to HttpStatusCode.NoContent,
                "http://[0:0:0:0:0:0:0:1]" to HttpStatusCode.Forbidden,
            )) {
                // Trusted Host uses address bytes; Origin additionally preserves the host spelling.
                assertEquals(expected, client.post("http://localhost/pin") {
                    header(HttpHeaders.Host, "[::1]")
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("pin=123456")
                }.status)
            }
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-04
    @Test
    fun publicPageAndTokenRedirectExchangeOnlyCurrentCredentials() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val client = createClient { followRedirects = false }
            val publicPage = client.get("http://localhost/") { header(HttpHeaders.Host, "127.0.0.1:8080") }
            assertEquals(HttpStatusCode.OK, publicPage.status)
            assertTrue(checkNotNull(publicPage.headers[HttpHeaders.ContentType]).startsWith("text/html"))
            assertTrue(publicPage.bodyAsText().contains("<!doctype html>"))
            val packagedHtml = httpTestContext().assets.open("mjpeg/index.html").use { it.readBytes() }
            assertArrayEquals(packagedHtml, publicPage.body<ByteArray>())
            assertNull(publicPage.headers[HttpHeaders.SetCookie])
            handler.webState.value = WebState(
                generation = 0L,
                accessOpen = false,
                notice = null,
                hasImage = false,
                display = WebPageSettings(background = "#FfEe00", title = "Confidential viewer title"),
            )
            val configuredPage = client.get("http://localhost/") { header(HttpHeaders.Host, "127.0.0.1:8080") }
            val configuredHtml = configuredPage.body<ByteArray>()
            assertArrayEquals(packagedHtml, configuredHtml)
            assertFalse(configuredHtml.decodeToString().contains("Confidential viewer title"))
            assertNull(configuredPage.headers[HttpHeaders.SetCookie])
            val token = checkNotNull(handler.access.accessToken).value
            val redirects = listOf("", "wrong", token, token).map { supplied ->
                client.get("http://localhost/?accessToken=$supplied") { header(HttpHeaders.Host, "127.0.0.1:8080") }
            }
            redirects.forEach { response ->
                assertEquals(HttpStatusCode.SeeOther, response.status)
                assertEquals("/", response.headers[HttpHeaders.Location])
                assertEquals("no-referrer", response.headers["Referrer-Policy"])
            }
            assertNull(redirects[0].headers[HttpHeaders.SetCookie])
            assertNull(redirects[1].headers[HttpHeaders.SetCookie])
            val cookie = checkNotNull(redirects[2].headers[HttpHeaders.SetCookie])
            assertEquals(cookie, redirects[3].headers[HttpHeaders.SetCookie])
            assertEquals(setOf("Path=/", "HttpOnly", "SameSite=Lax"), cookie.split("; ").drop(1).toSet())
            assertTrue(cookie.startsWith("screenstream_access="))
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-03, MJPEG-WEB-14, MJPEG-ACC-04
    @Test
    fun rejectedOriginsCannotSpendPinBudgetAndForwardedPeerDoesNotSplitIt() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            assertEquals(HttpStatusCode.Forbidden, client.post("http://localhost/pin") {
                header(HttpHeaders.Host, "127.0.0.1:8080")
                header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                setBody("pin=000000")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("http://localhost/pin") {
                header(HttpHeaders.Host, "127.0.0.1:8080")
                header(HttpHeaders.Referrer, "http://127.0.0.2:8080/page?x=1")
                header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                setBody("pin=000000")
            }.status)
            for (origin in listOf(
                "null",
                "https://127.0.0.1:8080",
                "http://127.0.0.2:8080",
                "http://127.0.0.1:8081",
                "http://user@127.0.0.1:8080",
                "http://127.0.0.1:8080/#fragment"
            )) {
                assertEquals(HttpStatusCode.Forbidden, client.post("http://localhost/pin") {
                    header(HttpHeaders.Host, "127.0.0.1:8080")
                    header(HttpHeaders.Origin, origin)
                    header(HttpHeaders.Referrer, "http://127.0.0.1:8080/page?x=1")
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("pin=000000")
                }.status)
            }
            repeat(4) { index ->
                assertEquals(HttpStatusCode.Forbidden, pinRequest("pin=000000", forwardedPeer = "198.51.100.$index").status)
            }
            assertEquals(HttpStatusCode.TooManyRequests, pinRequest("pin=000000", forwardedPeer = "203.0.113.1").status)
            handler.now = 60_000L
            val fallback = client.post("http://localhost/pin") {
                header(HttpHeaders.Host, "127.0.0.1:8080")
                header(HttpHeaders.Referrer, "http://127.0.0.1:8080/page?x=1")
                header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                setBody("pin=123456")
            }
            assertEquals(HttpStatusCode.NoContent, fallback.status)
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-05, MJPEG-WEB-07
    @Test
    fun invalidPinFormsConsumeWrongAttemptBudgetAndRetryAfterRoundsUp() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val oversized = "pin=123456&padding=".let { prefix -> prefix + "x".repeat(257 - prefix.length) }
            val invalidBodies = listOf("pin=123456&pin=123456", "other=123456", "pin=", oversized)
            invalidBodies.forEach { body -> assertEquals(HttpStatusCode.Forbidden, pinRequest(body).status) }
            val blocked = pinRequest("pin=123456", type = "text/plain")
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertEquals("60", blocked.headers[HttpHeaders.RetryAfter])
            handler.now = 1_001L
            val stillBlocked = pinRequest("pin=123456")
            assertEquals(HttpStatusCode.TooManyRequests, stillBlocked.status)
            assertEquals("59", stillBlocked.headers[HttpHeaders.RetryAfter])
            assertNull(stillBlocked.headers[HttpHeaders.SetCookie])
            handler.now = 60_000L
            val exactBody = "pin=123456&padding=".let { prefix -> prefix + "x".repeat(256 - prefix.length) }
            val authorized = pinRequest(exactBody)
            assertEquals(HttpStatusCode.NoContent, authorized.status)
            assertTrue(checkNotNull(authorized.headers[HttpHeaders.SetCookie]).startsWith("screenstream_access="))
            handler.access.updateSettings(AccessSettings())
            val open = pinRequest("irrelevant")
            assertEquals(HttpStatusCode.NoContent, open.status)
            assertNull(open.headers[HttpHeaders.SetCookie])
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-05
    @Test
    fun malformedPercentEscapeIsRejectedAndConsumesProtectedVerificationBudget() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val malformed = pinRequest("pin=%GG")
            assertEquals(HttpStatusCode.Forbidden, malformed.status)
            assertNull(malformed.headers[HttpHeaders.SetCookie])
            repeat(3) { assertEquals(HttpStatusCode.Forbidden, pinRequest("pin=000000").status) }
            assertEquals(HttpStatusCode.TooManyRequests, pinRequest("pin=000000").status)
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-05
    @Test
    fun pinBodyLimitUsesUtf8BytesAndStopsAfterTheDetectionByteWithoutWaitingForEof() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        val input = ByteChannel(autoFlush = true)
        try {
            val prefix = "pin=123456&padding="
            val base = prefix + "雪".repeat(79)
            val exact = base + "x".repeat(256 - base.toByteArray().size)
            assertEquals(256, exact.toByteArray().size)
            assertEquals(HttpStatusCode.NoContent, pinRequest(exact).status)
            input.writeFully((exact + "yz").toByteArray())
            val oversized = pinRequest(input)
            assertEquals(HttpStatusCode.Forbidden, oversized.status)
            assertNull(oversized.headers[HttpHeaders.SetCookie])
            assertEquals(listOf("123456", null), handler.verifiedPins)
            // The channel remains open: receiving a response did not need EOF or the 258th byte.
            val unread = ByteArray(1)
            assertEquals(1, input.readAvailable(unread))
            assertArrayEquals(byteArrayOf('z'.code.toByte()), unread)
        } finally {
            input.cancel(null)
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-05
    @Test
    fun unsupportedTypeDoesNotReadItsOpenBodyButStillReachesProtectedVerification() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        val input = ByteChannel()
        try {
            val response = pinRequest(input, ContentType.Text.Plain)
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(listOf<String?>(null), handler.verifiedPins)
            assertNull(response.headers[HttpHeaders.SetCookie])
        } finally {
            input.cancel(null)
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-06
    @Test
    fun failedAndTimedOutBodyReadsDoNotReachVerificationOrSpendWrongPinBudget() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        val readFailures = mutableListOf<Throwable>()
        application {
            intercept(ApplicationCallPipeline.Monitoring) {
                try {
                    proceed()
                } catch (cause: Throwable) {
                    readFailures += cause
                    throw cause
                }
            }
            WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener)
        }
        val readFault = IOException("Arranged request-body failure")
        val failed = ByteChannel()
        val faultingInput = object : ByteReadChannel by failed {
            override suspend fun awaitContent(min: Int): Boolean {
                // A channel canceled before readAvailable is EOF on this Ktor version.
                // Fail at its public wait boundary instead, after an actual empty-body read.
                failed.cancel(readFault)
                return failed.awaitContent(min)
            }
        }
        val stalled = ByteChannel()
        try {
            // The public pipeline records the actual receive failure before the test engine
            // handles/rethrows it; no incidental HTTP failure status is asserted.
            for (input in listOf(faultingInput, stalled)) {
                try {
                    pinRequest(input)
                } catch (_: Exception) {
                }
                assertTrue(handler.verifiedPins.isEmpty())
            }
            assertEquals(2, readFailures.size)
            assertTrue(generateSequence(readFailures[0]) { it.cause }.any { it === readFault })
            assertTrue(generateSequence(readFailures[1]) { it.cause }.any { it is TimeoutCancellationException })
            repeat(4) { assertEquals(HttpStatusCode.Forbidden, pinRequest("pin=000000").status) }
            assertEquals(HttpStatusCode.TooManyRequests, pinRequest("pin=000000").status)
        } finally {
            failed.cancel(null)
            stalled.cancel(null)
            owner.close()
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pinRequest(
        input: ByteReadChannel,
        type: ContentType = ContentType.Application.FormUrlEncoded,
    ): io.ktor.client.statement.HttpResponse = client.post("http://localhost/pin") {
        header(HttpHeaders.Host, "127.0.0.1:8080")
        header(HttpHeaders.Origin, "http://127.0.0.1:8080")
        setBody(object : OutgoingContent.ReadChannelContent() {
            override val contentType: ContentType = type
            override fun readFrom(): ByteReadChannel = input
        })
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.pinRequest(
        body: String,
        type: String = "application/x-www-form-urlencoded; charset=UTF-8",
        forwardedPeer: String? = null,
    ): io.ktor.client.statement.HttpResponse = client.post("http://localhost/pin") {
        header(HttpHeaders.Host, "127.0.0.1:8080")
        header(HttpHeaders.Origin, "http://127.0.0.1:8080")
        header(HttpHeaders.ContentType, type)
        if (forwardedPeer != null) header("X-Forwarded-For", forwardedPeer)
        setBody(body)
    }

    private fun assertCommonHeaders(cache: String?, nosniff: String?, referrer: String?, tokenPresent: Boolean) {
        assertEquals("no-store", cache)
        assertEquals("nosniff", nosniff)
        assertEquals(if (tokenPresent) "no-referrer" else "same-origin", referrer)
    }

    /** The adapter delegates to real access policy; this fixture does not model physical delivery. */
    private class AccessHandler(private val listener: HttpListener, settings: AccessSettings = AccessSettings()) : WebRoutes.RequestHandler {
        val access: HttpAccess = HttpAccess().apply { updateSettings(settings) }

        @Volatile
        var active: Boolean = true

        @Volatile
        var now: Long = 0L
        override val webState: MutableStateFlow<WebState?> = MutableStateFlow(null)
        override fun isListenerActive(listener: HttpListener): Boolean = active && listener === this.listener
        override fun exchangeAccessToken(listener: HttpListener, accessToken: String?): SecretValue? =
            if (isListenerActive(listener)) access.exchangeAccessToken(accessToken) else null

        val verifiedPins: MutableList<String?> = mutableListOf()
        override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? {
            verifiedPins += pin
            return if (isListenerActive(listener)) access.verifyPin(pin, peerIp, now) else null
        }

        override suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, accessToken: String?, accessCookie: String?) {
            call.respond(
                if (isListenerActive(listener) && access.allowsViewing(
                        accessToken,
                        accessCookie
                    )
                ) HttpStatusCode.NoContent else HttpStatusCode.Forbidden
            )
        }

        override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebRoutes.WebSocketAdmission =
            WebRoutes.WebSocketAdmission.Denied(null)

        override fun isWebSocketCurrent(listener: HttpListener, generation: Long): Boolean = false
    }
}
