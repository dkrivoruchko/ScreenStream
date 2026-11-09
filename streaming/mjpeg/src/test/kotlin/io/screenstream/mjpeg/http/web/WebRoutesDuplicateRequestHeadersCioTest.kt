package io.screenstream.mjpeg.http.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingRoot
import io.screenstream.mjpeg.http.CioHttpListener
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.http.HttpResponseHeaders
import io.screenstream.mjpeg.http.httpTestContext
import io.screenstream.mjpeg.http.httpTestProtectedAccessSettings
import io.screenstream.mjpeg.http.readHttpResponseHeaders
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
internal class WebRoutesDuplicateRequestHeadersCioTest {
    // Cases: MJPEG-WEB-15
    @Test(timeout = 30_000)
    fun realDuplicateHeaderFieldsIgnoreCookieWhileCurrentTokenAndOpenAccessStillWork() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val parent = Job()
        val handler = Handler()
        val observed = Channel<List<String>?>(Channel.UNLIMITED)
        val listener = CioHttpListener(parent, "127.0.0.1", port) { physical ->
            handler.listener = physical
            monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
                val name = checkNotNull(call.request.queryParameters["header"])
                observed.trySend(call.request.headers.getAll(name)?.toList())
            }
            WebRoutes(httpTestContext(), handler).installRoutes(this, physical)
        }
        try {
            withTimeout(5.seconds) { listener.startListening() }
            val token = handler.token()
            val cookie = handler.cookie(token)
            val origin = "http://127.0.0.1:$port"
            val duplicateFields = listOf(
                "Origin" to origin,
                "Sec-Fetch-Site" to "same-origin",
                "Referer" to "$origin/page",
            )
            for (open in listOf(false, true)) {
                if (open) handler.openAccess()
                for (path in listOf("/jpeg", "/mjpeg")) for ((header, value) in duplicateFields) {
                    for ((query, sendCookie, allowed) in listOf(
                        Triple("", false, false), Triple("&token=wrong", false, false),
                        Triple("&token=$token", false, true), Triple("", true, false),
                        Triple("&token=wrong", true, false), Triple("&token=$token", true, true),
                    )) {
                        val headers = mutableListOf("$header: $value", "$header: $value")
                        if (sendCookie) headers += "Cookie: screenstream_access=$cookie"
                        val response = request(port, "$path?header=$header$query", headers)
                        assertEquals(listOf(value, value), withTimeout(5.seconds) { observed.receive() })
                        assertEquals(if (open || allowed) 204 else 403, response.status, "$path $header cookie=$sendCookie open=$open query=$query")
                        assertEquals("*", response.headers["access-control-allow-origin"])
                        assertNull(response.headers["access-control-allow-credentials"])
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
                } finally {
                    parent.cancel()
                    withTimeout(5.seconds) { parent.join() }
                    observed.cancel()
                }
            }
        }
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", port))
            assertEquals(port, it.localPort)
        }
    }

    private fun request(port: Int, path: String, headers: List<String>): Response = Socket().use { socket ->
        socket.soTimeout = 5_000
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        val input = BufferedInputStream(socket.getInputStream())
        val request = buildString {
            append("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val status = line(input).split(' ')[1].toInt()
        val fields = readHttpResponseHeaders { line(input) }
        input.readBytes()
        Response(status, fields)
    }

    private fun line(input: BufferedInputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw EOFException()
            if (next == 13) {
                check(input.read() == 10)
                return bytes.toString(Charsets.US_ASCII)
            }
            check(bytes.size() < 16_384)
            bytes.write(next)
        }
    }

    private data class Response(val status: Int, val headers: HttpResponseHeaders)

    private class Handler : WebRoutes.RequestHandler {
        private val gate = Any()
        private val access = HttpAccess().apply { updateSettings(httpTestProtectedAccessSettings()) }
        lateinit var listener: HttpListener
        override val webState = MutableStateFlow<WebState?>(null)
        fun token(): String = synchronized(gate) { checkNotNull(access.token).value }
        fun cookie(token: String): String = synchronized(gate) { checkNotNull(access.exchangeToken(token)).value }
        fun openAccess() {
            synchronized(gate) { access.updateSettings(AccessSettings()) }
        }

        override fun isListenerActive(listener: HttpListener): Boolean = listener === this.listener
        override fun exchangeToken(listener: HttpListener, token: String?): SecretValue? = synchronized(gate) { access.exchangeToken(token) }
        override fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult? = null
        override suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, token: String?, accessCookie: String?) {
            val allowed = synchronized(gate) { access.allowsViewing(token, accessCookie) }
            call.respond(if (allowed) io.ktor.http.HttpStatusCode.NoContent else io.ktor.http.HttpStatusCode.Forbidden)
        }

        override fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebRoutes.WebSocketAdmission =
            error("Unexpected WebSocket")

        override fun isWebSocketCurrent(listener: HttpListener, credentialGeneration: Long): Boolean = false
    }
}
