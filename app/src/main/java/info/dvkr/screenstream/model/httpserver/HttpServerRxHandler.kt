package info.dvkr.screenstream.model.httpserver


import info.dvkr.screenstream.BuildConfig
import info.dvkr.screenstream.model.HttpServer
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.reactivex.netty.protocol.http.server.HttpServerRequest
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import io.reactivex.netty.protocol.http.server.RequestHandler
import rx.BackpressureOverflow
import rx.Observable
import rx.Subscription
import rx.functions.Action0
import rx.functions.Action1
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

internal class HttpServerRxHandler(private val favicon: ByteArray,
                                   private val indexHtmlPage: String,
                                   private val pinEnabled: Boolean,
                                   private val pinAddress: String,
                                   private val streamAddress: String,
                                   private val pinRequestHtmlPage: String,
                                   private val pinRequestErrorHtmlPage: String,
                                   private val clientEvent: Action1<HttpServerImpl.LocalEvent>,
                                   jpegBytesStream: Observable<ByteArray>) : RequestHandler<ByteBuf, ByteBuf> {

    private val TAG = "HttpServerRxHandler"

    private val CRLF = "\r\n".toByteArray()
    private val multipartBoundary = HttpServerImpl.randomString(20)
    private val jpegBoundary = ("--" + multipartBoundary + "\r\n").toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()

    // Handler internal components
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    private val jpegBytesStream = BehaviorSubject.create<ByteArray>()
    private val subscription: Subscription?

    init {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServerRxHandler: Create")

        singleThreadExecutor.submit {
            Thread.currentThread().priority = 8
            Thread.currentThread().name = "SSHttpServerRxHandler"
        }

        subscription = jpegBytesStream.observeOn(Schedulers.from(singleThreadExecutor)).subscribe { jpegBytes ->
            val jpegLength = Integer.toString(jpegBytes.size).toByteArray()
            this.jpegBytesStream.onNext(Unpooled.copiedBuffer(jpegBaseHeader, jpegLength, CRLF, CRLF, jpegBytes, CRLF, jpegBoundary).array())
        }
    }

    fun stop() {
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] HttpServerRxHandler: Stop")
        subscription?.unsubscribe()
        singleThreadExecutor.shutdown()
    }

    // TODO How to get server errors?
    override fun handle(request: HttpServerRequest<ByteBuf>, response: HttpServerResponse<ByteBuf>): Observable<Void> {
        val uri = request.uri
        if (BuildConfig.DEBUG_MODE) println(TAG + ": Thread [${Thread.currentThread().name}] Priority: ${Thread.currentThread().priority} HttpServerRxHandler: Handle: $uri")
        when {
            uri == HttpServer.DEFAULT_ICON_ADDRESS -> return sentFavicon(response)
            uri == HttpServer.DEFAULT_HTML_ADDRESS -> return if (pinEnabled) sentPinRequestHtml(response) else sentIndexHtml(response)
            uri == pinAddress && pinEnabled -> return sentIndexHtml(response)
            uri.startsWith(HttpServer.DEFAULT_PIN_ADDRESS) && pinEnabled -> return sentPinRequestErrorHtml(response)
            uri == streamAddress -> {
                // Getting clients statuses
                val channel = response.unsafeConnection().channelPipeline.channel()
                // Client connected
                clientEvent.call(HttpServerImpl.LocalEvent.ClientConnected(channel.remoteAddress() as InetSocketAddress))
                // Client disconnected
                channel.closeFuture().addListener(ChannelFutureListener { future -> clientEvent.call(HttpServerImpl.LocalEvent.ClientDisconnected(future.channel().remoteAddress() as InetSocketAddress)) })
                return sendStream(response)
            }
            else -> return redirect(request.hostHeader, response) // Redirecting to default server address
        }
    }

    private fun sentFavicon(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "image/icon")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(favicon.size))
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeBytesAndFlushOnEach(Observable.just(favicon))
    }

    private fun sentIndexHtml(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeStringAndFlushOnEach(Observable.just(indexHtmlPage))
    }

    private fun sentPinRequestHtml(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeStringAndFlushOnEach(Observable.just(pinRequestHtmlPage))
    }

    private fun sentPinRequestErrorHtml(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeStringAndFlushOnEach(Observable.just(pinRequestErrorHtmlPage))
    }

    private fun sendStream(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=" + multipartBoundary)
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        return response.writeBytesAndFlushOnEach(jpegBytesStream
                .onBackpressureBuffer(2,
                        Action0 {
                            val channel = response.unsafeConnection().channelPipeline.channel()
                            val inetSocketAddress = channel.remoteAddress() as InetSocketAddress
                            clientEvent.call(HttpServerImpl.LocalEvent.ClientBackpressure(inetSocketAddress))
                        }, BackpressureOverflow.ON_OVERFLOW_DROP_OLDEST)
                .doOnNext({ jpegBytes ->
                    val channel = response.unsafeConnection().channelPipeline.channel()
                    val inetSocketAddress = channel.remoteAddress() as InetSocketAddress
                    clientEvent.call(HttpServerImpl.LocalEvent.ClientBytesCount(inetSocketAddress, jpegBytes.size))
                })
                // Sending boundary so browser can understand that image is fully send
                .startWith(Unpooled.copiedBuffer(jpegBoundary, jpegBytesStream.value).array())
        )
    }

    private fun redirect(serverAddress: String, response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.MOVED_PERMANENTLY
        response.addHeader(HttpHeaderNames.LOCATION, "http://" + serverAddress)
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return Observable.empty<Void>()
    }
}