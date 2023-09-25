package info.dvkr.screenstream.mjpeg.internal.server

import android.content.Context
import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.*
import info.dvkr.screenstream.mjpeg.internal.MjpegError
import info.dvkr.screenstream.mjpeg.internal.MjpegEvent
import info.dvkr.screenstream.mjpeg.internal.MjpegState
import info.dvkr.screenstream.mjpeg.internal.MjpegStreamingService
import info.dvkr.screenstream.mjpeg.internal.NotificationBitmap
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.BindException
import java.util.concurrent.atomic.AtomicReference

internal class HttpServer(
    context: Context,
    private val mjpegSettings: MjpegSettings,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val notificationBitmap: NotificationBitmap,
    private val sendEvent: (MjpegEvent) -> Unit
) {

    private val httpServerFiles: HttpServerFiles = HttpServerFiles(context, mjpegSettings)
    private val clientData: ClientData = ClientData(mjpegSettings) { sendEvent(it) }
    private val stopDeferred: AtomicReference<CompletableDeferred<Unit>?> = AtomicReference(null)
    private lateinit var blockedJPEG: ByteArray

    private var ktorServer: CIOApplicationEngine? = null

    private val resultJpegStream = ByteArrayOutputStream()
    private val lastJPEG: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))

    init {
        XLog.d(getLog("init"))
    }

    internal fun start(serverAddresses: List<MjpegState.NetInterface>) {
        XLog.d(getLog("startServer"))

        val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
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
        val coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        runBlocking(coroutineScope.coroutineContext) {
            blockedJPEG = ByteArrayOutputStream().apply {
                notificationBitmap.getNotificationBitmap(NotificationBitmap.Type.ADDRESS_BLOCKED)
                    .compress(Bitmap.CompressFormat.JPEG, 100, this)
            }.toByteArray()
        }

        resultJpegStream.reset()
        lastJPEG.set(ByteArray(0))

        @OptIn(ExperimentalCoroutinesApi::class)
        val mjpegSharedFlow = bitmapStateFlow
            .map { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, mjpegSettings.jpegQualityFlow.first(), resultJpegStream)
                resultJpegStream.toByteArray()
            }
            .filter { it.isNotEmpty() }
            .flatMapLatest { jpeg ->
                lastJPEG.set(jpeg)
                flow<ByteArray> { // Send last image every second as keep-alive
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
                    port = runBlocking(parentCoroutineContext) { mjpegSettings.serverPortFlow.first() }
                }
            }
        }

        ktorServer = embeddedServer(CIO, environment) {
            connectionIdleTimeoutSeconds = 10
        }

        var exception: MjpegError? = null
        try {
            ktorServer?.start(false)
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
                ktorServer?.stop(0, 250)
                ktorServer = null
            }
        }
    }

    internal fun stop(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply Deferred@{
        XLog.d(this@HttpServer.getLog("stopServer"))
        ktorServer?.apply {
            stopDeferred.set(this@Deferred)
            stop(0, 250)
            XLog.d(this@HttpServer.getLog("stopServer", "Deferred: ktorServer: ${ktorServer?.hashCode()}"))
            ktorServer = null
        } ?: complete(Unit)
        XLog.d(this@HttpServer.getLog("stopServer", "Done"))
    }

    internal fun destroy(): CompletableDeferred<Unit> {
        XLog.d(getLog("destroy"))
        clientData.destroy()
        return stop()
    }
}