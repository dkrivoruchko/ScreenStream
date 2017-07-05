package info.dvkr.screenstream.model.httpserver


import info.dvkr.screenstream.model.HttpServer
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import io.reactivex.netty.RxNetty
import rx.Observable
import rx.functions.Action1
import rx.subjects.PublishSubject
import java.net.BindException
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

// TODO Singletone???
// TODO Check Threads
class HttpServerImpl constructor(serverAddress: InetSocketAddress,
                                 favicon: ByteArray,
                                 baseIndexHtml: String,
                                 backgroundColor: Int,
                                 disableMJpegCheck: Boolean,
                                 pinEnabled: Boolean,
                                 pin: String,
                                 pinRequestHtmlPage: String,
                                 pinRequestErrorMsg: String,
                                 jpegBytesStream: Observable<ByteArray>,
                                 onError: Action1<Throwable>) : info.dvkr.screenstream.model.HttpServer {

    private val TAG = "HttpServerImpl"

    companion object {
        private const val NETTY_IO_THREADS = 2

        init {
            ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED)
            RxNetty.disableNativeTransport()

            val rxEventLoopProvider = RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS))
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

    private val mIndexHtmlPage: String
    private val mStreamAddress: String
    private val mPinAddress: String
    private val mPinRequestHtmlPage: String
    private val mPinRequestErrorHtmlPage: String

    // Server internal components
    private val mClientStatus = PublishSubject.create<info.dvkr.screenstream.model.HttpServer.ClientEvent>()
    private val mGlobalServerEventLoop: EventLoopGroup = RxNetty.getRxEventLoopProvider().globalServerEventLoop()
    private val mHttpServer: io.reactivex.netty.protocol.http.server.HttpServer<ByteBuf, ByteBuf>
    private val mHttpServerRxHandler: HttpServerRxHandler
    private val isRunning: AtomicBoolean = AtomicBoolean(false)

    init {
        println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Create")

        val httpServerPort = serverAddress.port
        if (httpServerPort < 1025 || httpServerPort > 65535) throw IllegalArgumentException("Tcp port must be in range [1025, 65535]")

        mHttpServer = io.reactivex.netty.protocol.http.server.HttpServer.newServer(serverAddress, mGlobalServerEventLoop, NioServerSocketChannel::class.java)
                .clientChannelOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
//                .enableWireLogging(LogLevel.ERROR);

        var indexHtmlPage = baseIndexHtml.replaceFirst(HttpServer.BACKGROUND_COLOR.toRegex(), String.format("#%06X", 0xFFFFFF and backgroundColor))
        if (disableMJpegCheck) indexHtmlPage = indexHtmlPage.replaceFirst("id=mj".toRegex(), "").replaceFirst("id=pmj".toRegex(), "")

        if (pinEnabled) {
            mStreamAddress = "/" + randomString(16) + ".mjpeg"
            mIndexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), mStreamAddress)
            mPinAddress = HttpServer.DEFAULT_PIN_ADDRESS + pin
        } else {
            mStreamAddress = HttpServer.DEFAULT_STREAM_ADDRESS
            mIndexHtmlPage = indexHtmlPage.replaceFirst(HttpServer.SCREEN_STREAM_ADDRESS.toRegex(), mStreamAddress)
            mPinAddress = HttpServer.DEFAULT_PIN_ADDRESS
        }

        mPinRequestHtmlPage = pinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), "&nbsp")
        mPinRequestErrorHtmlPage = pinRequestHtmlPage.replaceFirst(HttpServer.WRONG_PIN_MESSAGE.toRegex(), pinRequestErrorMsg)

        mHttpServerRxHandler = HttpServerRxHandler(
                favicon,
                mIndexHtmlPage,
                pinEnabled,
                mPinAddress,
                mStreamAddress,
                mPinRequestHtmlPage,
                mPinRequestErrorHtmlPage,
                mClientStatus,
                jpegBytesStream)

        try {
            mHttpServer.start(mHttpServerRxHandler)
            isRunning.set(true)
            println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Started @port: ${mHttpServer.serverPort}")
        } catch (exception: BindException) {
            onError.call(exception)
            println(TAG + exception)
        }
    }

    override fun stop() {
        println(TAG + ": Thread [${Thread.currentThread().name}] HttpServer: Stop")
        if (!isRunning.get()) throw IllegalStateException("Http server is not running")

        mHttpServer.shutdown()
        mHttpServer.awaitShutdown()
        mHttpServerRxHandler.stop()
        mGlobalServerEventLoop.shutdownGracefully()
        RxNetty.useEventLoopProvider(HttpServerNioLoopProvider(NETTY_IO_THREADS))
        isRunning.set(false)
    }

    override fun onClientStatusChange(): Observable<HttpServer.ClientEvent> = mClientStatus.asObservable()

    override fun isRunning(): Boolean = isRunning.get()
}