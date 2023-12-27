package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getAppVersion
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.internal.HttpServerData.Companion.getClientId
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class HttpServer(
    context: Context,
    private val mjpegSettings: MjpegSettings,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val sendEvent: (MjpegEvent) -> Unit
) {
    private val favicon: ByteArray = context.getFileFromAssets("favicon.ico")
    private val logoPng: ByteArray = context.getFileFromAssets("logo.png")
    private val baseIndexHtml = String(context.getFileFromAssets("index.html"), StandardCharsets.UTF_8)
        .replace("%CONNECTING%", context.getString(R.string.mjpeg_html_stream_connecting))
        .replace("%STREAM_REQUIRE_PIN%", context.getString(R.string.mjpeg_html_stream_require_pin))
        .replace("%ENTER_PIN%", context.getString(R.string.mjpeg_html_enter_pin))
        .replace("%SUBMIT_PIN%", context.getString(R.string.mjpeg_html_submit_pin))
        .replace("%WRONG_PIN_MESSAGE%", context.getString(R.string.mjpeg_html_wrong_pin))
        .replace("%ADDRESS_BLOCKED%", context.getString(R.string.mjpeg_html_address_blocked))
        .replace("%ERROR%", context.getString(R.string.mjpeg_html_error_unspecified)) //TODO not used
        .replace("%DD_SERVICE%", if (BuildConfig.DEBUG) "mjpeg_client:dev" else "mjpeg_client:prod")
        .replace("DD_HANDLER", if (BuildConfig.DEBUG) "[\"http\", \"console\"]" else "[\"http\"]")
        .replace("%APP_VERSION%", context.getAppVersion())

    private val indexHtml: AtomicReference<String> = AtomicReference("")
    private val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))
    private val serverData: HttpServerData = HttpServerData(sendEvent)
    private val ktorServer: AtomicReference<Pair<CIOApplicationEngine, CompletableDeferred<Unit>>> = AtomicReference(null)

    init {
        XLog.d(getLog("init"))
    }

    internal suspend fun start(serverAddresses: List<MjpegState.NetInterface>) {
        XLog.d(getLog("startServer"))

        serverData.configure(mjpegSettings)

        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default)

        mjpegSettings.htmlBackColorFlow.onEach { htmlBackColor ->
            indexHtml.set(baseIndexHtml.replace("BACKGROUND_COLOR", "#%06X".format(0xFFFFFF and htmlBackColor)))
        }.launchIn(coroutineScope)

        mjpegSettings.htmlEnableButtonsFlow.combineTransform(mjpegSettings.htmlBackColorFlow) { htmlEnableButtons, htmlBackColor ->
            emit(Pair(htmlEnableButtons && serverData.enablePin.not(), "#%06X".format(0xFFFFFF and htmlBackColor)))
        }.distinctUntilChanged().onEach { (enableButtons, backColor) ->
            serverData.notifyClients("SETTINGS", JSONObject().put("enableButtons", enableButtons).put("backColor", backColor))
        }.launchIn(coroutineScope)

        val resultJpegStream = ByteArrayOutputStream()
        lastJPEG.set(ByteArray(0))

        @OptIn(ExperimentalCoroutinesApi::class)
        val mjpegSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, mjpegSettings.jpegQualityFlow.first(), resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .filter { it.isNotEmpty() }
            .onEach { jpeg -> lastJPEG.set(jpeg) }
            .flatMapLatest { jpeg ->
                flow<ByteArray> { // Send last image every second as keep-alive
                    while (currentCoroutineContext().isActive) {
                        emit(jpeg)
                        delay(1000)
                    }
                }
            }
            .conflate()
            .shareIn(coroutineScope, SharingStarted.Eagerly, 1)

        val serverPort = mjpegSettings.serverPortFlow.first()
        val server = embeddedServer(CIO, applicationEngineEnvironment {
            parentCoroutineContext = CoroutineExceptionHandler { _, throwable ->
                XLog.e(this@HttpServer.getLog("parentCoroutineContext", "coroutineExceptionHandler: $throwable"), throwable)
            }
            module { appModule(mjpegSharedFlow) }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress!!
                    port = serverPort
                }
            }
        }) {
            connectionIdleTimeoutSeconds = 10
            shutdownGracePeriod = 0
            shutdownTimeout = 500
        }

        ktorServer.set(server to CompletableDeferred())

        server.environment.monitor.subscribe(ApplicationStarted) {
            XLog.i(getLog("monitor", "KtorStarted: ${it.hashCode()}"))
        }

        server.environment.monitor.subscribe(ApplicationStopped) {
            XLog.i(getLog("monitor", "KtorStopped: ${it.hashCode()}"))
            ktorServer.get().second.complete(Unit)
            coroutineScope.cancel()
            serverData.clear()
        }

        try {
            server.start(false)
        } catch (cause: CancellationException) {
            if (cause.cause is SocketException) {
                XLog.w(getLog("startServer.CancellationException.SocketException", cause.cause.toString()))
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException))
            } else {
                XLog.w(getLog("startServer.CancellationException", cause.toString()), cause)
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
            }
        } catch (cause: BindException) {
            XLog.w(getLog("startServer.BindException", cause.toString()))
            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException))
        } catch (cause: Throwable) {
            XLog.e(getLog("startServer.Throwable"), cause)
            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
        }
        XLog.d(getLog("startServer", "Done. Ktor: ${server.appHashCode()} "))
    }

    internal suspend fun stop(reloadClients: Boolean) = coroutineScope {
        XLog.d(getLog("stopServer", "reloadClients: $reloadClients"))
        launch(Dispatchers.Default) {
            ktorServer.getAndSet(null)?.let { (server, stopJob) ->
                if (stopJob.isActive) {
                    if (reloadClients) serverData.notifyClients("RELOAD", timeout = 250)
                    val hashCode = server.appHashCode()
                    XLog.i(this@HttpServer.getLog("stopServer", "Ktor: $hashCode"))
                    server.stop(250, 500)
                    XLog.i(this@HttpServer.getLog("stopServer", "Done. Ktor: $hashCode"))
                }
            }
            XLog.d(this@HttpServer.getLog("stopServer", "Done"))
        }
    }

    internal suspend fun destroy() {
        XLog.d(getLog("destroy"))
        serverData.destroy()
        stop(false)
    }

    private fun CIOApplicationEngine.appHashCode(): Int = runCatching { application.hashCode() }.getOrDefault(0)

    private suspend fun DefaultWebSocketSession.send(type: String, data: Any?) {
        if (isActive) send(JSONObject().put("type", type).apply { if (data != null) put("data", data) }.toString())
    }

    private fun Application.appModule(mjpegSharedFlow: SharedFlow<ByteArray>) {
        val crlf = "\r\n".toByteArray()
        val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
        val multipartBoundary = randomString(20)
        val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$multipartBoundary")
        val jpegBoundary = "--$multipartBoundary\r\n".toByteArray()

        install(Compression) {
            gzip()
            deflate()
        }
        install(CachingHeaders) { options { _, _ -> CachingOptions(CacheControl.NoStore(CacheControl.Visibility.Private)) } }
        install(DefaultHeaders) { header(HttpHeaders.AccessControlAllowOrigin, "*") }
        install(ForwardedHeaders)
        install(WebSockets)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is IOException || cause is IllegalArgumentException || cause is IllegalStateException) return@exception
                XLog.e(this@appModule.getLog("exception"), RuntimeException("Throwable", cause))
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
                call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") { call.respondText(indexHtml.get(), ContentType.Text.Html) }
            get("favicon.ico") { call.respondBytes(favicon, ContentType.Image.XIcon) }
            get("logo.png") { call.respondBytes(logoPng, ContentType.Image.PNG) }
            get("start-stop") {
                if (mjpegSettings.htmlEnableButtonsFlow.first() && serverData.enablePin.not())
                    sendEvent(MjpegStreamingService.InternalEvent.StartStopFromWebPage)
                call.respond(HttpStatusCode.NoContent)
            }
            get(serverData.jpegFallbackAddress) {
                if (serverData.isAddressBlocked(call.request.origin.remoteAddress)) call.respond(HttpStatusCode.Forbidden)
                else {
                    val clientId = call.request.queryParameters["clientId"] ?: "-"
                    val remoteAddress = call.request.origin.remoteAddress
                    val remotePort = call.request.origin.remotePort
                    serverData.addConnected(clientId, remoteAddress, remotePort)
                    val bytes = lastJPEG.get()
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                    serverData.setNextBytes(clientId, remoteAddress, remotePort, bytes.size)
                    serverData.setDisconnected(clientId, remoteAddress, remotePort)
                }
            }

            webSocket("/socket") {
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                serverData.addClient(clientId, this)

                try {
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                        val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue

                        val enableButtons = mjpegSettings.htmlEnableButtonsFlow.first() && serverData.enablePin.not()
                        val streamData = JSONObject().put("enableButtons", enableButtons).put("streamAddress", serverData.streamAddress)

                        when (val type = msg.optString("type").uppercase()) {
                            "HEARTBEAT" -> send("HEARTBEAT", msg.optString("data"))

                            "CONNECT" -> when {
                                mjpegSettings.enablePinFlow.first().not() -> send("STREAM_ADDRESS", streamData)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                serverData.isClientAuthorized(clientId) -> send("STREAM_ADDRESS", streamData)
                                else -> send("UNAUTHORIZED", null)
                            }

                            "PIN" -> when {
                                serverData.isPinValid(clientId, remoteAddress, msg.optString("data")) -> send("STREAM_ADDRESS", streamData)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                else -> send("UNAUTHORIZED", "WRONG_PIN")
                            }

                            else -> {
                                val m = "Unknown message type: $type"
                                XLog.e(this@appModule.getLog("socket", m), IllegalArgumentException(m))
                            }
                        }
                    }
                } catch (ignore: CancellationException) {
                } catch (cause: Exception) {
                    XLog.w(this@appModule.getLog("socket", "catch: ${cause.localizedMessage}"), cause)
                } finally {
                    XLog.i(this@appModule.getLog("socket", "finally: $clientId"))
                    serverData.removeSocket(clientId)
                }
            }

            get(serverData.streamAddress) {
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                val remotePort = call.request.origin.remotePort

                if (serverData.isClientAllowed(clientId, remoteAddress).not()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }

                fun stopClientStream(channel: ByteWriteChannel) = channel.isClosedForWrite || serverData.isAddressBlocked(remoteAddress) ||
                        serverData.isDisconnected(clientId, remoteAddress, remotePort)

                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val status: HttpStatusCode = HttpStatusCode.OK

                    override val contentType: ContentType = contentType

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        val emmitCounter = AtomicLong(0L)
                        val collectCounter = AtomicLong(0L)

                        mjpegSharedFlow
                            .onStart {
                                XLog.i(this@appModule.getLog("onStart", "Client: $clientId:$remotePort"))
                                serverData.addConnected(clientId, remoteAddress, remotePort)
                                channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                            }
                            .onCompletion {
                                XLog.i(this@appModule.getLog("onCompletion", "Client: $clientId:$remotePort"))
                                serverData.setDisconnected(clientId, remoteAddress, remotePort)
                            }
                            .takeWhile { stopClientStream(channel).not() }
                            .map { Pair(emmitCounter.incrementAndGet(), it) }
                            .conflate()
                            .onEach { (emmitCounter, jpeg) ->
                                if (stopClientStream(channel)) return@onEach

                                if (emmitCounter - collectCounter.incrementAndGet() >= 5) {
                                    XLog.i(this@appModule.getLog("onEach", "Slow connection. Client: $clientId"))
                                    collectCounter.set(emmitCounter)
                                    serverData.setSlowConnection(clientId, remoteAddress, remotePort)
                                }

                                // Write MJPEG frame
                                val jpegSizeText = jpeg.size.toString().toByteArray()
                                channel.writeFully(jpegBaseHeader, 0, jpegBaseHeader.size)
                                channel.writeFully(jpegSizeText, 0, jpegSizeText.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(jpeg, 0, jpeg.size)
                                channel.writeFully(crlf, 0, crlf.size)
                                channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                                // Write MJPEG frame

                                val size = jpegBaseHeader.size + jpegSizeText.size + crlf.size * 3 + jpeg.size + jpegBoundary.size
                                serverData.setNextBytes(clientId, remoteAddress, remotePort, size)
                            }
                            .catch { /* Empty intentionally */ }
                            .collect()
                    }
                })
            }
        }
    }
}