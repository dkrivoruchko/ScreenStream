package info.dvkr.screenstream.data.httpserver

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.image.NotificationBitmap
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.model.HttpClient
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.model.TrafficPoint
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
internal class HttpServer(
    applicationContext: Context,
    private val parentCoroutineScope: CoroutineScope,
    private val settingsReadOnly: SettingsReadOnly,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val notificationBitmap: NotificationBitmap
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
    private lateinit var blockedJPEG: ByteArray

    private var ktorServer: CIOApplicationEngine? = null

    init {
        XLog.d(getLog("init"))
    }

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer"))

        val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
            if (throwable is IOException) return@CoroutineExceptionHandler
            if (throwable is CancellationException) return@CoroutineExceptionHandler
            XLog.d(getLog("onCoroutineException", "ktorServer: ${ktorServer?.hashCode()}"))
            XLog.e(getLog("onCoroutineException", throwable.toString()), throwable)
            ktorServer?.stop(0, 250)
            ktorServer = null
            when (throwable) {
                is BindException -> sendEvent(Event.Error(FixableError.AddressInUseException))
                else -> sendEvent(Event.Error(FatalError.HttpServerException))
            }
        }
        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        runBlocking(coroutineScope.coroutineContext) {
            blockedJPEG = ByteArrayOutputStream().apply {
                notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.ADDRESS_BLOCKED)
                    .compress(Bitmap.CompressFormat.JPEG, 100, this)
            }.toByteArray()
        }

        val resultJpegStream = ByteArrayOutputStream()
        val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))

        val mjpegSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, settingsReadOnly.jpegQualityFlow.first(), resultJpegStream)
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

        runBlocking(coroutineScope.coroutineContext) {
            httpServerFiles.configure()
            clientData.configure()
        }

        val environment = applicationEngineEnvironment {
            parentCoroutineContext = coroutineScope.coroutineContext
            watchPaths = emptyList() // Fix for java.lang.ClassNotFoundException: java.nio.file.FileSystems for API < 26
            module { appModule(httpServerFiles, clientData, mjpegSharedFlow, lastJPEG, blockedJPEG, stopDeferred) { sendEvent(it) } }
            serverAddresses.forEach { netInterface ->
                connector {
                    host = netInterface.address.hostAddress!!
                    port = runBlocking(parentCoroutineContext) { settingsReadOnly.serverPortFlow.first() }
                }
            }
        }

        ktorServer = embeddedServer(CIO, environment) {
            connectionIdleTimeoutSeconds = 10
        }

        var exception: AppError? = null
        try {
            ktorServer?.start(false)
        } catch (ignore: CancellationException) {
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
                ktorServer?.stop(0, 250)
                ktorServer = null
            }
        }
    }

    fun stop(): CompletableDeferred<Unit> {
        XLog.d(getLog("stopServer"))

        return CompletableDeferred<Unit>().apply Deferred@{
            ktorServer?.apply {
                stopDeferred.set(this@Deferred)
                stop(0, 250)
                XLog.d(this@HttpServer.getLog("stopServer", "Deferred: ktorServer: ${ktorServer.hashCode()}"))
                ktorServer = null
            } ?: complete(Unit)
            XLog.d(this@HttpServer.getLog("stopServer", "Deferred"))
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