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
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.net.BindException
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class HttpServer(
    private val settingsReadOnly: SettingsReadOnly,
    private val httpServerFiles: HttpServerFiles,
    private val clientStatistic: ClientStatistic,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val onStartStopRequest: () -> Unit,
    private val onError: (AppError) -> Unit
) {
    private val crlf = "\r\n".toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()
    private val multipartBoundary = randomString(20)
    private val contentTypeString = "multipart/x-mixed-replace; boundary=$multipartBoundary"
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        when (throwable) {
            is BindException -> {
                XLog.w(getLog("onCoroutineException", throwable.toString()))
                onError(FixableError.AddressInUseException)
            }

            else -> {
                XLog.e(getLog("onCoroutineException"), throwable)
                onError(FatalError.NettyServerException)
            }
        }
        ktorServer?.stop(250, 250)
        ktorServer = null
    }

    init {
        XLog.d(getLog("init"))
    }

    private val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))
    private var ktorServer: CIOApplicationEngine? = null
    private var stopDeferred: CompletableDeferred<Unit>? = null

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer"))

        val severPort = settingsReadOnly.severPort
        require(severPort in 1025..65535) { "Tcp port must be in range [1025, 65535]" }

        httpServerFiles.configure()

        val resultJpegStream = ByteArrayOutputStream()
        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        val clientMJPEGFrameSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, settingsReadOnly.jpegQuality, resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .flatMapLatest { jpeg ->
                lastJPEG.set(jpeg)
                flow<ByteArray> { repeat(Int.MAX_VALUE) { emit(jpeg); delay(1000) } }
            }
            .conflate()
            .shareIn(coroutineScope, SharingStarted.Eagerly, 1)

        val environment = applicationEngineEnvironment {
            parentCoroutineContext = coroutineScope.coroutineContext
            module { appModule(clientMJPEGFrameSharedFlow) }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress
                    port = severPort
                }
            }
        }

        ktorServer = embeddedServer(CIO, environment) {
            connectionIdleTimeoutSeconds = 10
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

    private fun Application.appModule(clientMJPEGFrameSharedFlow: SharedFlow<ByteArray>) {
        environment.monitor.subscribe(ApplicationStarted) {
            XLog.i(this@HttpServer.getLog("monitor", "ApplicationStarted: ${hashCode()}"))
        }

        environment.monitor.subscribe(ApplicationStopped) {
            XLog.i(this@HttpServer.getLog("monitor", "ApplicationStopped: ${hashCode()}"))
            it.environment.parentCoroutineContext.cancel()
            clientStatistic.sendEvent(StatisticEvent.ClearClients)
            stopDeferred?.complete(Unit)
            stopDeferred = null
        }

        install(DefaultHeaders) { header(HttpHeaders.CacheControl, "no-cache") }

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
                            this@get.ensureActive()
                            val clientId = UUID.randomUUID().mostSignificantBits
                            val emmitCounter = AtomicLong(0L)
                            val collectCounter = AtomicLong(0L)
                            clientMJPEGFrameSharedFlow
                                .map { Pair(emmitCounter.incrementAndGet(), it) }
                                .conflate()
                                .onStart {
                                    XLog.d(this@HttpServer.getLog("onStart", "Client: $clientId"))
                                    val clientAddressAndPort = call.request.local.remoteHost
                                    clientStatistic.sendEvent(StatisticEvent.Connected(clientId, clientAddressAndPort))
                                    channel.writeFully(jpegBoundary, 0, jpegBoundary.size)
                                    val totalSize = writeMJPEGFrame(channel, lastJPEG.get())
                                    clientStatistic.sendEvent(StatisticEvent.NextBytes(clientId, totalSize))
                                }
                                .onCompletion {
                                    XLog.d(this@HttpServer.getLog("onCompletion", "Client: $clientId"))
                                    clientStatistic.sendEvent(StatisticEvent.Disconnected(clientId))
                                }
                                .onEach { (counter, jpeg) ->
                                    if (collectCounter.incrementAndGet() != counter) {
                                        XLog.i(this@HttpServer.getLog("onEach", "Slow connection. Client: $clientId"))
                                        collectCounter.set(counter)
                                        clientStatistic.sendEvent(StatisticEvent.Backpressure(clientId))
                                    }
                                    val totalSize = writeMJPEGFrame(channel, jpeg)
                                    clientStatistic.sendEvent(StatisticEvent.NextBytes(clientId, totalSize))
                                }
                                .catch { cause -> XLog.e(this@HttpServer.getLog("catch", "cause: $cause")) }
                                .collect()
                        }
                    })
                }

                get(httpServerFiles.jpegFallbackAddress) {
                    call.respondBytes(lastJPEG.get(), ContentType.Image.JPEG)
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

    private suspend fun writeMJPEGFrame(channel: ByteWriteChannel, jpeg: ByteArray): Int {
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
}