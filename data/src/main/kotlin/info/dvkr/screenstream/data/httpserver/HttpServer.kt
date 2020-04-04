package info.dvkr.screenstream.data.httpserver

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.httpserver.ClientStatistic.StatisticEvent
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.other.randomString
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import io.ktor.application.*
import io.ktor.features.DefaultHeaders
import io.ktor.features.StatusPages
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.response.respond
import io.ktor.response.respondBytes
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.Routing
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.utils.io.ByteWriteChannel
import io.netty.handler.codec.http.HttpHeaderValues
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.util.concurrent.atomic.AtomicReference

class HttpServer(
    private val settingsReadOnly: SettingsReadOnly,
    private val httpServerFiles: HttpServerFiles,
    private val clientStatistic: ClientStatistic,
    private val bitmapChannel: BroadcastChannel<Bitmap>,
    private val onStartStopRequest: () -> Unit,
    private val onError: (AppError) -> Unit
) {
    private val crlf = "\r\n".toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
    private val multipartBoundary = randomString(20)
    private val contentTypeString = "multipart/x-mixed-replace; boundary=$multipartBoundary"
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(FatalError.CoroutineException)
    }

    init {
        XLog.d(getLog("init"))
    }

    private val lastMJPEGFrame: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))
    private var ktorServer: NettyApplicationEngine? = null
    private var stopDeferred: CompletableDeferred<Unit>? = null

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer"))

        val severPort = settingsReadOnly.severPort
        require(severPort in 1025..65535) { "Tcp port must be in range [1025, 65535]" }

        httpServerFiles.configure()

        val resultJpegStream = ByteArrayOutputStream()
        val context = Job() + Dispatchers.Default + coroutineExceptionHandler
        val clientMJPEGFrameBroadcastChannel = bitmapChannel.asFlow()
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, settingsReadOnly.jpegQuality, resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .map { jpegBytes -> jpegBytes.toMJPEGFrame() }
            .onEach { frame -> lastMJPEGFrame.set(frame) }
            .conflate()
            .broadcastIn(CoroutineScope(context), CoroutineStart.DEFAULT)

        val environment = applicationEngineEnvironment {
            parentCoroutineContext = context
            module { appModule(clientMJPEGFrameBroadcastChannel) }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress
                    port = severPort
                }
            }
        }

        ktorServer = embeddedServer(Netty, environment) {
            connectionGroupSize = 1
            workerGroupSize = 2
            callGroupSize = 1
        }

        var exception: AppError? = null
        try {
            ktorServer?.start(false)
        } catch (ex: BindException) {
            XLog.w(getLog("startServer", ex.toString()))
            exception = FixableError.AddressInUseException
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer"), throwable)
            exception = FatalError.NettyServerException
        } finally {
            if (exception != null) {
                onError(exception)
                ktorServer?.stop(250, 250)
                ktorServer = null
            }
        }
    }

    fun stop(): CompletableDeferred<Unit> {
        XLog.d(getLog("stopServer"))

        return CompletableDeferred<Unit>().apply Deferred@{
            ktorServer?.apply {
                stopDeferred = this@Deferred
                stop(250, 250)
                ktorServer = null
            } ?: complete(Unit)
        }
    }

    private fun Application.appModule(clientMJPEGFrameBroadcastChannel: BroadcastChannel<ByteArray>) {
        environment.monitor.subscribe(ApplicationStarted) {
            XLog.i(this@HttpServer.getLog("monitor", "ApplicationStarted: ${hashCode()}"))
        }

        environment.monitor.subscribe(ApplicationStopped) {
            XLog.i(this@HttpServer.getLog("monitor", "ApplicationStopped: ${hashCode()}"))
            it.environment.parentCoroutineContext.cancel()
            clientStatistic.clearClients()
            stopDeferred?.complete(Unit)
            stopDeferred = null
        }

        install(DefaultHeaders) {
            header(HttpHeaders.CacheControl, HttpHeaderValues.NO_CACHE.toString())
        }

        install(StatusPages) {
            status(HttpStatusCode.NotFound) {
                call.respondRedirect(HttpServerFiles.ROOT_ADDRESS, permanent = true)
            }
            exception<Throwable> { cause ->
                XLog.e(getLog("exception"), cause)
                onError(FatalError.NettyServerException)
                call.respond(HttpStatusCode.InternalServerError)
            }
        }

        install(Routing) {
            route(HttpServerFiles.ROOT_ADDRESS) {
                handle {
                    val responseHtml =
                        if (httpServerFiles.enablePin) {
                            when (call.request.queryParameters[HttpServerFiles.PIN_PARAMETER]) {
                                httpServerFiles.pin -> httpServerFiles.indexHtml
                                null -> httpServerFiles.pinRequestHtml
                                else -> httpServerFiles.pinRequestErrorHtml
                            }
                        } else {
                            httpServerFiles.indexHtml
                        }

                    call.respondText(responseHtml, ContentType.Text.Html)
                }

                get(httpServerFiles.streamAddress) {
                    call.respond(object : OutgoingContent.WriteChannelContent() {
                        override val status: HttpStatusCode = HttpStatusCode.OK

                        override val contentType: ContentType = ContentType.parse(contentTypeString)

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            val clientId = hashCode().toLong()

                            clientMJPEGFrameBroadcastChannel.asFlow()
                                .conflate() // TODO StatisticEvent.Backpressure()
                                .onStart {
                                    XLog.d(this@HttpServer.getLog("onStart", "Client: $clientId"))
                                    clientStatistic.sendEvent(
                                        StatisticEvent.Connected(clientId, call.request.local.remoteHost)
                                    )
                                    val frame = lastMJPEGFrame.get()
                                    channel.writeFully(frame, 0, frame.size)
                                    channel.flush()
                                    clientStatistic.sendEvent(StatisticEvent.NextBytes(clientId, frame.size))
                                }
                                .onEach { jpeg ->
                                    channel.writeFully(jpeg, 0, jpeg.size)
                                    channel.flush()
                                    clientStatistic.sendEvent(StatisticEvent.NextBytes(clientId, jpeg.size))
                                }
                                .onCompletion {
                                    XLog.d(this@HttpServer.getLog("onCompletion", "Client: $clientId"))
                                    clientStatistic.sendEvent(StatisticEvent.Disconnected(clientId))
                                }
                                .collect()
                        }
                    })
                }

                get(HttpServerFiles.START_STOP_ADDRESS) {
                    if (httpServerFiles.htmlEnableButtons) onStartStopRequest()
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

    private fun ByteArray.toMJPEGFrame(): ByteArray {
        val jpegSizeText = this.size.toString().toByteArray()
        val totalSize = jpegBaseHeader.size + jpegSizeText.size + crlf.size * 3 + this.size + jpegBoundary.size
        val result = jpegBaseHeader.copyOf(totalSize)
        var position = jpegBaseHeader.size
        System.arraycopy(jpegSizeText, 0, result, position, jpegSizeText.size)
        position += jpegSizeText.size
        System.arraycopy(crlf, 0, result, position, crlf.size)
        position += crlf.size
        System.arraycopy(crlf, 0, result, position, crlf.size)
        position += crlf.size
        System.arraycopy(this, 0, result, position, this.size)
        position += this.size
        System.arraycopy(crlf, 0, result, position, crlf.size)
        position += crlf.size
        System.arraycopy(jpegBoundary, 0, result, position, jpegBoundary.size)
        return result
    }
}