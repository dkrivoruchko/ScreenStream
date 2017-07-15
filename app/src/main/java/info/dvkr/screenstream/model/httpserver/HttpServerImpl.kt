package info.dvkr.screenstream.model.httpserver


import android.support.annotation.Keep
import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.EventBus
import info.dvkr.screenstream.model.HttpServer
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import rx.Observable
import rx.Scheduler
import rx.functions.Action1
import rx.subjects.BehaviorSubject
import rx.subscriptions.CompositeSubscription
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashMap

class HttpServerImpl constructor(serverAddress: InetSocketAddress,
                                 favicon: ByteArray,
                                 baseIndexHtml: String,
                                 backgroundColor: Int,
                                 disableMJpegCheck: Boolean,
                                 pinEnabled: Boolean,
                                 pin: String,
                                 basePinRequestHtmlPage: String,
                                 pinRequestErrorMsg: String,
                                 jpegBytesStream: Observable<ByteArray>,
                                 eventBus: EventBus,
                                 private val eventScheduler: Scheduler) : HttpServer {
    private val TAG = "HttpServerImpl"

    companion object {
        private const val NETTY_IO_THREADS_NUMBER = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()

            val rxEventLoopProvider = RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
            rxEventLoopProvider.globalServerParentEventLoop().shutdownGracefully()
        }

        internal fun randomString(len: Int): String {
            val chars = CharArray(len)
            val symbols = "0123456789abcdefghijklmnopqrstuvwxyz"
            val random = Random()
            for (i in 0..len - 1) chars[i] = symbols[random.nextInt(symbols.length)]
            return String(chars)
        }
    }

    // Server internal components
    private val globalServerEventLoop: EventLoopGroup = RxNetty.getRxEventLoopProvider().globalServerEventLoop()
    private val httpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>
    private val httpServerRxHandler: HttpServerRxHandler
    private val isRunning: AtomicBoolean = AtomicBoolean(false)
    private val subscriptions = CompositeSubscription()

    // Clients
    sealed class LocalEvent {
        @Keep data class ClientConnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientBytesCount(val address: InetSocketAddress, val bytesCount: Int) : LocalEvent()
        @Keep data class ClientDisconnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientBackpressure(val address: InetSocketAddress) : LocalEvent()
    }

    private val clientsMap = HashMap<InetSocketAddress, HttpServer.Client>()
    private val clientsEvents = BehaviorSubject.create<Collection<HttpServer.Client>>()

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Create")

        val httpServerPort = serverAddress.port
        if (httpServerPort !in 1025..65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        subscriptions.add(eventBus.getEvent().subscribe { globalEvent ->
            if (globalEvent is EventBus.GlobalEvent.CurrentClientsRequest) {
                if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] globalEvent: $globalEvent")
                eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsMap.values))
            }
        })

        subscriptions.add(clientsEvents
                .throttleLast(1, TimeUnit.SECONDS, eventScheduler).subscribe { clientsList ->
            //            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] clientsList: $clientsList")
            eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsList))
        })

        httpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(serverAddress, globalServerEventLoop, NioServerSocketChannel::class.java)
                .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//                .enableWireLogging(LogLevel.ERROR);

        var indexHtmlPage = baseIndexHtml.replaceFirst(HttpServer.BACKGROUND_COLOR.toRegex(), String.format("#%06X", 0xFFFFFF and backgroundColor))
        if (disableMJpegCheck) indexHtmlPage = indexHtmlPage.replaceFirst("id=mj".toRegex(), "").replaceFirst("id=pmj".toRegex(), "")

        val streamAddress: String
        val pinAddress: String
        if (pinEnabled) {
            streamAddress = "/" + randomString(16) + ".mjpeg"
            indexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            pinAddress = HttpServer.DEFAULT_PIN_ADDRESS + pin
        } else {
            streamAddress = HttpServer.DEFAULT_STREAM_ADDRESS
            indexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), streamAddress)
            pinAddress = HttpServer.DEFAULT_PIN_ADDRESS
        }

        val pinRequestHtmlPage = basePinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), "&nbsp")
        val pinRequestErrorHtmlPage = basePinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), pinRequestErrorMsg)

        httpServerRxHandler = HttpServerRxHandler(
                favicon,
                indexHtmlPage,
                pinEnabled,
                pinAddress,
                streamAddress,
                pinRequestHtmlPage,
                pinRequestErrorHtmlPage,
                Action1 { clientEvent -> toEvent(clientEvent) },
                jpegBytesStream)
        try {
            httpServer.start(httpServerRxHandler)
            isRunning.set(true)
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Started @port: ${httpServer.serverPort}")
        } catch (exception: Exception) {
            eventBus.sendEvent(EventBus.GlobalEvent.Error(exception))
        }
        eventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsMap.values))
    }

    override fun stop() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Stop")

        if (isRunning.get()) {
            httpServer.shutdown()
            httpServer.awaitShutdown()
        }
        httpServerRxHandler.stop()
        globalServerEventLoop.shutdownGracefully()
        subscriptions.clear()
        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
        isRunning.set(false)
    }

    fun toEvent(event: LocalEvent) {
        Observable.just(event).observeOn(eventScheduler).subscribe { toEvent ->
            //            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] toEvent: $toEvent")

            when (toEvent) {
                is LocalEvent.ClientConnected -> clientsMap.put(toEvent.address, HttpServer.Client(toEvent.address))
                is LocalEvent.ClientBytesCount -> clientsMap[toEvent.address]?.let { it.sendBytes = it.sendBytes.plus(toEvent.bytesCount) }
                is LocalEvent.ClientDisconnected -> clientsMap[toEvent.address]?.disconnected = true
                is LocalEvent.ClientBackpressure -> clientsMap[toEvent.address]?.hasBackpressure = true
            }
            clientsEvents.onNext(clientsMap.values)
        }
    }
}