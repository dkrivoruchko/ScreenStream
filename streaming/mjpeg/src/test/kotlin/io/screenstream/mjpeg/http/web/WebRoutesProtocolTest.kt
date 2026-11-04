package io.screenstream.mjpeg.http.web

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.response.respond
import io.ktor.server.testing.runTestApplication
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.KtorHttpDelivery
import io.screenstream.mjpeg.http.UnstartedHttpListener
import io.screenstream.mjpeg.http.finishHttpTest
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.httpTestPage
import io.screenstream.mjpeg.http.httpTestProtection
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.StreamBehaviorSettings.PostStopImage
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import java.lang.management.ManagementFactory

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
                val response = client.get("http://localhost/?token=") {
                    header(HttpHeaders.Host, host)
                    header("X-Forwarded-Host", "127.0.0.1:8080")
                    header("Forwarded", "host=127.0.0.1:8080")
                }
                assertEquals(
                    host,
                    if (host.contains("@") || host.contains("/") || host.contains("?") || host.contains("#")) HttpStatusCode.BadRequest else HttpStatusCode.Forbidden,
                    response.status
                )
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
                credentialGeneration = 0L,
                accessOpen = false,
                notice = null,
                imageKind = io.screenstream.mjpeg.http.media.JpegStore.ImageKind.None,
                display = WebPageSettings(background = "#FfEe00", title = "Confidential viewer title"),
            )
            val configuredPage = client.get("http://localhost/") { header(HttpHeaders.Host, "127.0.0.1:8080") }
            val configuredHtml = configuredPage.body<ByteArray>()
            assertArrayEquals(packagedHtml, configuredHtml)
            assertFalse(configuredHtml.decodeToString().contains("Confidential viewer title"))
            assertNull(configuredPage.headers[HttpHeaders.SetCookie])
            val token = checkNotNull(handler.access.token).value
            val redirects = listOf("", "wrong", token, token).map { supplied ->
                client.get("http://localhost/?token=$supplied") { header(HttpHeaders.Host, "127.0.0.1:8080") }
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedAndTimedOutBodyReadsDoNotReachVerificationOrSpendWrongPinBudget() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val requestDispatcher = StandardTestDispatcher(scheduler)

        fun drainUntil(condition: () -> Boolean) {
            val deadline = ManagementFactory.getRuntimeMXBean().uptime + 5_000L
            while (true) {
                scheduler.runCurrent()
                if (condition()) return
                check(ManagementFactory.getRuntimeMXBean().uptime < deadline) { "PIN request did not reach its external checkpoint" }
                Thread.yield()
            }
        }

        suspend fun <T> awaitRequest(request: suspend () -> T): T = coroutineScope {
            val response = async(Dispatchers.Default) { request() }
            try {
                drainUntil { response.isCompleted }
                response.await()
            } finally {
                response.cancel()
                drainUntil { response.isCompleted }
            }
        }

        runTestApplication {
            engine { dispatcher = requestDispatcher }
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
            val readEntered = CompletableDeferred<Long>()
            val stalledInput = object : ByteReadChannel by stalled {
                override suspend fun awaitContent(min: Int): Boolean {
                    readEntered.complete(scheduler.currentTime)
                    return stalled.awaitContent(min)
                }
            }
            try {
                // The public pipeline records the actual receive failure before the test engine
                // handles/rethrows it; no incidental HTTP failure status is asserted.
                awaitRequest {
                    try {
                        pinRequest(faultingInput)
                    } catch (_: Exception) {
                    }
                }
                assertTrue(handler.verifiedPins.isEmpty())
                coroutineScope {
                    val response = async(Dispatchers.Default) {
                        try {
                            pinRequest(stalledInput)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        drainUntil { readEntered.isCompleted }
                        val enteredAt = readEntered.await()
                        assertEquals(enteredAt, scheduler.currentTime)
                        scheduler.advanceTimeBy(4_999)
                        scheduler.runCurrent()
                        assertEquals(enteredAt + 4_999, scheduler.currentTime)
                        assertFalse(response.isCompleted)
                        assertEquals(1, readFailures.size)
                        assertTrue(handler.verifiedPins.isEmpty())
                        scheduler.advanceTimeBy(1)
                        scheduler.runCurrent()
                        assertEquals(enteredAt + 5_000, scheduler.currentTime)
                        assertEquals(2, readFailures.size)
                        assertTrue(generateSequence(readFailures[1]) { it.cause }.any { it is TimeoutCancellationException })
                        drainUntil { response.isCompleted }
                        response.await()
                    } finally {
                        response.cancel()
                        stalled.cancel(null)
                        drainUntil { response.isCompleted }
                    }
                }
                assertTrue(handler.verifiedPins.isEmpty())
                assertEquals(2, readFailures.size)
                assertTrue(generateSequence(readFailures[0]) { it.cause }.any { it === readFault })
                assertTrue(generateSequence(readFailures[1]) { it.cause }.any { it is TimeoutCancellationException })
                repeat(4) { assertEquals(HttpStatusCode.Forbidden, awaitRequest { pinRequest("pin=000000") }.status) }
                assertEquals(HttpStatusCode.TooManyRequests, awaitRequest { pinRequest("pin=000000") }.status)
            } finally {
                failed.cancel(null)
                stalled.cancel(null)
                owner.close()
            }
        }
    }

    // Cases: MJPEG-WEB-01, MJPEG-WEB-14, MJPEG-WEB-16, MJPEG-WEB-17
    @Test
    fun allPathsApplyAuthorityHeadersBeforeMethodsAndPreflightUsesFixedPolicy() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val expected = mapOf("/" to "GET", "/favicon.ico" to "GET", "/pin" to "POST", "/jpeg" to "GET, OPTIONS", "/mjpeg" to "GET, OPTIONS", "/ws" to "GET")
            for ((path, allow) in expected) {
                for (method in listOf(HttpMethod.Head, HttpMethod.Put, HttpMethod.Delete)) {
                    val response = client.request("http://localhost$path") {
                        this.method = method
                        header(HttpHeaders.Host, "127.0.0.1:8080")
                    }
                    assertEquals("$method $path", HttpStatusCode.MethodNotAllowed, response.status)
                    assertEquals(allow, response.headers[HttpHeaders.Allow])
                }
                for (suffix in listOf("", "?token=", "?token=secret")) {
                    for (host in listOf("bad/authority", "127.0.0.2:8080")) {
                        val response = client.get("http://localhost$path$suffix") { header(HttpHeaders.Host, host) }
                        assertEquals(if (host == "bad/authority") HttpStatusCode.BadRequest else HttpStatusCode.Forbidden, response.status)
                        assertCommonHeaders(
                            response.headers[HttpHeaders.CacheControl],
                            response.headers["X-Content-Type-Options"],
                            response.headers["Referrer-Policy"],
                            suffix.isNotEmpty()
                        )
                        assertEquals("ScreenStream", response.headers[HttpHeaders.Server])
                        assertTrue(response.headers[HttpHeaders.Date] != null)
                    }
                }
            }
            for (path in expected.keys + "/unknown") for (origin in listOf<String?>(null, "null", "https://foreign.example")) {
                val response = client.request("http://localhost$path") {
                    method = HttpMethod.Options
                    header(HttpHeaders.Host, "127.0.0.1:8080")
                    origin?.let { header(HttpHeaders.Origin, it) }
                    header(HttpHeaders.AccessControlRequestMethod, "DELETE")
                    header(HttpHeaders.AccessControlRequestHeaders, "X-Custom")
                }
                if (path == "/jpeg" || path == "/mjpeg") {
                    assertEquals(HttpStatusCode.NoContent, response.status)
                    assertEquals("GET, OPTIONS", response.headers[HttpHeaders.Allow])
                    assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
                    assertEquals("GET, OPTIONS", response.headers[HttpHeaders.AccessControlAllowMethods])
                    assertEquals("Content-Type, Range", response.headers[HttpHeaders.AccessControlAllowHeaders])
                } else {
                    assertEquals(if (path == "/unknown") HttpStatusCode.NotFound else HttpStatusCode.MethodNotAllowed, response.status)
                    assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
                    if (path != "/unknown") assertEquals(expected[path], response.headers[HttpHeaders.Allow])
                }
                assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
            }
            handler.active = false
            for (path in expected.keys + "/unknown") {
                assertEquals(HttpStatusCode.Forbidden, client.get("http://localhost$path") { header(HttpHeaders.Host, "127.0.0.1:8080") }.status)
            }
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-04, MJPEG-WEB-17
    @Test
    fun onlySuccessfulPackagedHtmlIsCompressedAndFaviconRemainsPublic() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val client = createClient { followRedirects = false }
            val html = client.get("http://localhost/") {
                header(HttpHeaders.Host, "127.0.0.1:8080")
                header(HttpHeaders.AcceptEncoding, "gzip")
            }
            assertEquals(HttpStatusCode.OK, html.status)
            assertEquals("gzip", html.headers[HttpHeaders.ContentEncoding])
            val inflated = java.util.zip.GZIPInputStream(html.body<ByteArray>().inputStream()).use { it.readBytes() }
            assertArrayEquals(httpTestContext().assets.open("mjpeg/index.html").use { it.readBytes() }, inflated)
            for (path in listOf("/?token=", "/favicon.ico", "/jpeg", "/mjpeg", "/unknown")) {
                val response = client.get("http://localhost$path") {
                    header(HttpHeaders.Host, "127.0.0.1:8080")
                    header(HttpHeaders.AcceptEncoding, "gzip")
                }
                assertNull(path, response.headers[HttpHeaders.ContentEncoding])
                if (path == "/favicon.ico") {
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertTrue(checkNotNull(response.headers[HttpHeaders.ContentType]).startsWith("image/x-icon"))
                    assertArrayEquals(httpTestContext().assets.open("mjpeg/favicon.ico").use { it.readBytes() }, response.body<ByteArray>())
                    assertNull(response.headers[HttpHeaders.SetCookie])
                }
            }
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-08, MJPEG-WEB-15
    @Test
    fun deliveryChecksCredentialsBeforeLateListenerOrTerminalUnavailabilityAndRoutesKeepCors() = testApplication {
        val owner = UnstartedHttpListener()
        val delivery = KtorHttpDelivery(httpTestContext())
        delivery.start(httpTestProtection(), PostStopImage.Placeholder, httpTestPage(), byteArrayOf(1))
        val passedAuthority = object : WebRoutes.RequestHandler by delivery {
            override fun isListenerActive(listener: HttpListener): Boolean = true
        }
        application { WebRoutes(httpTestContext(), passedAuthority).installRoutes(this, owner.listener) }
        val token = checkNotNull(delivery.appliedToken).value
        try {
            for (closing in listOf(false, true)) {
                if (closing) delivery.requestStop()
                for (path in listOf("/jpeg", "/mjpeg")) for (query in listOf("", "?token=wrong", "?token=$token")) {
                    val response = client.get("http://localhost$path$query") { header(HttpHeaders.Host, "127.0.0.1:8080") }
                    assertEquals(if (query == "?token=$token") HttpStatusCode.ServiceUnavailable else HttpStatusCode.Forbidden, response.status)
                    assertEquals(if (query == "?token=$token") "1" else null, response.headers[HttpHeaders.RetryAfter])
                    assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
                    assertEquals("Content-Length, Content-Range, Retry-After", response.headers[HttpHeaders.AccessControlExposeHeaders])
                    assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
                }
            }
        } finally {
            delivery.finishHttpTest()
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-08, MJPEG-WEB-15
    @Test
    fun crossOriginSignalsIgnoreOnlyCookieAndNeverBroadenTokenAuthority() = testApplication {
        val owner = UnstartedHttpListener()
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            val token = checkNotNull(handler.access.token).value
            val cookie = checkNotNull(handler.access.exchangeToken(token)).value
            val signals = listOf(
                emptyList<Pair<String, String>>() to false,
                listOf("Origin" to "http://127.0.0.1:8080") to false,
                listOf("Sec-Fetch-Site" to "same-origin") to false,
                listOf("Sec-Fetch-Site" to "none") to false,
                listOf("Referer" to "http://127.0.0.1:8080/page?x=1") to false,
                listOf("Origin" to "http://127.0.0.2:8080") to true,
                listOf("Origin" to "null") to true,
                listOf("Origin" to "malformed") to true,
                listOf("Sec-Fetch-Site" to "same-site") to true,
                listOf("Sec-Fetch-Site" to "cross-site") to true,
                listOf("Referer" to "http://127.0.0.2:8080/page") to true,
                listOf("Referer" to "malformed") to true,
                listOf("Origin" to "http://127.0.0.1:8080", "Sec-Fetch-Site" to "cross-site") to true,
            )
            for (open in listOf(false, true)) {
                if (open) handler.access.updateSettings(AccessSettings())
                for (path in listOf("/jpeg", "/mjpeg")) for ((headers, cross) in signals) {
                    for ((query, sendCookie, credentialValid) in listOf(
                        Triple("", false, false), Triple("?token=wrong", false, false),
                        Triple("?token=$token", false, true), Triple("", true, !cross),
                        Triple("?token=wrong", true, !cross), Triple("?token=$token", true, true),
                    )) {
                        val response = client.get("http://localhost$path$query") {
                            header(HttpHeaders.Host, "127.0.0.1:8080")
                            headers.forEach { (name, value) -> header(name, value) }
                            if (sendCookie) header(HttpHeaders.Cookie, "screenstream_access=$cookie")
                        }
                        assertEquals(
                            "$path $headers $query cookie=$sendCookie open=$open",
                            if (open || credentialValid) HttpStatusCode.NoContent else HttpStatusCode.Forbidden,
                            response.status
                        )
                        assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin])
                        assertEquals("Content-Length, Content-Range, Retry-After", response.headers[HttpHeaders.AccessControlExposeHeaders])
                        assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
                    }
                }
            }
        } finally {
            owner.close()
        }
    }

    // Cases: MJPEG-WEB-02, MJPEG-WEB-03, MJPEG-WEB-07
    @Test
    fun authoritySyntaxAndOriginPrecedenceAreCheckedBeforePinVerification() = testApplication {
        val owner = UnstartedHttpListener(host = "2001:db8::1")
        val handler = AccessHandler(owner.listener, httpTestProtection())
        application { WebRoutes(httpTestContext(), handler).installRoutes(this, owner.listener) }
        try {
            for (host in listOf("[2001:db8::1]:8080", "[2001:DB8:0:0:0:0:0:1]:8080")) {
                assertEquals(HttpStatusCode.OK, client.get("http://localhost/") { header(HttpHeaders.Host, host) }.status)
            }
            for (host in listOf("[2001:db8::1]:word", "é:8080", "127.0.0.1:8080/path")) {
                assertEquals(host, HttpStatusCode.BadRequest, client.get("http://localhost/") { header(HttpHeaders.Host, host) }.status)
            }
            assertEquals(HttpStatusCode.Forbidden, client.get("http://localhost/") { header(HttpHeaders.Host, "[2001:db8::1%lo]:8080") }.status)
            val host = "[2001:db8::1]:8080"
            for ((origin, referer, expected) in listOf(
                Triple("http://$host", "http://foreign:8080/page", HttpStatusCode.NoContent),
                Triple("http://[2001:db8:0:0:0:0:0:1]:8080", "http://$host/page", HttpStatusCode.Forbidden),
                Triple("http://$host/path", "http://$host/page", HttpStatusCode.Forbidden),
                Triple(null, "http://$host/page?query=1", HttpStatusCode.NoContent),
                Triple(null, null, HttpStatusCode.Forbidden),
            )) {
                val response = client.post("http://localhost/pin") {
                    header(HttpHeaders.Host, host)
                    origin?.let { header(HttpHeaders.Origin, it) }
                    referer?.let { header(HttpHeaders.Referrer, it) }
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("pin=123456")
                }
                assertEquals(expected, response.status)
            }
            assertEquals(listOf("123456", "123456"), handler.verifiedPins)
        } finally {
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
        override fun exchangeToken(listener: HttpListener, token: String?): SecretValue? =
            if (isListenerActive(listener)) access.exchangeToken(token) else null

        val verifiedPins: MutableList<String?> = mutableListOf()
        override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? {
            verifiedPins += pin
            return if (isListenerActive(listener)) access.verifyPin(pin, peerIp, now) else null
        }

        override suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, token: String?, accessCookie: String?) {
            call.respond(
                if (isListenerActive(listener) && access.allowsViewing(
                        token,
                        accessCookie
                    )
                ) HttpStatusCode.NoContent else HttpStatusCode.Forbidden
            )
        }

        override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebRoutes.WebSocketAdmission =
            WebRoutes.WebSocketAdmission.Denied(null)

        override fun isWebSocketCurrent(listener: HttpListener, credentialGeneration: Long): Boolean = false
    }
}
