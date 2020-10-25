package info.dvkr.screenstream.data.httpserver

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.model.FatalError
import info.dvkr.screenstream.data.model.FixableError
import info.dvkr.screenstream.data.model.NetInterface
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.settings.SettingsReadOnly
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.server.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.net.BindException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppHttpServer(
    private val settingsReadOnly: SettingsReadOnly,
    private val httpServerFiles: HttpServerFiles,
    private val clientStatistic: ClientStatistic,
    private val bitmapStateFlow: StateFlow<Bitmap>,
    private val onStartStopRequest: () -> Unit,
    private val onError: (AppError) -> Unit
) {

    private companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()
            RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
                .apply { globalServerParentEventLoop().shutdownGracefully() }
        }
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        XLog.e(getLog("onCoroutineException"), throwable)
        onError(FatalError.CoroutineException)
    }

    private enum class State { CREATED, RUNNING, ERROR }

    private val state = AtomicReference(State.CREATED)
    private var serverEventLoop: EventLoopGroup? = null
    private var nettyHttpServer: HttpServer<ByteBuf, ByteBuf>? = null
    private var httpServerRxHandler: HttpServerRxHandler? = null
    private var coroutineScope: CoroutineScope? = null

    init {
        XLog.d(getLog("init", "Invoked"))
    }

    fun start(serverAddresses: List<NetInterface>) {
        XLog.d(getLog("startServer", "Invoked"))
        check(state.get() == State.CREATED) { "AppHttpServer in state [${state.get()}] expected ${State.CREATED}" }
        val severPort = settingsReadOnly.severPort
        require(severPort in 1025..65535) { "Tcp port must be in range [1025, 65535]" }

        coroutineScope = CoroutineScope(Job() + Dispatchers.Default + coroutineExceptionHandler)

        val httpServerRxHandler = HttpServerRxHandler(
            coroutineScope!!,
            serverAddresses.map { it.address },
            httpServerFiles,
            onStartStopRequest,
            clientStatistic,
            settingsReadOnly,
            bitmapStateFlow
        )

        val serverEventLoop = RxNetty.getRxEventLoopProvider().globalServerEventLoop()

        val nettyHttpServer = HttpServer.newServer(severPort, serverEventLoop, NioServerSocketChannel::class.java)
            .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//          .enableWireLogging(LogLevel.ERROR)

        var exception: AppError? = null
        try {
            nettyHttpServer.start(httpServerRxHandler)
        } catch (ex: BindException) {
            XLog.w(getLog("startServer", ex.toString()))
            exception = FixableError.AddressInUseException
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer"), throwable)
            exception = FatalError.NettyServerException
        }

        if (exception != null) {
            state.set(State.ERROR)
            onError(exception)
        } else {
            this.httpServerRxHandler = httpServerRxHandler
            this.serverEventLoop = serverEventLoop
            this.nettyHttpServer = nettyHttpServer

            state.set(State.RUNNING)
        }
    }

    fun stop() {
        XLog.d(getLog("stopServer", "Invoked"))

        try {
            nettyHttpServer?.shutdown()
            nettyHttpServer?.awaitShutdown()
            nettyHttpServer = null
        } catch (throwable: Throwable) {
            XLog.e(getLog("stopServer.nettyHttpServer"), throwable)
        }

        try {
            serverEventLoop?.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
            serverEventLoop = null
        } catch (throwable: Throwable) {
            XLog.e(getLog("stopServer.serverEventLoop"), throwable)
        }

        coroutineScope?.cancel()
        httpServerRxHandler = null

        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
        state.set(State.CREATED)
    }
}