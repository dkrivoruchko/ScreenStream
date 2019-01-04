package info.dvkr.screenstream.data.httpserver

import android.content.Context
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.data.model.*
import info.dvkr.screenstream.data.other.getLog
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import java.net.BindException
import java.net.InetSocketAddress

class HttpServerImpl constructor(
    context: Context,
    private val httpServerFiles: HttpServerFiles,
    private val jpegChannel: ReceiveChannel<ByteArray>,
    private val onStartStopRequest: () -> Unit,
    private val onStatistic: (List<HttpClient>, List<TrafficPoint>) -> Unit,
    onError: (AppError) -> Unit
) : HttpServerCoroutineScope(onError), HttpServer {

    private companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()
            RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
                .apply { globalServerParentEventLoop().shutdownGracefully() }
        }
    }

    private enum class State {
        CREATED, RUNNING, ERROR
    }

    @Suppress("ArrayInDataClass")
    private data class ServerState(
        val state: State = State.CREATED,
        val globalServerEventLoop: EventLoopGroup? = null,
        val nettyHttpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>? = null,
        val httpServerStatistic: HttpServerStatistic? = null,
        val httpServerRxHandler: HttpServerRxHandler? = null
    ) {
        override fun toString(): String = "ServerState: $state"

        internal fun requireState(vararg requireStates: State) {
            state in requireStates ||
                    throw IllegalStateException("HttpServer in state [$state] expected ${requireStates.contentToString()}")
        }
    }

    sealed class ServerEvent {
        data class Start(val serverAddress: InetSocketAddress) : ServerEvent()
        object Stop : ServerEvent()
        data class ServerError(val appError: AppError) : ServerEvent()
        object Destroy : ServerEvent()

        override fun toString(): String = this::class.java.simpleName
    }

    override fun start(serverAddress: InetSocketAddress) = sendServerEvent(ServerEvent.Start(serverAddress))

    override fun stop() = sendServerEvent(ServerEvent.Stop)

    override fun destroy() = sendServerEvent(ServerEvent.Destroy)

    private val serverEventChannel: SendChannel<ServerEvent> = actor(capacity = 16) {
        var serverState = ServerState()

        for (event in this@actor) try {
            XLog.i(this@HttpServerImpl.getLog("actor", "$serverState. Request: $event"))

            when (event) {
                is ServerEvent.Start -> serverState = startServer(serverState, event.serverAddress)
                is ServerEvent.Stop -> serverState = stopServer(serverState)
                is ServerEvent.ServerError -> serverState = serverError(serverState, event.appError)
                is ServerEvent.Destroy -> super.destroy()
            }
        } catch (throwable: Throwable) {
            XLog.e(this@HttpServerImpl.getLog("actor"), throwable)
            onError(FatalError.ActorException)
        }
    }

    private fun sendServerEvent(event: ServerEvent) {
        parentJob.isActive || return

        if (serverEventChannel.isClosedForSend) {
            XLog.e(getLog("sendServerEvent"), IllegalStateException("Channel is ClosedForSend"))
            onError(FatalError.ChannelException)
        } else if (serverEventChannel.offer(event).not()) {
            XLog.e(getLog("sendServerEvent"), IllegalStateException("Channel is full"))
            onError(FatalError.ChannelException)
        }
    }

    init {
        XLog.d(getLog("init", "Invoked"))
    }

    private fun startServer(serverState: ServerState, serverAddress: InetSocketAddress): ServerState {
        XLog.d(getLog("startServer", "Invoked"))
        serverState.requireState(State.CREATED)

        if (serverAddress.port !in 1025..65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        val globalServerEventLoop = RxNetty.getRxEventLoopProvider().globalServerEventLoop()

        val nettyHttpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(
            serverAddress, globalServerEventLoop, NioServerSocketChannel::class.java
        )
            .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//          .enableWireLogging(LogLevel.ERROR)

        val httpServerStatistic = HttpServerStatistic(::onStatistic.get()) { appError ->
            sendServerEvent(ServerEvent.ServerError(appError))
        }

        val (currentStreamAddress, pinEnabled) = httpServerFiles.configureStreamAddress()
        val currentStartStopAddress = httpServerFiles.configureStartStopAddress()

        val httpServerRxHandler = HttpServerRxHandler(
            httpServerFiles.favicon,
            httpServerFiles.logo,
            httpServerFiles.configureIndexHtml(currentStreamAddress, currentStartStopAddress),
            currentStreamAddress,
            currentStartStopAddress,
            pinEnabled,
            httpServerFiles.configurePinAddress(),
            httpServerFiles.configurePinRequestHtmlPage(),
            httpServerFiles.configurePinRequestErrorHtmlPage(),
            onStartStopRequest,
            { statisticEvent -> httpServerStatistic.sendStatisticEvent(statisticEvent) },
            jpegChannel,
            { appError -> sendServerEvent(ServerEvent.ServerError(appError)) }
        )

        var exception: AppError? = null
        try {
            nettyHttpServer.start(httpServerRxHandler)
        } catch (ex: BindException) {
            XLog.w(getLog("startServer", ex.toString()))
            exception = FixableError.AddressInUseException
            sendServerEvent(ServerEvent.ServerError(exception))
        } catch (throwable: Throwable) {
            XLog.e(getLog("startServer"), throwable)
            exception = FatalError.NettyServerException
            sendServerEvent(ServerEvent.ServerError(exception))
        }

        return serverState.copy(
            state = if (exception == null) State.RUNNING else State.ERROR,
            globalServerEventLoop = globalServerEventLoop,
            nettyHttpServer = nettyHttpServer,
            httpServerStatistic = httpServerStatistic,
            httpServerRxHandler = httpServerRxHandler
        )
    }

    private fun stopServer(serverState: ServerState): ServerState {
        XLog.d(getLog("stopServer", "Invoked"))

        serverState.httpServerRxHandler?.destroy()
        serverState.httpServerStatistic?.destroy()

        try {
            serverState.nettyHttpServer?.shutdown()
            serverState.nettyHttpServer?.awaitShutdown()
            serverState.globalServerEventLoop?.shutdownGracefully()
        } catch (throwable: Throwable) {
            XLog.w(getLog("stopServer", throwable.toString()))
        }

        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))

        return serverState.copy(
            state = State.CREATED,
            globalServerEventLoop = null,
            nettyHttpServer = null,
            httpServerStatistic = null,
            httpServerRxHandler = null
        )
    }

    private fun serverError(serverState: ServerState, appError: AppError): ServerState {
        XLog.d(getLog("serverError", "Invoked"))
        onError(appError)
        return serverState.copy(state = State.ERROR)
    }

}