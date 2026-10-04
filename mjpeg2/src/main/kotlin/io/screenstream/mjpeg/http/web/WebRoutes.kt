package io.screenstream.mjpeg.http.web

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.screenstream.mjpeg.http.HttpAccess
import io.screenstream.mjpeg.http.HttpDelivery.MediaFormat
import io.screenstream.mjpeg.http.HttpListener
import io.screenstream.mjpeg.settings.SecretValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/** HTTP and WebSocket transport adapter; admission and presentation facts belong to the delivery. */
internal class WebRoutes(context: Context, private val requestHandler: RequestHandler) {
    private val applicationContext = context.applicationContext
    private val indexHtml: ByteArray by lazy { applicationContext.assets.open("mjpeg/index.html").use { it.readBytes() } }

    fun installRoutes(application: Application, listener: HttpListener) {
        application.install(WebSockets) {
            pingPeriodMillis = 15_000L
            timeoutMillis = 15_000L
            maxFrameSize = 4_096L
            channels {
                incoming = bounded(capacity = 4, onOverflow = ChannelOverflow.CLOSE)
                outgoing = bounded(capacity = 8, onOverflow = ChannelOverflow.SUSPEND)
            }
        }
        application.intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val hasAccessTokenParameter = call.request.queryParameters.contains("accessToken")
            call.response.headers.append("Referrer-Policy", if (hasAccessTokenParameter) "no-referrer" else "same-origin")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            if (trustedRequestOrigin(call, listener) == null || !requestHandler.isListenerActive(listener)) {
                call.respond(HttpStatusCode.Forbidden)
                finish()
            }
        }
        application.routing {
            get("/") {
                if (call.request.queryParameters.contains("accessToken")) {
                    requestHandler.exchangeAccessToken(listener, call.request.queryParameters["accessToken"])?.let { setAccessCookie(call, it.value) }
                    call.response.headers.append(HttpHeaders.Location, "/")
                    call.respond(HttpStatusCode.SeeOther)
                } else {
                    call.respondBytes(indexHtml, ContentType.Text.Html)
                }
            }
            post("/pin") {
                if (!hasSameOrigin(call, listener, refererAllowed = true)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val pin = receivePin(call)
                when (val result = requestHandler.verifyPin(listener, pin, call.request.local.remoteHost)) {
                    is HttpAccess.PinResult.Authorized -> {
                        result.cookie?.let { setAccessCookie(call, it.value) }
                        call.respond(HttpStatusCode.NoContent)
                    }

                    is HttpAccess.PinResult.Blocked -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, ((result.retryAfterMs + 999) / 1000).toString())
                        call.respond(HttpStatusCode.TooManyRequests)
                    }

                    HttpAccess.PinResult.Wrong, null -> call.respond(HttpStatusCode.Forbidden)
                }
            }
            get("/jpeg") { respondMedia(call, listener, MediaFormat.Jpeg) }
            get("/mjpeg") { respondMedia(call, listener, MediaFormat.Mjpeg) }
            webSocket("/ws") {
                val requestJob = checkNotNull(call.coroutineContext[Job])
                val defaultSessionJob = checkNotNull(coroutineContext[Job])
                try {
                    if (!hasSameOrigin(call, listener, refererAllowed = false)) {
                        closeAndAwaitTermination(CloseReason.Codes.VIOLATED_POLICY, "Origin rejected")
                        return@webSocket
                    }
                    serveWebSocketState(this, call, listener)
                } finally {
                    // Cancel raw transport before joining Ktor's detached default-session job;
                    // request cancellation also prevents the upgrade's final flush from waiting indefinitely.
                    requestJob.cancel()
                    defaultSessionJob.cancel()
                    withContext(NonCancellable) { defaultSessionJob.join() }
                }
            }
        }
    }

    private suspend fun receivePin(call: ApplicationCall): String? {
        if (call.request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) return null
        val input = call.receiveChannel()
        // One extra byte detects an oversized body without reading an unbounded request.
        val bytes = ByteArray(MAX_PIN_BODY_BYTES + 1)
        var size = 0
        withTimeout(5.seconds) {
            while (size < bytes.size) {
                val count = input.readAvailable(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
        }
        if (size > MAX_PIN_BODY_BYTES) return null
        val fields = try {
            parseQueryString(bytes.decodeToString(endIndex = size))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return fields.getAll("pin")?.singleOrNull()
    }

    private fun setAccessCookie(call: ApplicationCall, accessCookie: String) {
        call.response.headers.append(HttpHeaders.SetCookie, "$COOKIE_NAME=$accessCookie; Path=/; HttpOnly; SameSite=Lax")
    }

    private suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat) {
        requestHandler.respondMedia(
            call = call,
            listener = listener,
            format = format,
            accessToken = call.request.queryParameters["accessToken"],
            accessCookie = call.request.cookies[COOKIE_NAME],
        )
    }

    private suspend fun serveWebSocketState(webSocket: DefaultWebSocketSession, call: ApplicationCall, listener: HttpListener) {
        val admission = requestHandler.admitWebSocket(
            listener = listener,
            accessCookie = call.request.cookies[COOKIE_NAME],
            peerIp = call.request.local.remoteHost,
            requestJob = checkNotNull(call.coroutineContext[Job]),
        )
        val generation = when (admission) {
            is WebSocketAdmission.Denied -> {
                val deniedState = buildJsonObject {
                    val retryAfterMs = admission.retryAfterMs
                    put("access", if (retryAfterMs == null) "pin_required" else "blocked")
                    if (retryAfterMs != null) put("retryAfterMs", retryAfterMs)
                }
                webSocket.sendStateSnapshot(deniedState.toString())
                webSocket.closeAndAwaitTermination(CloseReason.Codes.NORMAL, "Access required")
                return
            }

            is WebSocketAdmission.Accepted -> admission.generation
        }
        coroutineScope {
            launch {
                try {
                    if (webSocket.incoming.receiveCatching().isSuccess) {
                        webSocket.closeAndAwaitTermination(CloseReason.Codes.VIOLATED_POLICY, "Read-only connection")
                    }
                } finally {
                    // Incoming close/error also ends a collector waiting for the next state.
                    this@coroutineScope.cancel()
                }
            }
            requestHandler.webState.collect { state ->
                if (state == null || state.generation != generation) {
                    throw CancellationException("Viewing access unavailable")
                }
                val jsonState = state.encodeJson().toString()
                if (!requestHandler.isWebSocketCurrent(listener, generation)) {
                    throw CancellationException("Viewing access revoked")
                }
                webSocket.sendStateSnapshot(jsonState)
            }
        }
    }

    private suspend fun DefaultWebSocketSession.sendStateSnapshot(jsonState: String) {
        val frame = Frame.Text(jsonState)
        if (frame.data.size > 256 * 1024) {
            closeAndAwaitTermination(CloseReason.Codes.INTERNAL_ERROR, "Page settings exceed snapshot limit")
            throw CancellationException("Page settings exceed snapshot limit")
        }
        withTimeout(SEND_TIMEOUT) {
            send(frame)
            // Ktor's default-session flush reaches only the raw queue, not its intermediate outgoing queue.
            flush()
        }
    }

    private suspend fun DefaultWebSocketSession.closeAndAwaitTermination(code: CloseReason.Codes, reason: String) {
        // Capture before withTimeout introduces its own coroutine context.
        val defaultSessionJob = checkNotNull(coroutineContext[Job])
        withTimeout(SEND_TIMEOUT) {
            close(CloseReason(code, reason))
            defaultSessionJob.join()
        }
    }

    private fun hasSameOrigin(call: ApplicationCall, listener: HttpListener, refererAllowed: Boolean): Boolean {
        val expectedOrigin = trustedRequestOrigin(call, listener) ?: return false
        val originHeader = call.request.headers[HttpHeaders.Origin]
        val sourceUrl = when {
            originHeader != null -> originHeader
            refererAllowed -> call.request.headers[HttpHeaders.Referrer] ?: return false
            else -> return false
        }
        return parseOrigin(sourceUrl, allowPath = originHeader == null) == expectedOrigin
    }

    /** Direct CIO HTTP only; forwarded headers and arbitrary aliases never become trusted request authority. */
    private fun trustedRequestOrigin(call: ApplicationCall, listener: HttpListener): Origin? {
        val hostHeader = call.request.headers.getAll(HttpHeaders.Host)?.singleOrNull() ?: return null
        val requestOrigin = parseOrigin("http://$hostHeader", allowPath = false) ?: return null
        if (requestOrigin.port != listener.port) return null
        val listenerAddress = parseIpAddressBytes(listener.host.substringBefore('%')) ?: return null
        val requestAddress = parseIpAddressBytes(requestOrigin.host)
        val isLoopbackAlias = requestOrigin.host == "localhost" && InetAddress.getByName(listener.host).isLoopbackAddress
        return if (requestAddress == listenerAddress || isLoopbackAlias) requestOrigin else null
    }

    private fun parseOrigin(url: String, allowPath: Boolean): Origin? {
        try {
            val uri = URI(url)
            if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawFragment != null) return null
            if (!allowPath && (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null)) return null
            val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return null
            if (host != "localhost" && parseIpAddressBytes(host) == null) return null
            return Origin(host, if (uri.port == -1) 80 else uri.port)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: java.net.URISyntaxException) {
            return null
        }
    }

    private fun parseIpAddressBytes(host: String): List<Byte>? = try {
        val addressLiteral = host.removeSurrounding("[", "]")
        val isIpv6 = addressLiteral.contains(':') && addressLiteral.all { it in "0123456789abcdefABCDEF:." }
        val isIpv4 = addressLiteral.split('.').let { parts ->
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.all { digit -> digit in '0'..'9' } && (part.toIntOrNull() ?: 256) in 0..255
            }
        }
        if (isIpv6 || isIpv4) {
            InetAddress.getByName(addressLiteral).address.toList()
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    interface RequestHandler {
        val webState: StateFlow<WebState?>

        fun isListenerActive(listener: HttpListener): Boolean

        fun exchangeAccessToken(listener: HttpListener, accessToken: String?): SecretValue?

        fun verifyPin(listener: HttpListener, pin: String?, peerIp: String): HttpAccess.PinResult?

        suspend fun respondMedia(call: ApplicationCall, listener: HttpListener, format: MediaFormat, accessToken: String?, accessCookie: String?)

        fun admitWebSocket(listener: HttpListener, accessCookie: String?, peerIp: String, requestJob: Job): WebSocketAdmission

        fun isWebSocketCurrent(listener: HttpListener, generation: Long): Boolean
    }

    sealed interface WebSocketAdmission {
        data class Accepted(val generation: Long) : WebSocketAdmission
        data class Denied(val retryAfterMs: Long?) : WebSocketAdmission
    }

    private data class Origin(val host: String, val port: Int)

    private companion object {
        const val COOKIE_NAME = "screenstream_access"
        const val MAX_PIN_BODY_BYTES = 256
        val SEND_TIMEOUT = 5.seconds
    }
}
