package info.dvkr.screenstream.domain.httpserver


import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.domain.utils.Utils
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
import java.net.InetSocketAddress
import java.util.concurrent.Executors

internal class HttpServerRxHandler(
    private val favicon: ByteArray,
    private val logo: ByteArray,
    private val indexHtmlPage: String,
    private val pinEnabled: Boolean,
    private val pinAddress: String,
    private val streamAddress: String,
    private val pinRequestHtmlPage: String,
    private val pinRequestErrorHtmlPage: String,
    private val clientEvent: Action1<HttpServerImpl.LocalEvent>,
    private val logItv: Action1<String>,
    jpegBytesStream: Observable<ByteArray>
) : RequestHandler<ByteBuf, ByteBuf> {

    private val CRLF = "\r\n".toByteArray()
    private val multipartBoundary = HttpServerImpl.randomString(20)
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()

    // Handler internal components
    private val singleThreadExecutor = Executors.newSingleThreadExecutor()
    private val jpegBytesStream = BehaviorRelay.create<ByteArray>()
    private val subscription: Subscription?

    init {
        logItv.call("[${Utils.getLogPrefix(this)}] Init")

        singleThreadExecutor.submit {
            Thread.currentThread().priority = 8
            Thread.currentThread().name = "SSHttpServerRxHandler"
        }

        subscription = jpegBytesStream.observeOn(Schedulers.from(singleThreadExecutor)).subscribe { jpegBytes ->
            val jpegLength = Integer.toString(jpegBytes.size).toByteArray()
            this.jpegBytesStream.call(
                Unpooled.copiedBuffer(jpegBaseHeader, jpegLength, CRLF, CRLF, jpegBytes, CRLF, jpegBoundary).array()
            )
        }
    }

    fun stop() {
        logItv.call("[${Utils.getLogPrefix(this)}] Stop")

        subscription?.unsubscribe()
        singleThreadExecutor.shutdown()
    }

    override fun handle(request: HttpServerRequest<ByteBuf>, response: HttpServerResponse<ByteBuf>): Observable<Void> {
        val uri = request.uri
        logItv.call("[${Utils.getLogPrefix(this)}] Handle: $uri}")

        when {
            uri == HttpServer.DEFAULT_ICON_ADDRESS -> return sendFavicon(response)

            uri == HttpServer.DEFAULT_LOGO_ADDRESS -> return sendLogo(response)

            uri == HttpServer.DEFAULT_HTML_ADDRESS ->
                return if (pinEnabled) sendHtml(response, pinRequestHtmlPage) else sendHtml(response, indexHtmlPage)

            uri == pinAddress && pinEnabled -> return sendHtml(response, indexHtmlPage)

            uri.startsWith(HttpServer.DEFAULT_PIN_ADDRESS) && pinEnabled ->
                return sendHtml(response, pinRequestErrorHtmlPage)

            uri == streamAddress -> {
                // Getting clients statuses
                val channel = response.unsafeConnection().channelPipeline.channel()
                // Client connected
                clientEvent.call(HttpServerImpl.LocalEvent.ClientConnected(channel.remoteAddress() as InetSocketAddress))
                // Client disconnected
                channel.closeFuture().addListener(ChannelFutureListener { future ->
                    val address = future.channel().remoteAddress() as InetSocketAddress
                    clientEvent.call(HttpServerImpl.LocalEvent.ClientDisconnected(address))
                })
                return sendStream(response)
            }
            else -> return redirect(request.hostHeader, response) // Redirecting to default server address
        }
    }

    private fun sendFavicon(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "image/icon")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(favicon.size))
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeBytesAndFlushOnEach(Observable.just(favicon))
    }

    private fun sendLogo(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "image/png")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(logo.size))
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeBytesAndFlushOnEach(Observable.just(logo))
    }

    private fun sendHtml(response: HttpServerResponse<ByteBuf>, html: String): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return response.writeStringAndFlushOnEach(Observable.just(html))
    }

    private fun sendStream(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        response.status = HttpResponseStatus.OK
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=$multipartBoundary")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        return response.writeBytesAndFlushOnEach(
            jpegBytesStream
                .onBackpressureBuffer(
                    2,
                    Action0 {
                        val channel = response.unsafeConnection().channelPipeline.channel()
                        val inetSocketAddress = channel.remoteAddress() as InetSocketAddress
                        clientEvent.call(HttpServerImpl.LocalEvent.ClientBackpressure(inetSocketAddress))
                    }, BackpressureOverflow.ON_OVERFLOW_DROP_OLDEST
                )
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
        response.addHeader(HttpHeaderNames.LOCATION, "http://$serverAddress")
        response.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return Observable.empty<Void>()
    }
}