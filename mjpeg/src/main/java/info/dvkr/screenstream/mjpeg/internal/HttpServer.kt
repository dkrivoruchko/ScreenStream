package info.dvkr.screenstream.mjpeg.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.common.getVersionName
import info.dvkr.screenstream.common.randomString
import info.dvkr.screenstream.mjpeg.R
import info.dvkr.screenstream.mjpeg.internal.HttpServerData.Companion.getClientId
import info.dvkr.screenstream.mjpeg.settings.MjpegSettings
import info.dvkr.screenstream.mjpeg.ui.MjpegError
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.netty.Netty
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

internal class HttpServer(
    context: Context,
    private val mjpegSettings: MjpegSettings,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val videoHub: VideoStreamHub,
    private val sendEvent: (MjpegEvent) -> Unit
) {
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val favicon: ByteArray = context.getFileFromAssets("favicon.ico")
    private val logoSvg: ByteArray = context.getFileFromAssets("logo.svg")
    private val baseIndexHtml = String(context.getFileFromAssets("index.html"), StandardCharsets.UTF_8)
        .replace("%CONNECTING%", context.getString(R.string.mjpeg_html_stream_connecting))
        .replace("%RECONNECTING%", context.getString(R.string.mjpeg_html_stream_reconnecting))
        .replace("%STREAM_REQUIRE_PIN%", context.getString(R.string.mjpeg_html_stream_require_pin))
        .replace("%ENTER_PIN%", context.getString(R.string.mjpeg_html_enter_pin))
        .replace("%SUBMIT_PIN%", context.getString(R.string.mjpeg_html_submit_pin))
        .replace("%WRONG_PIN_MESSAGE%", context.getString(R.string.mjpeg_html_wrong_pin))
        .replace("%ADDRESS_BLOCKED%", context.getString(R.string.mjpeg_html_address_blocked))
        .replace("%ERROR%", context.getString(R.string.mjpeg_html_error_unspecified)) //TODO not used
        .replace("%APP_VERSION%", context.getVersionName())

    private val indexHtml: AtomicReference<String> = AtomicReference("")
    private val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))
    private val serverData: HttpServerData = HttpServerData(sendEvent)
    private val mjpegSharedFlow: AtomicReference<SharedFlow<ByteArray>> = AtomicReference(null)
    private val tlsIdentity = TlsIdentity(context)
    private val _tlsFingerprint = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    internal val tlsFingerprint: StateFlow<String?> = _tlsFingerprint
    private val ktorServer: AtomicReference<Pair<EmbeddedServer<*, *>, CompletableDeferred<Unit>>> = AtomicReference(null)

    private data class HtmlIndexSettings(
        val backColor: String,
        val fitWindow: Boolean,
        val keepImageOnReconnect: Boolean
    )

    private data class HtmlSettings(
        val enableButtons: Boolean,
        val backColor: String,
        val fitWindow: Boolean,
        val keepImageOnReconnect: Boolean
    )

    init {
        XLog.d(getLog("init"))
    }

    internal suspend fun start(serverAddresses: List<MjpegNetInterface>) {
        XLog.d(getLog("startServer"))

        serverData.configure(mjpegSettings)

        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default)

        mjpegSettings.data
            .map { HtmlIndexSettings(it.htmlBackColor.toColorHexString(), it.htmlFitWindow, it.htmlKeepImageOnReconnect) }
            .distinctUntilChanged()
            .onEach { (backColor, fitWindow, keepImageOnReconnect) ->
                indexHtml.set(
                    baseIndexHtml
                        .replace("BACKGROUND_COLOR", backColor)
                        .replace("FIT_WINDOW", if (fitWindow) """style='width: 100%; object-fit: contain;'""" else "")
                        .replace("%KEEP_IMAGE_ON_RECONNECT%", keepImageOnReconnect.toString())
                )
            }
            .launchIn(coroutineScope)

        mjpegSettings.data
            .map {
                HtmlSettings(
                    enableButtons = it.htmlEnableButtons && serverData.enablePin.not(),
                    backColor = it.htmlBackColor.toColorHexString(),
                    fitWindow = it.htmlFitWindow,
                    keepImageOnReconnect = it.htmlKeepImageOnReconnect
                )
            }
            .distinctUntilChanged()
            .onEach { (enableButtons, backColor, fitWindow, keepImageOnReconnect) ->
                val data = JSONObject(
                    mapOf(
                        "enableButtons" to enableButtons,
                        "backColor" to backColor,
                        "fitWindow" to fitWindow,
                        "keepImageOnReconnect" to keepImageOnReconnect
                    )
                )
                serverData.notifyClients("SETTINGS", data)
            }
            .launchIn(coroutineScope)

        val resultJpegStream = ByteArrayOutputStream()
        lastJPEG.set(ByteArray(0))

        @OptIn(ExperimentalCoroutinesApi::class)
        val mjpegFlow = combine(bitmapStateFlow, mjpegSettings.data) { bitmap, settings -> bitmap to settings }
            .conflate()
            .map { (bitmap, settings) ->
                withContext(Dispatchers.IO) {
                    resultJpegStream.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, resultJpegStream)
                    resultJpegStream.toByteArray() to settings.maxFPS
                }
            }
            .filter { (jpeg, _) -> jpeg.isNotEmpty() }
            .onEach { (jpeg, _) -> lastJPEG.set(jpeg) }
            .catch { cause ->
                XLog.e(getLog("mjpegFlow", "Producer failed"), cause)
                throw cause
            }
            .flatMapLatest { (jpeg, maxFPS) ->
                if (maxFPS > 0) { // If maxFPS > 0, repeatedly emit the same JPEG every second (keep-alive)
                    flow {
                        while (currentCoroutineContext().isActive) {
                            emit(jpeg)
                            delay(1000.milliseconds)
                        }
                    }
                } else {
                    flowOf(jpeg)
                }
            }
            .conflate()
            .onCompletion { cause ->
                if (cause != null && cause !is CancellationException) {
                    XLog.e(getLog("mjpegFlow", "Producer completed with error"), cause)
                }
            }
            // Only JPEG-encode while someone is actually watching the MJPEG stream (saves CPU/battery, esp. in video mode)
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 2000, replayExpirationMillis = 0), 1)

        mjpegSharedFlow.set(mjpegFlow)

        val serverPort = mjpegSettings.data.value.serverPort
        val httpsPort = httpsPortFor(serverPort)
        val identity = runCatching { withContext(Dispatchers.IO) { tlsIdentity.get(serverAddresses.map { it.address }) } }
            .onFailure { XLog.e(getLog("startServer", "TLS identity failed"), it) }
            .getOrNull()
        _tlsFingerprint.value = identity?.fingerprint
        val server = embeddedServer(
            factory = Netty,
            rootConfig = serverConfig {
                parentCoroutineContext = CoroutineExceptionHandler { _, throwable ->
                    if (throwable is BindException) return@CoroutineExceptionHandler
                    if (throwable is SocketException) return@CoroutineExceptionHandler
                    XLog.i(this@HttpServer.getLog("parentCoroutineContext", "coroutineExceptionHandler: $throwable"), throwable)
                }
                module { appModule() }
            },
            configure = {
                shutdownGracePeriod = 0
                shutdownTimeout = 500
                enableHttp2 = false // Avoid ALPN on Android; HTTP/1.1 is all we need
                serverAddresses.forEach { netInterface ->
                    connector {
                        host = netInterface.address.hostAddress!!
                        port = serverPort
                    }
                    if (identity != null) sslConnector(
                        keyStore = identity.keyStore,
                        keyAlias = TlsIdentity.ALIAS,
                        keyStorePassword = { identity.password.toCharArray() },
                        privateKeyPassword = { identity.password.toCharArray() }
                    ) {
                        host = netInterface.address.hostAddress!!
                        port = httpsPort
                    }
                }
            }
        )

        ktorServer.set(server to CompletableDeferred())

        server.monitor.subscribe(ApplicationStarted) {
            XLog.i(getLog("monitor", "KtorStarted: ${it.hashCode()}"))
        }

        server.monitor.subscribe(ApplicationStopped) {
            XLog.i(getLog("monitor", "KtorStopped: ${it.hashCode()}"))
            coroutineScope.cancel()
            serverData.clear()
            ktorServer.get()?.second?.complete(Unit)
        }

        try {
            server.start(false)
        } catch (cause: CancellationException) {
            if (cause.cause is SocketException) {
                XLog.w(getLog("startServer.CancellationException.SocketException", cause.cause.toString()))
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException()))
            } else {
                XLog.w(getLog("startServer.CancellationException", cause.toString()), cause)
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException()))
            }
        } catch (cause: BindException) {
            XLog.w(getLog("startServer.BindException", cause.toString()))
            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.AddressInUseException()))
        } catch (cause: Throwable) {
            XLog.e(getLog("startServer.Throwable"), cause)
            sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException()))
        }
        XLog.d(getLog("startServer", "Done. Ktor: ${server.hashCode()} "))
    }

    internal suspend fun stop(reloadClients: Boolean) = coroutineScope {
        XLog.d(getLog("stopServer", "reloadClients: $reloadClients"))
        launch(Dispatchers.Default) {
            ktorServer.getAndSet(null)?.let { (server, stopJob) ->
                if (stopJob.isActive) {
                    if (reloadClients) serverData.notifyClients("RELOAD", timeout = 250)
                    val hashCode = server.hashCode()
                    XLog.i(this@HttpServer.getLog("stopServer", "Ktor: $hashCode"))
                    server.stop(250, 500, TimeUnit.MILLISECONDS)
                    XLog.i(this@HttpServer.getLog("stopServer", "Done. Ktor: $hashCode"))
                }
            }
            mjpegSharedFlow.set(null)
            XLog.d(this@HttpServer.getLog("stopServer", "Done"))
        }
    }

    internal suspend fun destroy() {
        XLog.d(getLog("destroy"))
        serverData.destroy()
        stop(false)
    }

    /**
     * Browsers always send `Origin` on WebSocket upgrades and cross-origin requests. Non-browser clients (VLC, OBS, curl)
     * usually send none, which is allowed. A present Origin must match the Host the client connected to.
     */
    private fun ApplicationRequest.isSameOrigin(): Boolean {
        val origin = headers[HttpHeaders.Origin] ?: return true
        val host = headers[HttpHeaders.Host] ?: return false
        val originHost = runCatching { java.net.URI(origin).let { u -> if (u.port == -1) u.host else "${u.host}:${u.port}" } }.getOrNull()
        return originHost != null && (originHost.equals(host, ignoreCase = true) || "[$originHost]".equals(host, ignoreCase = true))
    }

    private suspend fun DefaultWebSocketSession.send(type: String, data: Any?) {
        if (isActive) send(JSONObject().put("type", type).apply { if (data != null) put("data", data) }.toString())
    }

    private fun httpsPortFor(httpPort: Int): Int = if (httpPort >= 65535) httpPort - 1 else httpPort + 1

    private fun Application.appModule() {
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
        install(DefaultHeaders) {
            header("X-Content-Type-Options", "nosniff")
            header("X-Frame-Options", "SAMEORIGIN")
            header("Referrer-Policy", "no-referrer")
        }
        // No ForwardedHeaders: this server is never behind a proxy, and trusting `Forwarded` lets clients spoof their IP.
        // No CORS: the viewer page is same-origin; cross-origin pages must not be able to read the stream.
        install(WebSockets)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                if (cause is IOException || cause is IllegalArgumentException || cause is IllegalStateException) return@exception
                XLog.e(this@appModule.getLog("exception"), RuntimeException("Throwable", cause))
                sendEvent(MjpegStreamingService.InternalEvent.Error(MjpegError.HttpServerException()))
                call.respondText(text = "500 Internal Server Error", status = HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") {
                // The viewer page always runs over HTTPS: encrypted PIN/stream, and a secure context for WebCodecs.
                // Raw stream URLs stay available over HTTP for players like VLC/OBS.
                if (call.request.local.scheme == "http" && _tlsFingerprint.value != null && call.request.queryParameters["http"] == null) {
                    val hostHeader = call.request.headers[HttpHeaders.Host] ?: call.request.local.serverHost
                    val host = if (hostHeader.startsWith("[")) hostHeader.substringBefore("]") + "]" else hostHeader.substringBefore(":")
                    call.response.headers.append(HttpHeaders.Location, "https://$host:${httpsPortFor(call.request.local.localPort)}/")
                    call.respond(HttpStatusCode.Found)
                    return@get
                }
                call.respondText(indexHtml.get(), ContentType.Text.Html)
            }
            get("favicon.ico") { call.respondBytes(favicon, ContentType.Image.XIcon) }
            get("logo.svg") { call.respondBytes(logoSvg, ContentType.Image.SVG) }
            get("start-stop") {
                if (call.request.isSameOrigin().not()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                if (mjpegSettings.data.value.htmlEnableButtons && serverData.enablePin.not())
                    sendEvent(MjpegStreamingService.InternalEvent.StartStopFromWebPage)
                call.respond(HttpStatusCode.NoContent)
            }
            get(serverData.jpegFallbackAddress) {
                val clientId = call.request.getClientId()
                if (serverData.isClientAllowed(clientId, call.request.origin.remoteAddress).not()) call.respond(HttpStatusCode.Forbidden)
                else {
                    val remoteAddress = call.request.origin.remoteAddress
                    val remotePort = call.request.origin.remotePort
                    serverData.addConnected(clientId, remoteAddress, remotePort)
                    val bytes = withContext(Dispatchers.IO) {
                        // MJPEG encoding only runs while /stream clients are subscribed, so encode the current frame on demand
                        ByteArrayOutputStream().also { out ->
                            bitmapStateFlow.value.compress(Bitmap.CompressFormat.JPEG, mjpegSettings.data.value.jpegQuality, out)
                        }.toByteArray()
                    }
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                    serverData.setNextBytes(clientId, remoteAddress, remotePort, bytes.size)
                    serverData.setDisconnected(clientId, remoteAddress, remotePort)
                }
            }

            webSocket("/socket") {
                if (call.request.isSameOrigin().not()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                    return@webSocket
                }
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                serverData.addClient(clientId, this)

                try {
                    for (frame in incoming) {
                        frame as? Frame.Text ?: continue
                        val msg = runCatching { JSONObject(frame.readText()) }.getOrNull() ?: continue

                        val enableButtons = mjpegSettings.data.value.htmlEnableButtons && serverData.enablePin.not()
                        val videoCodec = mjpegSettings.data.value.videoCodec
                        val isVideo = videoCodec != MjpegSettings.Values.VIDEO_CODEC_MJPEG && VideoCapture.isSupported(videoCodec)
                        val streamData = JSONObject().put("enableButtons", enableButtons).put("streamAddress", serverData.streamAddress)
                            .put("videoAddress", if (isVideo) "video" else JSONObject.NULL)

                        when (val type = msg.optString("type").uppercase()) {
                            "HEARTBEAT" -> send("HEARTBEAT", msg.optString("data"))

                            "CONNECT" -> when {
                                mjpegSettings.data.value.enablePin.not() -> send("STREAM_ADDRESS", streamData)
                                serverData.isAddressBlocked(remoteAddress) -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                serverData.isClientAuthorized(clientId) -> send("STREAM_ADDRESS", streamData)
                                else -> send("UNAUTHORIZED", null)
                            }

                            "PIN" -> when (serverData.checkPin(clientId, remoteAddress, msg.optString("data"))) {
                                HttpServerData.PinResult.VALID -> send("STREAM_ADDRESS", streamData)
                                HttpServerData.PinResult.BLOCKED -> send("UNAUTHORIZED", "ADDRESS_BLOCKED")
                                HttpServerData.PinResult.WRONG -> {
                                    delay(1000.milliseconds) // Slow down online guessing
                                    send("UNAUTHORIZED", "WRONG_PIN")
                                }
                            }

                            else -> {
                                val m = "Unknown message type: $type"
                                XLog.e(this@appModule.getLog("socket", m), IllegalArgumentException(m))
                            }
                        }
                    }
                } catch (_: CancellationException) {
                } catch (cause: Exception) {
                    XLog.w(this@appModule.getLog("socket", "catch: ${cause.localizedMessage}"), cause)
                } finally {
                    XLog.i(this@appModule.getLog("socket", "finally: $clientId"))
                    serverData.removeSocket(clientId)
                }
            }

            webSocket("/video") {
                if (call.request.isSameOrigin().not()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Origin not allowed"))
                    return@webSocket
                }
                val clientId = call.request.getClientId()
                val remoteAddress = call.request.origin.remoteAddress
                val remotePort = call.request.origin.remotePort
                if (serverData.isClientAllowed(clientId, remoteAddress).not()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                    return@webSocket
                }

                serverData.addConnected(clientId, remoteAddress, remotePort)
                XLog.i(this@appModule.getLog("video", "Client: $clientId:$remotePort"))
                try {
                    coroutineScope {
                        val session = this@webSocket
                        launch { // Client -> server: key frame requests (e.g. after a decoder error); ends when the socket closes
                            for (frame in incoming) {
                                if (frame is Frame.Text && frame.readText() == "KEYFRAME") videoHub.requestKeyFrame()
                            }
                            this@coroutineScope.cancel()
                        }

                        var sentConfig: VideoConfig? = null
                        var lastSeq = -1L
                        var waitForKey = true
                        videoHub.requestKeyFrame()
                        videoHub.packets.collect { packet ->
                            if (serverData.isAddressBlocked(remoteAddress) || serverData.isDisconnected(clientId, remoteAddress, remotePort))
                                throw CancellationException("Client stopped")

                            // A gap means this client fell behind and the shared buffer dropped frames: resync on next key frame
                            if (lastSeq >= 0 && packet.seq != lastSeq + 1 && !waitForKey) {
                                waitForKey = true
                                serverData.setSlowConnection(clientId, remoteAddress, remotePort)
                                videoHub.requestKeyFrame()
                            }
                            lastSeq = packet.seq
                            if (waitForKey && !packet.isKey) return@collect
                            waitForKey = false

                            val config = videoHub.config.value ?: return@collect
                            if (config != sentConfig) {
                                session.send(
                                    JSONObject().put("type", "CONFIG").put("codec", config.codec)
                                        .put("width", config.width).put("height", config.height).toString()
                                )
                                sentConfig = config
                            }

                            val buffer = java.nio.ByteBuffer.allocate(9 + packet.data.size)
                                .put(if (packet.isKey) 1.toByte() else 0.toByte()).putLong(packet.ptsUs).put(packet.data)
                            buffer.flip()
                            session.outgoing.send(Frame.Binary(true, buffer))
                            serverData.setNextBytes(clientId, remoteAddress, remotePort, buffer.limit())
                        }
                    }
                } catch (_: CancellationException) {
                } catch (cause: Exception) {
                    XLog.d(this@appModule.getLog("video", "catch: ${cause.localizedMessage}"))
                } finally {
                    serverData.setDisconnected(clientId, remoteAddress, remotePort)
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
                        val emitCounter = AtomicLong(0L)
                        val collectCounter = AtomicLong(0L)

                        val mjpegFlow = mjpegSharedFlow.get() ?: return
                        mjpegFlow.onStart {
                            XLog.i(this@appModule.getLog("onStart", "Client: $clientId:$remotePort"))
                            serverData.addConnected(clientId, remoteAddress, remotePort)
                            channel.writeFully(jpegBoundary)
                        }
                            .onCompletion {
                                XLog.i(this@appModule.getLog("onCompletion", "Client: $clientId:$remotePort"))
                                serverData.setDisconnected(clientId, remoteAddress, remotePort)
                            }
                            .takeWhile { stopClientStream(channel).not() }
                            .map { Pair(emitCounter.incrementAndGet(), it) }
                            .conflate()
                            .onEach { (emitCounter, jpeg) ->
                                if (stopClientStream(channel)) return@onEach

                                if (emitCounter - collectCounter.incrementAndGet() >= 5) {
                                    XLog.i(this@appModule.getLog("onEach", "Slow connection. Client: $clientId"))
                                    collectCounter.set(emitCounter)
                                    serverData.setSlowConnection(clientId, remoteAddress, remotePort)
                                }

                                // Write MJPEG frame
                                val jpegSizeText = jpeg.size.toString().toByteArray()
                                val mjpegHeader = buildPacket {
                                    writeFully(jpegBaseHeader)
                                    writeFully(jpegSizeText)
                                    writeFully(crlf)
                                    writeFully(crlf)
                                }.readByteArray()

                                val mjpegFooter = buildPacket {
                                    writeFully(crlf)
                                    writeFully(jpegBoundary)
                                }.readByteArray()

                                channel.writeFully(mjpegHeader)
                                channel.writeFully(jpeg)
                                channel.writeFully(mjpegFooter)
                                channel.flush()
                                // Write MJPEG frame

                                val size = mjpegHeader.size + jpeg.size + mjpegFooter.size
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
