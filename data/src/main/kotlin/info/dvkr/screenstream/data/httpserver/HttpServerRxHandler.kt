package info.dvkr.screenstream.data.httpserver

import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.other.getTag
import info.dvkr.screenstream.data.other.randomString
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.reactivex.netty.RxNetty
import io.reactivex.netty.protocol.http.server.HttpServerRequest
import io.reactivex.netty.protocol.http.server.HttpServerResponse
import io.reactivex.netty.protocol.http.server.RequestHandler
import io.reactivex.netty.threads.RxJavaEventloopScheduler
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import rx.BackpressureOverflow
import rx.Observable
import rx.functions.Action0
import timber.log.Timber
import java.net.InetSocketAddress

internal class HttpServerRxHandler(
    private val favicon: ByteArray,
    private val logo: ByteArray,
    private val indexHtmlPage: String,
    private val streamAddress: String,
    private val pinEnabled: Boolean,
    private val pinAddress: String,
    private val pinRequestHtmlPage: String,
    private val pinRequestErrorHtmlPage: String,
    private val onStatisticEvent: (HttpServerStatistic.StatisticEvent) -> Unit,
    jpegBytesChannel: ReceiveChannel<ByteArray>,
    onError: (AppError) -> Unit
) : HttpServerCoroutineScope(onError), RequestHandler<ByteBuf, ByteBuf> {

    private val crlf = "\r\n".toByteArray()
    private val multipartBoundary = randomString(20)
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()

    private val jpegBytesStream = BehaviorRelay.create<ByteArray>()
    private val eventloopScheduler = RxJavaEventloopScheduler(RxNetty.getRxEventLoopProvider().globalClientEventLoop())

    init {
        Timber.tag(getTag("init")).d("Invoked")
        launch {
            for (jpegBytes in jpegBytesChannel) {
                val jpegLength = jpegBytes.size.toString().toByteArray()
                jpegBytesStream.call(
                    Unpooled.copiedBuffer(jpegBaseHeader, jpegLength, crlf, crlf, jpegBytes, crlf, jpegBoundary).array()
                )
            }
        }
    }

    override fun handle(request: HttpServerRequest<ByteBuf>, response: HttpServerResponse<ByteBuf>): Observable<Void> {
        val uri = request.uri
        val clientAddress = response.unsafeConnection().channelPipeline.channel().remoteAddress() as InetSocketAddress
        Timber.tag(getTag("handle")).d("Request [$uri] from ${clientAddress.address.hostAddress}:${clientAddress.port}")

        return when {
            uri == HttpServerFiles.DEFAULT_ICON_ADDRESS -> sendFavicon(response)

            uri == HttpServerFiles.DEFAULT_LOGO_ADDRESS -> sendLogo(response)

            uri == HttpServerFiles.DEFAULT_HTML_ADDRESS ->
                if (pinEnabled) sendHtml(response, pinRequestHtmlPage) else sendHtml(response, indexHtmlPage)

            uri == pinAddress && pinEnabled -> sendHtml(response, indexHtmlPage)

            uri.startsWith(HttpServerFiles.DEFAULT_PIN_ADDRESS) && pinEnabled ->
                sendHtml(response, pinRequestErrorHtmlPage)

            uri == streamAddress -> sendStream(response)

            else -> redirect(request.hostHeader, response)
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
        val channel = response.unsafeConnection().channelPipeline.channel()
        val clientAddress = channel.remoteAddress() as InetSocketAddress

        onStatisticEvent(HttpServerStatistic.StatisticEvent.Connected(clientAddress))
        channel.closeFuture().addListener {
            onStatisticEvent(HttpServerStatistic.StatisticEvent.Disconnected(clientAddress))
        }

        response.status = HttpResponseStatus.OK
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/x-mixed-replace; boundary=$multipartBoundary")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

        return response.writeBytesAndFlushOnEach(
            jpegBytesStream
                .observeOn(eventloopScheduler)
                .onBackpressureBuffer(
                    2,
                    Action0 { onStatisticEvent(HttpServerStatistic.StatisticEvent.Backpressure(clientAddress)) },
                    BackpressureOverflow.ON_OVERFLOW_DROP_OLDEST
                )
                .doOnNext { jpegBytes ->
                    onStatisticEvent(HttpServerStatistic.StatisticEvent.NextBytes(clientAddress, jpegBytes.size))
                }
                // Sending boundary so browser can understand that previous image was fully send
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