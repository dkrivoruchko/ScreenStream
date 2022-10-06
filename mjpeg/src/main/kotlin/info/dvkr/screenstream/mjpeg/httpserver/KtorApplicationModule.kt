package info.dvkr.screenstream.mjpeg.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.HttpServerException
import info.dvkr.screenstream.mjpeg.randomString
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun Application.appModule(
    httpServerFiles: HttpServerFiles,
    clientData: ClientData,
    mjpegSharedFlow: SharedFlow<ByteArray>,
    lastJPEG: AtomicReference<ByteArray>,
    blockedJPEG: ByteArray,
    stopDeferred: AtomicReference<CompletableDeferred<Unit>?>,
    sendEvent: (HttpServer.Event) -> Unit
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
        channel.flush()
        return jpegBaseHeader.size + jpegSizeText.size + crlf.size * 3 + jpeg.size + jpegBoundary.size
    }

    environment.monitor.subscribe(ApplicationStarted) {
        XLog.i(getLog("monitor", "KtorApplicationStarted: ${hashCode()}"))
    }

    environment.monitor.subscribe(ApplicationStopped) {
        XLog.i(getLog("monitor", "KtorApplicationStopped: ${hashCode()}"))
        it.environment.parentCoroutineContext.cancel()
        clientData.clearStatistics()
        stopDeferred.getAndSet(null)?.complete(Unit)
    }

    install(DefaultHeaders) { header(HttpHeaders.CacheControl, "no-cache") }
    install(CORS) {
        allowHeader(HttpHeaders.AccessControlAllowOrigin)
        anyHost()
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
            sendEvent(HttpServer.Event.Error(HttpServerException))
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    routing {
        route(HttpServerFiles.ROOT_ADDRESS) {

            handle {
                if (clientData.enablePin.not()) {
                    call.respondText(httpServerFiles.indexHtml, ContentType.Text.Html)
                } else {
                    val ipAddress: InetSocketAddress? = ClientAddressWorkAround.getInetSocketAddress(call.request)
                    val fallbackHost = call.request.local.remoteHost
                    val clientId = ClientData.getId(ipAddress, fallbackHost)

                    if (clientData.isAddressBlocked(ipAddress, fallbackHost)) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        if (clientData.isClientAuthorized(clientId)) {
                            call.respondText(httpServerFiles.indexHtml, ContentType.Text.Html)
                        } else {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                }
            }

            get(HttpServerFiles.PIN_REQUEST_ADDRESS) {
                if (clientData.enablePin.not()) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val ipAddress: InetSocketAddress? = ClientAddressWorkAround.getInetSocketAddress(call.request)
                    val fallbackHost = call.request.local.remoteHost
                    val clientId = ClientData.getId(ipAddress, fallbackHost)

                    if (clientData.isAddressBlocked(ipAddress, fallbackHost)) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        clientData.onConnected(clientId, ipAddress, fallbackHost)

                        when (call.request.queryParameters[HttpServerFiles.PIN_PARAMETER]) {
                            httpServerFiles.pin -> {
                                clientData.onPinCheck(clientId, true)
                                call.respondText(httpServerFiles.indexHtml, ContentType.Text.Html)
                            }
                            null -> {
                                call.respondText(httpServerFiles.pinRequestHtml, ContentType.Text.Html)
                            }
                            else -> {
                                clientData.onPinCheck(clientId, false)

                                if (clientData.isClientBlocked(clientId)) {
                                    call.respond(HttpStatusCode.Forbidden)
                                } else {
                                    call.respondText(httpServerFiles.pinRequestErrorHtml, ContentType.Text.Html)
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
                val ipAddress: InetSocketAddress? = ClientAddressWorkAround.getInetSocketAddress(call.request)
                val fallbackHost = call.request.local.remoteHost
                val clientId = ClientData.getId(ipAddress, fallbackHost)

                if (clientData.isClientAllowed(clientId, ipAddress, fallbackHost).not()) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.OK

                    override val contentType: ContentType = contentType

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val emmitCounter = AtomicLong(0L)
                        val collectCounter = AtomicLong(0L)

                        mjpegSharedFlow
                            .onStart {
                                XLog.d(this@appModule.getLog("onStart", "Client: $clientId"))
                                clientData.onConnected(clientId, ipAddress, fallbackHost)

                                channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                                val totalSize = writeMJPEGFrame(channel, lastJPEG.get())
                                clientData.onNextBytes(clientId, totalSize)
                            }
                            .onCompletion {
                                XLog.d(this@appModule.getLog("onCompletion", "Client: $clientId"))
                                clientData.onDisconnected(clientId)
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
                                    clientData.onSlowConnection(clientId)
                                }

                                val totalSize = if (clientData.isClientAllowed(clientId, ipAddress, fallbackHost)) {
                                    writeMJPEGFrame(channel, jpeg)
                                } else {
                                    writeMJPEGFrame(channel, blockedJPEG)
                                }

                                clientData.onNextBytes(clientId, totalSize)
                            }
                            .catch { /* Empty intentionally */ }
                            .collect()
                    }
                })
            }

            get(httpServerFiles.jpegFallbackAddress) {
                val ipAddress: InetSocketAddress? = ClientAddressWorkAround.getInetSocketAddress(call.request)

                if (clientData.isAddressBlocked(ipAddress, call.request.local.remoteHost)) {
                    call.respondBytes(blockedJPEG, ContentType.Image.JPEG)
                } else {
                    call.respondBytes(lastJPEG.get(), ContentType.Image.JPEG)
                }
            }

            get(HttpServerFiles.START_STOP_ADDRESS) {
                if (httpServerFiles.htmlEnableButtons && clientData.enablePin.not())
                    sendEvent(HttpServer.Event.Action.StartStopRequest)

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
}