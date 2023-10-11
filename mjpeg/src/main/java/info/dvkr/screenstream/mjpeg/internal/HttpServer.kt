package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getAppVersion
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.internal.HttpServerData.Companion.getClientId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.CORS
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
    private val scriptJs: ByteArray = context.getFileFromAssets("script.js")
    private val baseIndexHtml = String(context.getFileFromAssets("index.html"), StandardCharsets.UTF_8)
        .replace("%CONNECTING%", context.getString(R.string.mjpeg_html_stream_connecting))
        .replace("%STREAM_REQUIRE_PIN%", context.getString(R.string.mjpeg_html_stream_require_pin))
        .replace("%ENTER_PIN%", context.getString(R.string.mjpeg_html_enter_pin))
        .replace("%SUBMIT_PIN%", context.getString(R.string.mjpeg_html_submit_pin))
        .replace("%WRONG_PIN_MESSAGE%", context.getString(R.string.mjpeg_html_wrong_pin))
        .replace("%ADDRESS_BLOCKED%", context.getString(R.string.mjpeg_html_address_blocked))
        .replace("%ERROR%", context.getString(R.string.mjpeg_html_error_unspecified))
        .replace("%DD_SERVICE%", if (BuildConfig.DEBUG) "MJPEG_Client-DEV" else "MJPEG_Client-PROD")
        .replace("DD_HANDLER", if (BuildConfig.DEBUG) "[\"http\", \"console\"]" else "[\"http\"]")
        .replace("%APP_VERSION%", context.getAppVersion())

    private val indexHtml: AtomicReference<String> = AtomicReference("")
    private val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))
    private val serverData: HttpServerData = HttpServerData(sendEvent)
    private val stopDeferredRelay: AtomicReference<CompletableDeferred<Unit>?> = AtomicReference(null)

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is IOException && throwable !is BindException) return@CoroutineExceptionHandler
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        XLog.d(getLog("onCoroutineException", "ktorServer: ${ktorServer?.hashCode()}: $throwable"))
        XLog.e(getLog("onCoroutineException", throwable.toString()), throwable)
        ktorServer?.stop(0, 250)
        ktorServer = null
        when (throwable) {
            is BindException -> sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException))
            else -> sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
        }
    }

    @Volatile
    private var ktorServer: CIOApplicationEngine? = null

    init {
        XLog.d(getLog("init"))
    }

    internal fun start(serverAddresses: List<MjpegState.NetInterface>) {
        XLog.d(getLog("startServer"))

        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        runBlocking(coroutineScope.coroutineContext) { serverData.configure(mjpegSettings) }

        mjpegSettings.htmlEnableButtonsFlow.combineTransform(mjpegSettings.htmlBackColorFlow) { htmlEnableButtons, htmlBackColor ->
            emit(Pair(htmlEnableButtons, htmlBackColor))
        }.distinctUntilChanged().onEach { (htmlEnableButtons, htmlBackColor) ->
            val enableButtons = htmlEnableButtons && serverData.enablePin.not()
            val backColor = "#%06X".format(0xFFFFFF and htmlBackColor)
            serverData.notifyClients("SETTINGS", JSONObject().put("enableButtons", enableButtons).put("backColor", backColor))
            indexHtml.set(baseIndexHtml.replace("ENABLE_BUTTONS", enableButtons.toString()).replace("BACKGROUND_COLOR", backColor))
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

        val server = embeddedServer(CIO, applicationEngineEnvironment {
            parentCoroutineContext = coroutineScope.coroutineContext
            module { appModule(mjpegSharedFlow) }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress!!
                    port = runBlocking(parentCoroutineContext) { mjpegSettings.serverPortFlow.first() }
                }
            }
        }) {
            connectionIdleTimeoutSeconds = 10
        }

        server.environment.monitor.subscribe(ApplicationStarted) {
            XLog.i(getLog("monitor", "KtorStarted: ${it.hashCode()}"))
        }

        server.environment.monitor.subscribe(ApplicationStopped) {
            XLog.i(getLog("monitor", "KtorStopped: ${it.hashCode()}"))
            it.environment.parentCoroutineContext.cancel()
            serverData.clear()
            stopDeferredRelay.getAndSet(null)?.complete(Unit)
        }

        var exception: MjpegError? = null
        try {
            server.start(false)
        } catch (ignore: CancellationException) {
        } catch (ex: BindException) {
            XLog.w(getLog("startServer.BindException", ex.toString()))
            exception = MjpegError.AddressInUseException
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer.Throwable", throwable.toString()))
            XLog.e(getLog("startServer.Throwable"), throwable)
            exception = MjpegError.HttpServerException
        } finally {
            exception?.let {
                sendEvent(MjpegStreamingService.InternalEvent.Error(it))
                server.stop(0, 250)
                ktorServer = null
            } ?: run {
                ktorServer = server
            }
        }
    }

    internal fun stop(reloadClients: Boolean): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply Deferred@{
            XLog.d(this@HttpServer.getLog("stopServer", "reloadClients: $reloadClients"))
            ktorServer?.apply {
                stopDeferredRelay.set(this@Deferred)
                if (reloadClients) serverData.notifyClients("RELOAD")
                stop(0, 250)
                XLog.d(this@HttpServer.getLog("stopServer", "Deferred: ktorServer: ${ktorServer?.hashCode()}"))
                ktorServer = null
            } ?: complete(Unit)
            XLog.d(this@HttpServer.getLog("stopServer", "Done"))
        }

    internal fun destroy(): CompletableDeferred<Unit> {
        XLog.d(getLog("destroy"))
        serverData.destroy()
        return stop(false)
    }

    private suspend fun DefaultWebSocketSession.send(type: String, data: Any?) {
        if (isActive) send(JSONObject().put("type", type).apply { if (data != null) put("data", data) }.toString())
    }

    private fun Application.appModule(mjpegSharedFlow: SharedFlow<ByteArray>) {
        val crlf = "\r\n".toByteArray()
        val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
        val multipartBoundary = randomString(20)
        val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$multipartBoundary")
        val jpegBoundary = "--$multipartBoundary\r\n".toByteArray()

        install(DefaultHeaders) { header(HttpHeaders.CacheControl, "no-cache") }
        install(CORS) {
            allowHeader(HttpHeaders.AccessControlAllowOrigin)
            anyHost()
        }
        install(ForwardedHeaders)
        install(WebSockets)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is IOException) return@exception
                if (cause is CancellationException) return@exception
                if (cause is IllegalArgumentException) return@exception
                XLog.e(this@appModule.getLog("exception<Throwable>", cause.toString()))
                XLog.e(this@appModule.getLog("exception"), RuntimeException(">>>>>>>>>", cause)) //TODO Need real logs
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException))
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") { call.respondText(indexHtml.get(), ContentType.Text.Html) }
            get("favicon.ico") { call.respondBytes(favicon, ContentType.Image.XIcon) }
            get("logo.png") { call.respondBytes(logoPng, ContentType.Image.PNG) }
            get("script.js") { call.respondBytes(scriptJs, ContentType.Text.JavaScript) }
            get("start-stop") {
                if (mjpegSettings.htmlEnableButtonsFlow.first() && serverData.enablePin.not())
                    sendEvent(MjpegStreamingService.InternalEvent.StartStopFromWebPage)
                call.respond(HttpStatusCode.NoContent)
            }
            get(serverData.jpegFallbackAddress) {
                if (serverData.isAddressBlocked(call.request.origin.remoteAddress)) call.respond(HttpStatusCode.Forbidden)
                else call.respondBytes(lastJPEG.get(), ContentType.Image.JPEG)
            }

            webSocket("/socket") {
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                serverData.addClient(clientId, this)

                try {
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue

                        val msg = runCatching { JSONObject(frame.readText()) }
                            .onFailure { XLog.e(this@appModule.getLog("fromFrameText", it.message), it) }
                            .getOrNull() ?: continue

                        when (val type = msg.optString("type").uppercase()) {
                            "HEARTBEAT" -> send("HEARTBEAT", msg.optString("data"))

                            "CONNECT" -> when {
                                mjpegSettings.enablePinFlow.first().not() -> send("STREAM_ADDRESS", serverData.streamAddress)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                serverData.isClientAuthorized(clientId) -> send("STREAM_ADDRESS", serverData.streamAddress)
                                else -> send("UNAUTHORIZED", null)
                            }

                            "PIN" -> when {
                                serverData.isPinValid(clientId, remoteAddress, msg.optString("data")) -> send("STREAM_ADDRESS", serverData.streamAddress)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                else -> send("UNAUTHORIZED", "WRONG_PIN")
                            }

                            else -> {
                                val m = "Unknown message type: $type"
                                XLog.e(this@appModule.getLog("socket", m), IllegalArgumentException(m))
                            }
                        }
                    }
//                } catch (ignore: CancellationException) {
                } catch (cause: Exception) {
                    XLog.e(this@appModule.getLog("socket", "catch: ${cause.localizedMessage}"), cause)
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