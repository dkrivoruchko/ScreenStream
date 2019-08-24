package info.dvkr.screenstream.data.httpserver

import com.elvishew.xlog.XLog
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.model.AppError
import info.dvkr.screenstream.data.other.asString
import info.dvkr.screenstream.data.other.getLog
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
import java.net.InetAddress
import java.net.InetSocketAddress

internal class HttpServerRxHandler(
    private val serverAddresses: List<InetAddress>,
    private val httpServerFiles: HttpServerFiles,
    private val onStartStopRequest: () -> Unit,
    private val onStatisticEvent: (HttpServerStatistic.StatisticEvent) -> Unit,
    jpegBytesChannel: ReceiveChannel<ByteArray>,
    onError: (AppError) -> Unit
) : HttpServerCoroutineScope(onError), RequestHandler<ByteBuf, ByteBuf> {

    private val indexHtml: String
    private val streamAddress: String
    private val startStopAddress: String
    private val htmlEnableButtons: Boolean
    private val pinEnabled: Boolean
    private val pinAddress: String
    private val pinRequestHtml: String
    private val pinRequestErrorHtml: String

    private val crlf = "\r\n".toByteArray()
    private val multipartBoundary = randomString(20)
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()

    private val jpegBytesStream = BehaviorRelay.create<ByteArray>()
    private val eventloopScheduler = RxJavaEventloopScheduler(RxNetty.getRxEventLoopProvider().globalClientEventLoop())

    init {
        XLog.d(getLog("init", "Invoked"))

        httpServerFiles.prepareForConfigure().apply {
            htmlEnableButtons = first
            pinEnabled = second
        }

        streamAddress = httpServerFiles.configureStreamAddress()
        startStopAddress = httpServerFiles.configureStartStopAddress()
        indexHtml = httpServerFiles.configureIndexHtml(streamAddress, startStopAddress)
        pinAddress = httpServerFiles.configurePinAddress()
        pinRequestHtml = httpServerFiles.configurePinRequestHtml()
        pinRequestErrorHtml = httpServerFiles.configurePinRequestErrorHtml()

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
        val localAddress = response.unsafeConnection().channelPipeline.channel().localAddress() as InetSocketAddress
        if (localAddress.address !in serverAddresses) {
            XLog.w(getLog("handle", "Closing request to wrong IP address: ${localAddress.asString()}"))
            return response.unsafeConnection().close()
        }

        val uri = request.uri
        val clientAddress = response.unsafeConnection().channelPipeline.channel().remoteAddress() as InetSocketAddress
        XLog.d(getLog("handle", "Request to: ${localAddress.asString()}$uri from ${clientAddress.asString()}"))

        return when {
            uri == HttpServerFiles.ICON_PNG_ADDRESS -> response.sendPng(httpServerFiles.faviconPng)
            uri == HttpServerFiles.LOGO_PNG_ADDRESS -> response.sendPng(httpServerFiles.logoPng)
            uri == HttpServerFiles.FULLSCREEN_ON_PNG_ADDRESS -> response.sendPng(httpServerFiles.fullScreenOnPng)
            uri == HttpServerFiles.FULLSCREEN_OFF_PNG_ADDRESS -> response.sendPng(httpServerFiles.fullScreenOffPng)
            uri == HttpServerFiles.START_STOP_PNG_ADDRESS -> response.sendPng(httpServerFiles.startStopPng)
            uri == startStopAddress && htmlEnableButtons -> onStartStopRequest().run { response.sendHtml(indexHtml) }
            uri == HttpServerFiles.DEFAULT_HTML_ADDRESS -> response.sendHtml(if (pinEnabled) pinRequestHtml else indexHtml)
            uri == pinAddress && pinEnabled -> response.sendHtml(indexHtml)
            uri.startsWith(HttpServerFiles.DEFAULT_PIN_ADDRESS) && pinEnabled -> response.sendHtml(pinRequestErrorHtml)
            uri == streamAddress -> sendStream(response)
            else -> response.redirect(request.hostHeader)
        }
    }

    private fun HttpServerResponse<ByteBuf>.sendPng(pngBytes: ByteArray): Observable<Void> {
        status = HttpResponseStatus.OK
        addHeader(HttpHeaderNames.CONTENT_TYPE, "image/png")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.CONTENT_LENGTH, pngBytes.size.toString())
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return writeBytesAndFlushOnEach(Observable.just(pngBytes))
    }

    private fun HttpServerResponse<ByteBuf>.sendHtml(html: String): Observable<Void> {
        status = HttpResponseStatus.OK
        addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return writeStringAndFlushOnEach(Observable.just(html))
    }

    private fun sendStream(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        val channel = response.unsafeConnection().channelPipeline.channel()
        val clientAddress = channel.remoteAddress() as InetSocketAddress

        onStatisticEvent(HttpServerStatistic.StatisticEvent.Connected(clientAddress))
        channel.closeFuture().addListener {
            onStatisticEvent(HttpServerStatistic.StatisticEvent.Disconnected(clientAddress))
        }

        response.status = HttpResponseStatus.OK
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/x-mixed-replace;boundary=$multipartBoundary")
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

    private fun HttpServerResponse<ByteBuf>.redirect(serverAddress: String): Observable<Void> {
        status = HttpResponseStatus.MOVED_PERMANENTLY
        addHeader(HttpHeaderNames.LOCATION, "http://$serverAddress")
        addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return Observable.empty<Void>()
    }
}