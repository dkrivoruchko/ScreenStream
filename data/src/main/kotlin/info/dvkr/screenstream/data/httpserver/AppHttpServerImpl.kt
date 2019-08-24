package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getLog
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.server.HttpServer
import kotlinx.coroutines.channels.ReceiveChannel
import java.net.BindException
import java.util.concurrent.atomic.AtomicReference

class AppHttpServerImpl constructor(
    private val httpServerFiles: HttpServerFiles,
    private val jpegChannel: ReceiveChannel<ByteArray>,
    private val onStartStopRequest: () -> Unit,
    private val onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    private val onError: (AppError) -> Unit
) : AppHttpServer {

    private companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()
            RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
                .apply { globalServerParentEventLoop().shutdownGracefully() }
        }
    }

    private enum class State { CREATED, RUNNING, ERROR }

    private val state = AtomicReference<State>(State.CREATED)
    private var serverEventLoop: EventLoopGroup? = null
    private var nettyHttpServer: HttpServer<ByteBuf, ByteBuf>? = null
    private var httpServerStatistic: HttpServerStatistic? = null
    private var httpServerRxHandler: HttpServerRxHandler? = null

    init {
        XLog.d(getLog("init", "Invoked"))
    }

    @Synchronized
    override fun start(serverAddresses: List<NetInterface>, severPort: Int, useWiFiOnly: Boolean) {
        XLog.d(getLog("startServer", "Invoked"))
        check(state.get() == State.CREATED) { "AppHttpServer in state [${state.get()}] expected ${State.CREATED}" }
        require(severPort in 1025..65535) { "Tcp port must be in range [1025, 65535]" }

        val httpServerStatistic = HttpServerStatistic(::onStatistic.get()) { appError ->
            state.set(State.ERROR)
            onError(appError)
        }

        val httpServerRxHandler = HttpServerRxHandler(
            serverAddresses.map { it.address },
            httpServerFiles,
            onStartStopRequest,
            { statisticEvent -> httpServerStatistic.sendStatisticEvent(statisticEvent) },
            jpegChannel,
            { appError -> state.set(State.ERROR); onError(appError) }
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
            this.httpServerStatistic = httpServerStatistic
            this.httpServerRxHandler = httpServerRxHandler
            this.serverEventLoop = serverEventLoop
            this.nettyHttpServer = nettyHttpServer

            state.set(State.RUNNING)
        }
    }

    @Synchronized
    override fun stop() {
        XLog.d(getLog("stopServer", "Invoked"))

        try {
            nettyHttpServer?.shutdown()
            nettyHttpServer?.awaitShutdown()
            nettyHttpServer = null
        } catch (throwable: Throwable) {
            XLog.e(getLog("stopServer.nettyHttpServer"), throwable)
        }

        try {
            serverEventLoop?.shutdownGracefully()
            serverEventLoop = null
        } catch (throwable: Throwable) {
            XLog.e(getLog("stopServer.serverEventLoop"), throwable)
        }

        httpServerRxHandler?.destroy()
        httpServerRxHandler = null
        httpServerStatistic?.destroy()
        httpServerStatistic = null

        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
        state.set(State.CREATED)
    }
}