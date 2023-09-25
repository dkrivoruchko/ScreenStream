package info.dvkr.screenstream.mjpeg.internal.server

import android.annotation.SuppressLint
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.internal.MjpegError
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.internal.server.ClientData.Companion.clientId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun Application.appModule(
    httpServerFiles: HttpServerFiles,
    clientData: ClientData,
    mjpegSharedFlow: SharedFlow<ByteArray>,
    lastJPEG: AtomicReference<ByteArray>,
    blockedJPEG: ByteArray,
    stopDeferred: AtomicReference<CompletableDeferred<Unit>?>,
    sendEvent: (MjpegEvent) -> Unit
) {

    val crlf = "\r\n".toByteArray()
    val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
    val multipartBoundary = randomString(20)
    val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$multipartBoundary")
    val jpegBoundary = "--$multipartBoundary\r\n".toByteArray()

    suspend fun writeMJPEGFrame(channel: ByteWriteChannel, jpeg: ByteArray): Int {
        if (channel.isClosedForWrite) return 0
        channel.writeFully(jpegBaseHeader, 0, jpegBaseHeader.size)
        val jpegSizeText = jpeg.size.toString().toByteArray()
        channel.writeFully(jpegSizeText, 0, jpegSizeText.size)
        channel.writeFully(crlf, 0, crlf.size)
        channel.writeFully(crlf, 0, crlf.size)
        channel.writeFully(jpeg, 0, jpeg.size)
        channel.writeFully(crlf, 0, crlf.size)
        channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
        return jpegBaseHeader.size + jpegSizeText.size + crlf.size * 3 + jpeg.size + jpegBoundary.size
    }

    environment.monitor.subscribe(ApplicationStarted) {
        XLog.i(getLog("monitor", "KtorApplicationStarted: ${it.hashCode()}"))
    }

    environment.monitor.subscribe(ApplicationStopped) {
        XLog.i(getLog("monitor", "KtorApplicationStopped: ${it.hashCode()}"))
        it.environment.parentCoroutineContext.cancel()
        clientData.clearStatistics()
        stopDeferred.getAndSet(null)?.complete(Unit)
    }

    install(DefaultHeaders) { header(HttpHeaders.CacheControl, "no-cache") }
    install(CORS) {
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        anyHost()
    }
    install(ForwardedHeaders)

    install(WebSockets) {
        @SuppressLint("NewApi")
        pingPeriod = Duration.ofSeconds(1)
        @SuppressLint("NewApi")
        timeout = Duration.ofSeconds(2)
    }
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondRedirect(HttpServerFiles.ROOT_ADDRESS, permanent = true)
        }
        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respondRedirect(HttpServerFiles.CLIENT_BLOCKED_ADDRESS)
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respondRedirect(HttpServerFiles.PIN_REQUEST_ADDRESS)
        }
        exception<Throwable> { call, cause ->
            if (cause is IOException) return@exception
            if (cause is CancellationException) return@exception
            if (cause is IllegalArgumentException) return@exception
            XLog.e(this@appModule.getLog("exception<Throwable>", cause.toString()))
            XLog.e(this@appModule.getLog("exception"), cause)
            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    routing {
        webSocket("/monitor") {
            runCatching { for (frame in incoming) Unit }
        }
        route(HttpServerFiles.ROOT_ADDRESS) {
            handle {
                when {
                    clientData.enablePin.not() -> call.respondText(httpServerFiles.indexHtml, ContentType.Text.Html)
                    clientData.isAddressBlocked(call.request.origin) -> call.respond(HttpStatusCode.Forbidden)
                    clientData.isClientAuthorized(call.request.origin) -> call.respondText(httpServerFiles.indexHtml, ContentType.Text.Html)
                    else -> call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }

        get(HttpServerFiles.PIN_REQUEST_ADDRESS) {
            when {
                clientData.enablePin.not() -> call.respond(HttpStatusCode.NotFound)
                clientData.isAddressBlocked(call.request.origin) -> call.respond(HttpStatusCode.Forbidden)
                else -> {
                    clientData.onConnected(call.request.origin)
                    when (call.request.queryParameters[HttpServerFiles.PIN_PARAMETER]) {
                        httpServerFiles.pin -> {
                            clientData.onPinCheck(call.request.origin, true)
                            call.respondRedirect(HttpServerFiles.ROOT_ADDRESS)
                        }

                        null -> call.respondText(httpServerFiles.pinRequestHtml, ContentType.Text.Html)
                        else -> {
                            clientData.onPinCheck(call.request.origin, false)
                            when {
                                clientData.isClientBlocked(call.request.origin) -> call.respond(HttpStatusCode.Forbidden)
                                else -> call.respondText(httpServerFiles.pinRequestErrorHtml, ContentType.Text.Html)
                            }
                        }
                    }
                }
            }
        }

        get(HttpServerFiles.CLIENT_BLOCKED_ADDRESS) {
            if (clientData.enablePin && clientData.blockAddress) {
                call.respondText(httpServerFiles.addressBlockedHtml, ContentType.Text.Html)
            } else {
                call.respond(HttpStatusCode.NotFound)
            }
        }

        get(httpServerFiles.streamAddress) {
            if (clientData.isClientAllowed(call.request.origin).not()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val status: HttpStatusCode = HttpStatusCode.OK

                override val contentType: ContentType = contentType

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    val emmitCounter = AtomicLong(0L)
                    val collectCounter = AtomicLong(0L)
                    val clientId = call.request.origin.clientId

                    mjpegSharedFlow
                        .onStart {
                            XLog.d(this@appModule.getLog("onStart", "Client: $clientId"))
                            clientData.onConnected(call.request.origin)
                        }
                        .onCompletion {
                            XLog.d(this@appModule.getLog("onCompletion", "Client: $clientId"))
                            clientData.onDisconnected(call.request.origin)
                        }
                        .map { Pair(emmitCounter.incrementAndGet(), it) }
                        .conflate()
                        .onEach { (emmitCounter, jpeg) ->
                            if (channel.isClosedForWrite) {
                                XLog.d(this@appModule.getLog("onEach", "IsClosedForWrite: Client: $clientId"))
                                coroutineContext.cancel()
                                return@onEach
                            }

                            if (emmitCounter - collectCounter.incrementAndGet() >= 5) {
                                XLog.i(this@appModule.getLog("onEach", "Slow connection. Client: $clientId"))
                                collectCounter.set(emmitCounter)
                                clientData.onSlowConnection(call.request.origin)
                            }

                            val totalSize = if (clientData.isClientAllowed(call.request.origin)) {
                                writeMJPEGFrame(channel, jpeg)
                            } else {
                                writeMJPEGFrame(channel, blockedJPEG)
                            }

                            clientData.onNextBytes(call.request.origin, totalSize)
                        }
                        .catch { /* Empty intentionally */ }
                        .collect()
                }
            })
        }

        get(httpServerFiles.jpegFallbackAddress) {
            if (clientData.isAddressBlocked(call.request.origin)) {
                call.respondBytes(blockedJPEG, ContentType.Image.JPEG)
            } else {
                call.respondBytes(lastJPEG.get(), ContentType.Image.JPEG)
            }
        }

        get(HttpServerFiles.START_STOP_ADDRESS) {
            if (httpServerFiles.htmlEnableButtons && clientData.enablePin.not())
                sendEvent(MjpegStreamingService.InternalEvent.StartStopFromWebPage)
            call.respondText("")
        }

        get(HttpServerFiles.FAVICON_PNG) {
            call.respondBytes(httpServerFiles.faviconPng, ContentType.Image.PNG)
        }
        get(HttpServerFiles.LOGO_PNG) {
            call.respondBytes(httpServerFiles.logoPng, ContentType.Image.PNG)
        }
        get(HttpServerFiles.FULLSCREEN_ON_PNG) {
            call.respondBytes(httpServerFiles.fullscreenOnPng, ContentType.Image.PNG)
        }
        get(HttpServerFiles.FULLSCREEN_OFF_PNG) {
            call.respondBytes(httpServerFiles.fullscreenOffPng, ContentType.Image.PNG)
        }
        get(HttpServerFiles.START_STOP_PNG) {
            call.respondBytes(httpServerFiles.startStopPng, ContentType.Image.PNG)
        }
    }
}