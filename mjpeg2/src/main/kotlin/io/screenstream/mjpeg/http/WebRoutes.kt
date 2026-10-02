package io.screenstream.mjpeg.http

import android.content.Context
import android.os.SystemClock
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocketRaw
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.ChannelOverflow
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared routes and bounded per-connection writers; no capture or settings command capability. */
internal class WebRoutes(
    context: Context,
    private val gate: Any,
    private val access: HttpAccess,
    private val pages: WebClientSessions,
    private val presentation: WebPresentation,
    private val revision: StateFlow<Long>,
    private val active: (AddressServer) -> Boolean,
    private val imageKind: () -> HttpDelivery.ImageKind?,
    private val media: suspend (ApplicationCall, AddressServer, HttpDelivery.ViewerMethod) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val sockets = LinkedHashSet<SocketOwner>()
    private val asset: ByteArray by lazy { applicationContext.assets.open("mjpeg/index.html").use { it.readBytes() } }

    fun install(application: Application, server: AddressServer) {
        application.install(WebSockets) {
            maxFrameSize = MAX_INCOMING_FRAME_BYTES
            channels {
                incoming = bounded(capacity = INCOMING_QUEUE_CAPACITY, onOverflow = ChannelOverflow.CLOSE)
                outgoing = bounded(capacity = OUTGOING_QUEUE_CAPACITY, onOverflow = ChannelOverflow.SUSPEND)
            }
        }
        application.intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val tokenExchange = call.request.queryParameters.contains("t")
            call.response.headers.append("Referrer-Policy", if (tokenExchange) "no-referrer" else "same-origin")
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            if (authority(call, server) == null || !synchronized(gate) { active(server) }) {
                call.respond(HttpStatusCode.Forbidden)
                finish()
            }
        }
        application.routing {
            get("/") {
                if (call.request.queryParameters.contains("t")) {
                    val credential = synchronized(gate) { if (active(server)) access.exchangeToken(call.request.queryParameters["t"]) else null }
                    credential?.let { setCookie(call, it.value) }
                    call.response.headers.append(HttpHeaders.Location, "/")
                    call.respond(HttpStatusCode.SeeOther)
                } else call.respondBytes(asset, ContentType.Text.Html)
            }
            post("/pin") {
                if (!sameOrigin(call, server, refererAllowed = true)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val pin = receivePin(call)
                val result = synchronized(gate) {
                    if (active(server)) access.verifyPin(pin, call.request.local.remoteHost, SystemClock.elapsedRealtime()) else null
                }
                when (result) {
                    is HttpAccess.PinResult.Authorized -> {
                        result.cookie?.let { setCookie(call, it.value) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                    is HttpAccess.PinResult.Blocked -> {
                        call.response.headers.append(HttpHeaders.RetryAfter, ((result.retryAfterMs + 999) / 1000).toString())
                        call.respond(HttpStatusCode.TooManyRequests)
                    }
                    else -> call.respond(HttpStatusCode.Forbidden)
                }
            }
            get("/jpeg") { media(call, server, HttpDelivery.ViewerMethod.Jpeg) }
            get("/mjpeg") { media(call, server, HttpDelivery.ViewerMethod.Mjpeg) }
            webSocketRaw("/ws") {
                try {
                    if (!sameOrigin(call, server, refererAllowed = false)) {
                        sendAndClose(CloseReason.Codes.VIOLATED_POLICY, "Origin rejected")
                        return@webSocketRaw
                    }
                    serveState(this, call, server)
                } finally {
                    // Upgrade performs a final flush after normal return: cancel first on every exit.
                    cancel()
                }
            }
        }
    }

    /** Caller revokes generations under the shared gate and cancels these jobs outside it. */
    fun ownedJobs(): List<Job> = sockets.map { it.job }

    private suspend fun serveState(session: WebSocketSession, call: ApplicationCall, server: AddressServer) {
        val job = checkNotNull(session.coroutineContext[Job])
        val owner = synchronized(gate) {
            if (!active(server) || !access.admits(null, cookie(call))) null
            else SocketOwner(job, server, access.generation,
                pages.connectPage(call.request.queryParameters["s"], access.generation, SystemClock.elapsedRealtime())).also { sockets.add(it) }
        }
        if (owner == null) {
            val denied = synchronized(gate) { access.deniedState(call.request.local.remoteHost, SystemClock.elapsedRealtime()) }
            val state = buildJsonObject {
                when (denied) {
                    HttpAccess.DeniedState.PinRequired -> put("access", "pin_required")
                    is HttpAccess.DeniedState.Blocked -> { put("access", "blocked"); put("retryAfterMs", denied.retryAfterMs) }
                }
            }.toString()
            withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) { session.send(Frame.Text(state)); session.flush() }
            session.sendAndClose(CloseReason.Codes.NORMAL, "Access required")
            return
        }
        try {
            coroutineScope {
                val conversation = this
                val output = Mutex()
                val pongs = Channel<ByteArray>(Channel.CONFLATED)
                launch {
                    try {
                        for (frame in session.incoming) {
                            when (frame) {
                                is Frame.Pong -> pongs.trySend(frame.data)
                                is Frame.Ping -> withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
                                    output.withLock { session.send(Frame.Pong(frame.data)); session.flush() }
                                }
                                is Frame.Close -> {
                                    withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
                                        output.withLock { session.send(frame); session.flush() }
                                    }
                                    return@launch
                                }
                                else -> {
                                    withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
                                        output.withLock { session.sendAndClose(CloseReason.Codes.VIOLATED_POLICY, "Read-only connection") }
                                    }
                                    return@launch
                                }
                            }
                        }
                    } finally {
                        // Child timeout is cancellation, not a failure propagated by coroutineScope.
                        conversation.cancel()
                    }
                }
                launch {
                    try {
                        var pingSequence = 0L
                        while (true) {
                            delay(PING_INTERVAL_MILLIS.milliseconds)
                            while (pongs.tryReceive().isSuccess) { /* Discard replies to an earlier ping. */ }
                            val ping = ByteBuffer.allocate(PING_PAYLOAD_BYTES).putLong(++pingSequence).array()
                            withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
                                output.withLock { session.send(Frame.Ping(ping)); session.flush() }
                            }
                            withTimeout(PONG_TIMEOUT_MILLIS.milliseconds) {
                                while (!pongs.receive().contentEquals(ping)) { /* Only this ping satisfies its deadline. */ }
                            }
                        }
                    } finally {
                        conversation.cancel()
                    }
                }
                revision.collect {
                    val snapshot = synchronized(gate) {
                        if (!active(server) || owner.generation != access.generation) throw CancellationException("Page access revoked")
                        presentation.snapshot(!access.pinEnabled, owner.page.id, imageKind())
                    }.toString()
                    if (snapshot.toByteArray(Charsets.UTF_8).size > MAX_OUTGOING_SNAPSHOT_BYTES) {
                        withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
                            output.withLock { session.sendAndClose(CloseReason.Codes.INTERNAL_ERROR, "Page settings exceed snapshot limit") }
                        }
                        conversation.cancel()
                        return@collect
                    }
                    withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) { output.withLock { session.send(Frame.Text(snapshot)); session.flush() } }
                }
            }
        } finally {
            synchronized(gate) {
                pages.pageDisconnected(owner.page, SystemClock.elapsedRealtime())
                sockets.remove(owner)
            }
            // Cancellation is prompt; physical completion remains owned by its transport tree.
            session.cancel()
        }
    }

    private suspend fun WebSocketSession.sendAndClose(code: CloseReason.Codes, reason: String) {
        withTimeout(SEND_TIMEOUT_MILLIS.milliseconds) {
            send(Frame.Close(CloseReason(code, reason)))
            flush()
        }
    }

    private suspend fun receivePin(call: ApplicationCall): String? {
        if (call.request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) return null
        val input = call.receiveChannel()
        val bytes = ByteArray(MAX_PIN_BODY_BYTES + 1)
        var size = 0
        withTimeout(PIN_BODY_TIMEOUT_MILLIS.milliseconds) {
            while (size < bytes.size) {
                val count = input.readAvailable(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
        }
        if (size > MAX_PIN_BODY_BYTES) return null
        val fields = try { parseQueryString(bytes.decodeToString(endIndex = size)) } catch (_: IllegalArgumentException) { return null }
        return fields.getAll("pin")?.singleOrNull()
    }

    private fun setCookie(call: ApplicationCall, credential: String) {
        call.response.headers.append(HttpHeaders.SetCookie, "$COOKIE_NAME=$credential; Path=/; HttpOnly; SameSite=Lax")
    }

    private fun sameOrigin(call: ApplicationCall, server: AddressServer, refererAllowed: Boolean): Boolean {
        val expected = authority(call, server) ?: return false
        val origin = call.request.headers[HttpHeaders.Origin]
        val raw = origin ?: if (refererAllowed) call.request.headers[HttpHeaders.Referrer] else null
        val parsed = raw?.let { parseOrigin(it, allowPath = origin == null) } ?: return false
        return parsed == expected
    }

    /** Direct CIO HTTP only; forwarded headers and arbitrary aliases never become trusted authority. */
    private fun authority(call: ApplicationCall, server: AddressServer): Origin? {
        val host = call.request.headers.getAll(HttpHeaders.Host)?.singleOrNull() ?: return null
        val origin = parseOrigin("http://$host", allowPath = false) ?: return null
        if (origin.port != server.port) return null
        val bound = numericHost(server.host.substringBefore('%')) ?: return null
        val supplied = numericHost(origin.host)
        return if (supplied == bound || origin.host == "localhost" && InetAddress.getByName(server.host).isLoopbackAddress) origin else null
    }

    private fun parseOrigin(raw: String, allowPath: Boolean): Origin? = try {
        val uri = URI(raw)
        if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawFragment != null ||
            (!allowPath && (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null))) null
        else uri.host?.lowercase()?.removeSurrounding("[", "]")?.let { host ->
            if (host == "localhost" || numericHost(host) != null) Origin(host, if (uri.port == -1) HTTP_DEFAULT_PORT else uri.port) else null
        }
    } catch (_: IllegalArgumentException) { null } catch (_: java.net.URISyntaxException) { null }

    private fun numericHost(host: String): List<Byte>? = try {
        val value = host.removeSurrounding("[", "]")
        if (value.contains(':') && value.all { it in "0123456789abcdefABCDEF:." } ||
            value.split('.').let { parts -> parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { digit -> digit in '0'..'9' } && (part.toIntOrNull() ?: 256) in 0..255 } }) {
            InetAddress.getByName(value).address.toList()
        } else null
    } catch (_: Exception) { null }

    private data class Origin(val host: String, val port: Int)
    private class SocketOwner(val job: Job, val server: AddressServer, val generation: Long, val page: WebClientSessions.Page)

    internal companion object {
        const val COOKIE_NAME = "screenstream_access"
        fun cookie(call: ApplicationCall): String? = call.request.cookies[COOKIE_NAME]
        private const val HTTP_DEFAULT_PORT = 80
        private const val MAX_PIN_BODY_BYTES = 256
        private const val PIN_BODY_TIMEOUT_MILLIS = 5_000L
        private const val MAX_INCOMING_FRAME_BYTES = 4_096L
        private const val MAX_OUTGOING_SNAPSHOT_BYTES = 256 * 1024
        private const val INCOMING_QUEUE_CAPACITY = 4
        private const val OUTGOING_QUEUE_CAPACITY = 8
        private const val SEND_TIMEOUT_MILLIS = 5_000L
        private const val PING_PAYLOAD_BYTES = 8
        private const val PING_INTERVAL_MILLIS = 15_000L
        private const val PONG_TIMEOUT_MILLIS = 15_000L
    }
}
