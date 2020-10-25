package info.dvkr.screenstream.data.httpserver

import android.graphics.Bitmap
import com.elvishew.xlog.XLog
import com.jakewharton.rxrelay.BehaviorRelay
import info.dvkr.screenstream.data.other.asString
import info.dvkr.screenstream.data.other.getLog
import info.dvkr.screenstream.data.other.randomString
import info.dvkr.screenstream.data.settings.SettingsReadOnly
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import rx.BackpressureOverflow
import rx.Observable
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

internal class HttpServerRxHandler(
    coroutineScope: CoroutineScope,
    private val serverAddresses: List<InetAddress>,
    private val httpServerFiles: HttpServerFiles,
    private val onStartStopRequest: () -> Unit,
    private val clientStatistic: ClientStatistic,
    private val settingsReadOnly: SettingsReadOnly,
    private val bitmapStateFlow: StateFlow<Bitmap>
) : RequestHandler<ByteBuf, ByteBuf> {

    private val crlf = "\r\n".toByteArray()
    private val multipartBoundary = randomString(20)
    private val jpegBoundary = ("--$multipartBoundary\r\n").toByteArray()
    private val jpegBaseHeader = "Content-Type: image/jpeg\r\nContent-Length: ".toByteArray()

    private val jpegBytesStream = BehaviorRelay.create<ByteArray>()
    private val jpegStillImg: AtomicReference<ByteArray> = AtomicReference(ByteArray(0))

    private val eventloopScheduler = RxJavaEventloopScheduler(RxNetty.getRxEventLoopProvider().globalClientEventLoop())

    init {
        XLog.d(getLog("init", "Invoked"))

        httpServerFiles.configure()

        val resultJpegStream = ByteArrayOutputStream()
        coroutineScope.launch {
            bitmapStateFlow.onEach { bitmap ->
                resultJpegStream.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, settingsReadOnly.jpegQuality, resultJpegStream)
                ensureActive()
                val jpegBytes = resultJpegStream.toByteArray().also { jpegStillImg.set(it) }
                val jpegLength = jpegBytes.size.toString().toByteArray()
                jpegBytesStream.call(
                    Unpooled.copiedBuffer(jpegBaseHeader, jpegLength, crlf, crlf, jpegBytes, crlf, jpegBoundary).array()
                )
            }.collect()
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
            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.FAVICON_PNG -> response.sendPng(httpServerFiles.faviconPng)
            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.LOGO_PNG -> response.sendPng(httpServerFiles.logoPng)
            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.FULLSCREEN_ON_PNG -> response.sendPng(httpServerFiles.fullscreenOnPng)
            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.FULLSCREEN_OFF_PNG -> response.sendPng(httpServerFiles.fullscreenOffPng)
            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.START_STOP_PNG -> response.sendPng(httpServerFiles.startStopPng)

            uri == HttpServerFiles.ROOT_ADDRESS + HttpServerFiles.START_STOP_ADDRESS && httpServerFiles.htmlEnableButtons ->
                onStartStopRequest().run { response.sendHtml(httpServerFiles.indexHtml) }

            uri == HttpServerFiles.ROOT_ADDRESS ->
                response.sendHtml(if (httpServerFiles.enablePin) httpServerFiles.pinRequestHtml else httpServerFiles.indexHtml)

            uri == "${HttpServerFiles.ROOT_ADDRESS}?pin=${httpServerFiles.pin}" && httpServerFiles.enablePin ->
                response.sendHtml(httpServerFiles.indexHtml)

            uri.startsWith("${HttpServerFiles.ROOT_ADDRESS}?pin=") && httpServerFiles.enablePin ->
                response.sendHtml(httpServerFiles.pinRequestErrorHtml)

            uri == HttpServerFiles.ROOT_ADDRESS + httpServerFiles.streamAddress -> sendStream(response)

            uri.startsWith(HttpServerFiles.ROOT_ADDRESS + httpServerFiles.jpegFallbackAddress) ->
                response.sendJpeg(jpegStillImg.get())

            else -> response.redirect(request.hostHeader)
        }
    }

    private fun HttpServerResponse<ByteBuf>.sendPng(pngBytes: ByteArray): Observable<Void> {
        status = HttpResponseStatus.OK
        addHeader(HttpHeaderNames.CONTENT_TYPE, "image/png")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        setHeader(HttpHeaderNames.CONTENT_LENGTH, pngBytes.size.toString())
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return writeBytesAndFlushOnEach(Observable.just(pngBytes))
    }

    private fun HttpServerResponse<ByteBuf>.sendHtml(html: String): Observable<Void> {
        status = HttpResponseStatus.OK
        addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return writeStringAndFlushOnEach(Observable.just(html))
    }

    private fun sendStream(response: HttpServerResponse<ByteBuf>): Observable<Void> {
        val channel = response.unsafeConnection().channelPipeline.channel()
        val clientAddress = channel.remoteAddress() as InetSocketAddress

        clientStatistic.sendEvent(
            ClientStatistic.StatisticEvent.Connected(
                clientAddress.hashCode().toLong(), clientAddress.asString()
            )
        )
        channel.closeFuture().addListener {
            clientStatistic.sendEvent(ClientStatistic.StatisticEvent.Disconnected(clientAddress.hashCode().toLong()))
        }

        response.status = HttpResponseStatus.OK
        response.setHeader(HttpHeaderNames.CONTENT_TYPE, "multipart/x-mixed-replace;boundary=$multipartBoundary")
        response.setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        response.setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        response.setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)

        return response.writeBytesAndFlushOnEach(
            jpegBytesStream
                .observeOn(eventloopScheduler)
                .onBackpressureBuffer(
                    2,
                    {
                        clientStatistic.sendEvent(
                            ClientStatistic.StatisticEvent.Backpressure(clientAddress.hashCode().toLong())
                        )
                    },
                    BackpressureOverflow.ON_OVERFLOW_DROP_OLDEST
                )
                .doOnNext { jpegBytes ->
                    clientStatistic.sendEvent(
                        ClientStatistic.StatisticEvent.NextBytes(clientAddress.hashCode().toLong(), jpegBytes.size)
                    )
                }
                // Sending boundary so browser can understand that previous image was fully send
                .startWith(Unpooled.copiedBuffer(jpegBoundary, jpegBytesStream.value).array())
        )
    }

    private fun HttpServerResponse<ByteBuf>.sendJpeg(jpegBytes: ByteArray): Observable<Void> {
        status = HttpResponseStatus.OK
        addHeader(HttpHeaderNames.CONTENT_TYPE, "image/jpeg")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        setHeader(HttpHeaderNames.CONTENT_LENGTH, jpegBytes.size.toString())
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return writeBytesAndFlushOnEach(Observable.just(jpegBytes))
    }

    private fun HttpServerResponse<ByteBuf>.redirect(serverAddress: String): Observable<Void> {
        status = HttpResponseStatus.MOVED_PERMANENTLY
        addHeader(HttpHeaderNames.LOCATION, "http://$serverAddress")
        addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        setHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache,no-store,max-age=0,must-revalidate")
        setHeader(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return Observable.empty()
    }
}