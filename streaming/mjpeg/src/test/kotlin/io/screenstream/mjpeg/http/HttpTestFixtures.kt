package io.screenstream.mjpeg.http

import android.content.Context
import io.screenstream.mjpeg.networkaddress.InterfaceType
import io.screenstream.mjpeg.networkaddress.NetworkAddress
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.SecretValue
import io.screenstream.mjpeg.settings.WebPageSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

internal fun httpTestContext(): Context {
    return RuntimeEnvironment.getApplication()
}

internal fun httpTestPageSettings(title: String = "Viewing contract"): WebPageSettings = WebPageSettings(title = title)

internal fun httpTestProtectedAccessSettings(pin: String = "123456"): AccessSettings =
    AccessSettings(pinEnabled = true, pin = SecretValue(pin), blockMinutes = 1)

internal fun httpTestAddress(id: Long = 1L, host: String = "127.0.0.1", interfaceName: String = "lo"): NetworkAddress =
    NetworkAddress(id, InetAddress.getByName(host), interfaceName, InterfaceType.Other)

/** Genuine listener identity without socket activation; each fixture still settles its engine owner. */
internal class UnstartedHttpListenerFixture(host: String = "127.0.0.1", port: Int = 8080) {
    private val parent = Job()
    val listener: HttpListener = CioHttpListener(parent, host, port) { }

    suspend fun close() {
        withContext(NonCancellable) {
            try {
                withTimeout(15.seconds) { listener.stopAndAwaitCleanup() }
            } finally {
                parent.cancel()
                withTimeout(15.seconds) { parent.join() }
            }
        }
    }
}

internal suspend fun HttpDelivery.awaitHttpInfo(predicate: (HttpDelivery.Info) -> Boolean): HttpDelivery.Info =
    withTimeout(15.seconds) { info.first(predicate) }

internal suspend fun HttpDelivery.stopHttpTestDelivery() {
    withContext(NonCancellable) { withTimeout(15.seconds) { stop() } }
}

internal fun assertHttpFailure(expected: Throwable, actual: Throwable?) {
    org.junit.Assert.assertEquals(expected::class.java, actual?.javaClass)
    org.junit.Assert.assertTrue(
        "Expected original ${expected::class.java.name} in propagated cause chain",
        actual != null && generateSequence(actual) { it.cause }.any { it === expected },
    )
}

internal data class HttpJpegResponse(val status: Int, val headers: HttpResponseHeaders, val bytes: ByteArray)

internal fun requestHttpJpeg(port: Int, path: String = "/jpeg"): HttpJpegResponse = Socket().use { socket ->
    socket.soTimeout = 5_000
    socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
    socket.getOutputStream().apply {
        write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        flush()
    }
    val input = socket.getInputStream().buffered()
    val status = readJpegHeaderLine(input).split(' ')[1].toInt()
    val headers = readHttpResponseHeaders { readJpegHeaderLine(input) }
    val length = checkNotNull(headers["content-length"]).toInt()
    require(length >= 0)
    val bytes = input.readNBytes(length)
    if (bytes.size != length) throw EOFException("JPEG ended at ${bytes.size} of $length bytes")
    HttpJpegResponse(status, headers, bytes)
}

private fun readJpegHeaderLine(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
        val value = input.read()
        if (value < 0) throw EOFException("Incomplete JPEG response headers")
        if (value == 13) {
            check(input.read() == 10)
            return bytes.toString(Charsets.US_ASCII)
        }
        check(bytes.size() < 16_384)
        bytes.write(value)
    }
}
