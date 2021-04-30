package info.dvkr.screenstream.data.httpserver

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getLog
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
import java.io.IOException
import java.net.BindException
import java.util.*
import java.util.concurrent.atomic.AtomicReference

internal class HttpServer(
    applicationContext: Context,
    private val parentCoroutineScope: CoroutineScope,
    private val settingsReadOnly: SettingsReadOnly,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val addressBlockedBitmap: Bitmap
) {

    sealed class Event {

        sealed class Action : Event() {
            object StartStopRequest : Action()
        }

        sealed class Statistic : Event() {
            class Clients(val clients: List<HttpClient>) : Statistic()
            class Traffic(val traffic: List<TrafficPoint>) : Statistic()
        }

        class Error(val error: AppError) : Event()

        override fun toString(): String = javaClass.simpleName
    }

    private val _eventSharedFlow = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val eventSharedFlow: SharedFlow<Event> = _eventSharedFlow.asSharedFlow()

    private val httpServerFiles: HttpServerFiles = HttpServerFiles(applicationContext, settingsReadOnly)
    private val clientData: ClientData = ClientData(settingsReadOnly) { sendEvent(it) }
    private val stopDeferred: AtomicReference<CompletableDeferred<Unit>?> = AtomicReference(null)
    private val blockedJPEG: ByteArray = ByteArrayOutputStream().apply {
        addressBlockedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, this)
    }.toByteArray()

    private var ktorServer: CIOApplicationEngine? = null

    init {
        XLog.d(getLog("init"))
    }

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer"))

        val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable is IOException) return@CoroutineExceptionHandler
            XLog.e(getLog("onCoroutineException >>>"))
            XLog.e(getLog("onCoroutineException"), throwable)
            sendEvent(Event.Error(FatalError.HttpServerException))
            ktorServer?.stop(250, 250)
            ktorServer = null
        }
        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        val resultJpegStream = ByteArrayOutputStream()
        val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))

        val mjpegSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, settingsReadOnly.jpegQuality, resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .flatMapLatest { jpeg ->
                lastJPEG.set(jpeg)
                flow<ByteArray> { // Send last image every second as keep-alive //TODO Add settings option for this
                    while (currentCoroutineContext().isActive) {
                        emit(jpeg)
                        delay(1000)
                    }
                }
            }
            .conflate()
            .shareIn(coroutineScope, SharingStarted.Eagerly, 1)

        httpServerFiles.configure()
        clientData.configure()

        val environment = applicationEngineEnvironment {
            parentCoroutineContext = coroutineScope.coroutineContext
            watchPaths = emptyList() // Fix for java.lang.ClassNotFoundException: java.nio.file.FileSystems for API < 26
            module {
                appModule(
                    httpServerFiles, clientData, mjpegSharedFlow, lastJPEG, blockedJPEG, stopDeferred
                ) { sendEvent(it) }
            }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress
                    port = settingsReadOnly.severPort
                }
            }
        }

        ktorServer = embeddedServer(CIO, environment) {
            connectionIdleTimeoutSeconds = 10
        }

        var exception: AppError? = null
        try {
            ktorServer?.start(false)
        } catch (ignore: kotlinx.coroutines.CancellationException) {
        } catch (ex: BindException) {
            XLog.w(getLog("startServer", ex.toString()))
            exception = FixableError.AddressInUseException
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer >>>"))
            XLog.e(getLog("startServer"), throwable)
            exception = FatalError.HttpServerException
        } finally {
            exception?.let {
                sendEvent(Event.Error(it))
                ktorServer?.stop(250, 250)
                ktorServer = null
            }
        }
    }

    fun stop(): CompletableDeferred<Unit> {
        XLog.d(getLog("stopServer"))

        return CompletableDeferred<Unit>().apply Deferred@{
            ktorServer?.apply {
                stopDeferred.set(this@Deferred)
                stop(250, 250)
                ktorServer = null
            } ?: complete(Unit)
        }
    }

    fun destroy(): CompletableDeferred<Unit> {
        XLog.d(getLog("destroy"))
        clientData.destroy()
        return stop()
    }

    private fun sendEvent(event: Event) {
        parentCoroutineScope.launch { _eventSharedFlow.emit(event) }
    }
}