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
import rx.functions.Action1
import rx.subjects.BehaviorSubject
import rx.subscriptions.CompositeSubscription
import java.net.BindException
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList

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
                                 private val mEventBus: EventBus, // TODO This is bad
                                 onStatus: Action1<String>) : HttpServer {
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
    private val mGlobalServerEventLoop: EventLoopGroup = RxNetty.getRxEventLoopProvider().globalServerEventLoop()
    private val mHttpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>
    private val mHttpServerRxHandler: HttpServerRxHandler
    private val isRunning: AtomicBoolean = AtomicBoolean(false)
    private val mSubscriptions = CompositeSubscription()

    // Clients
    sealed class LocalEvent {
        @Keep data class ClientConnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientDisconnected(val address: InetSocketAddress) : LocalEvent()
        @Keep data class ClientBackpressure(val address: InetSocketAddress) : LocalEvent()
    }

    private val mClients = BehaviorSubject.create<List<HttpServer.Client>>(ArrayList<HttpServer.Client>())

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Create")

        val httpServerPort = serverAddress.port
        if (httpServerPort !in 1025..65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        mSubscriptions.add(mEventBus.getEvent().subscribe { globalEvent ->
            when (globalEvent) {
                is EventBus.GlobalEvent.CurrentClientsRequest ->
                    mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(mClients.value))
            }
        })

        mHttpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(serverAddress, mGlobalServerEventLoop, NioServerSocketChannel::class.java)
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

        mHttpServerRxHandler = HttpServerRxHandler(
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
            mHttpServer.start(mHttpServerRxHandler)
            isRunning.set(true)

            onStatus.call(HttpServer.HTTP_SERVER_OK) // ????

            mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(mClients.value))
            if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Started @port: ${mHttpServer.serverPort}")
        } catch (exception: BindException) {
            onStatus.call(HttpServer.HTTP_SERVER_ERROR_PORT_BUSY)
            if (BuildConfig.DEBUG_MODE) println(TAG + exception)
        }
    }

    override fun stop() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Stop")
        if (!isRunning.get()) throw IllegalStateException("Http server is not running")

        mHttpServer.shutdown()
        mHttpServer.awaitShutdown()
        mHttpServerRxHandler.stop()
        mGlobalServerEventLoop.shutdownGracefully()
        mSubscriptions.clear()
        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS_NUMBER))
        isRunning.set(false)
    }

    fun toEvent(event: LocalEvent) {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] toEvent: ${event.javaClass.simpleName}")

        when (event) {
            is LocalEvent.ClientConnected -> {
                val clientsList = mClients.value.plusElement(HttpServer.Client(event.address))
                mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(clientsList))
            }

            is LocalEvent.ClientDisconnected -> {
                mClients.value.forEach { client ->
                    if (client.clientAddress == event.address) {
                        client.disconnected = true
                        return@forEach
                    }
                }
                mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(mClients.value))
            }

            is LocalEvent.ClientBackpressure -> {
                mClients.value.forEach { client ->
                    if (client.clientAddress == event.address) {
                        client.hasBackpressure = true
                        return@forEach
                    }
                }
                mEventBus.sendEvent(EventBus.GlobalEvent.CurrentClients(mClients.value))
            }
        }
    }
}