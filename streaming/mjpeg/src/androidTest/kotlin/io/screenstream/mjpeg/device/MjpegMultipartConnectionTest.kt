package io.screenstream.mjpeg.device

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Actual Android loopback IO; these fixtures do not start capture, the module or production CIO. */
@RunWith(AndroidJUnit4::class)
internal class MjpegMultipartConnectionTest {
    @Test(timeout = 15_000)
    fun chunkedFramesCrossChunkBoundariesAndZeroChunkTrailersEndTheDecodedResponse() {
        val frames = part("ABC") + part("XYZ")
        val split = 11
        val chunks = chunk(frames.take(split).toByteArray()) + chunk(frames.drop(split).toByteArray()) + "0\r\nX-End: done\r\n\r\n".toByteArray()
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers("Transfer-Encoding: chunked\r\n") + chunks); flush() }
            peer.awaitClientClose(socket) // Decoded EOF must not require this physical socket to close.
        }) { port, _ ->
            MjpegMultipartConnection(port).use { connection ->
                assertContentEquals("ABC".toByteArray(), connection.nextFrame())
                connection.awaitFrame("second literal frame") { it.contentEquals("XYZ".toByteArray()) }
                connection.assertClosedByServer()
            }
        }
    }

    @Test(timeout = 15_000)
    fun fixedLengthResponseEndsAtItsDecodedLengthWhileUnframedResponseNeedsPhysicalEof() {
        for (fixedLength in listOf(false, true)) {
            val frame = part("frame")
            withPeer({ socket, peer ->
                val framing = if (fixedLength) "Content-Length: ${frame.size}\r\n" else ""
                socket.getOutputStream().apply { write(headers(framing) + frame); flush() }
                if (fixedLength) peer.awaitClientClose(socket)
            }) { port, _ ->
                MjpegMultipartConnection(port).use { connection ->
                    assertContentEquals("frame".toByteArray(), connection.nextFrame())
                    connection.assertClosedByServer()
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun stalledFrameReadTimesOutAndClosesTheOwnedSocketBeforeFixtureCleanup() {
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers()); flush() }
            peer.awaitClientClose(socket)
        }) { port, peer ->
            MjpegMultipartConnection(port).use { connection ->
                val started = SystemClock.elapsedRealtime()
                assertFailsWith<SocketTimeoutException> { connection.awaitFrame("stalled frame", 300) { true } }
                assertTrue(SystemClock.elapsedRealtime() - started < 3_000, "A stalled read exceeded its aggregate budget")
                assertTrue(peer.clientClosed.await(2, TimeUnit.SECONDS), "Timeout must close the exact client socket")
            }
        }
    }

    @Test(timeout = 15_000)
    fun progressingPayloadCannotRestartTheWholeFrameBudget() {
        val writes = AtomicInteger()
        withPeer({ socket, peer ->
            val output = socket.getOutputStream()
            output.write(headers() + "--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: 10000\r\n\r\n".toByteArray())
            output.flush()
            try {
                while (!peer.stop.await(25, TimeUnit.MILLISECONDS)) {
                    output.write('x'.code)
                    output.flush()
                    writes.incrementAndGet()
                }
            } catch (_: SocketException) {
                peer.clientClosed.countDown()
            }
        }) { port, _ ->
            MjpegMultipartConnection(port).use { connection ->
                val started = SystemClock.elapsedRealtime()
                assertFailsWith<SocketTimeoutException> { connection.awaitFrame("progressing frame", 400) { true } }
                assertTrue(writes.get() >= 2, "The peer must continue sending while parsing is pending")
                assertTrue(SystemClock.elapsedRealtime() - started < 3_000, "Payload progress extended the aggregate budget")
            }
        }
    }

    @Test(timeout = 15_000)
    fun bufferedBulkReadsRefreshTheBudgetBeforeEveryAvailableSocketRead() {
        val sent = CountDownLatch(1)
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers() + part("x".repeat(8192))); flush() }
            sent.countDown()
            peer.awaitClientClose(socket, allowTransportError = true)
        }) { port, peer ->
            val socket = ShortReadSocket()
            MjpegMultipartConnection(port, socket).use { connection ->
                assertTrue(sent.await(2, TimeUnit.SECONDS))
                assertTrue(socket.availableBytes() > 8_000, "The bulk read must start with many bytes available")
                socket.delayReads.set(true)
                assertFailsWith<SocketTimeoutException> { connection.awaitFrame("buffered bulk payload", 200) { true } }
                assertTrue(socket.delayedReads.get() < 20, "The buffer consumed beyond the socket-read deadline")
                assertTrue(peer.clientClosed.await(2, TimeUnit.SECONDS), "The peer must observe client termination before cleanup")
            }
        }
    }

    @Test(timeout = 15_000)
    fun matchingFrameThatFinishesDecodingAfterTheBudgetCannotPass() {
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers() + part("matching")); flush() }
            peer.awaitClientClose(socket)
        }) { port, peer ->
            MjpegMultipartConnection(port).use { connection ->
                val matcherEntered = AtomicBoolean()
                assertFailsWith<SocketTimeoutException> {
                    connection.awaitFrame("late decoded match", 300) {
                        matcherEntered.set(true)
                        SystemClock.sleep(600)
                        true
                    }
                }
                assertTrue(matcherEntered.get())
                assertTrue(peer.clientClosed.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 15_000)
    fun closeReceiptCannotWaitForALatePeerEof() {
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers()); flush() }
            peer.stop.await(600, TimeUnit.MILLISECONDS) // EOF after the budget would previously have passed.
        }) { port, _ ->
            MjpegMultipartConnection(port).use { connection ->
                val failure = assertFailsWith<AssertionError> { connection.assertClosedByServer(150) }
                assertIs<SocketTimeoutException>(failure.cause)
            }
        }
    }

    @Test(timeout = 15_000)
    fun aCompletedReadBudgetCannotCloseTheNextRead() {
        val next = CountDownLatch(1)
        withPeer({ socket, peer ->
            val output = socket.getOutputStream()
            output.write(headers() + part("first"))
            output.flush()
            check(next.await(5, TimeUnit.SECONDS))
            if (!peer.stop.await(450, TimeUnit.MILLISECONDS)) {
                output.write(part("second"))
                output.flush()
            }
            peer.awaitClientClose(socket)
        }) { port, _ ->
            MjpegMultipartConnection(port).use { connection ->
                connection.awaitFrame("first", 300) { it.contentEquals("first".toByteArray()) }
                next.countDown()
                connection.awaitFrame("second", 2_000) { it.contentEquals("second".toByteArray()) }
            }
        }
    }

    @Test(timeout = 15_000)
    fun closingTheOwnerInterruptsAnEnteredAndroidSocketReadAndDoesNotProvePeerClosure() {
        withPeer({ socket, peer ->
            socket.getOutputStream().apply { write(headers()); flush() }
            peer.awaitClientClose(socket)
        }) { port, _ ->
            val socket = ObservedSocket()
            MjpegMultipartConnection(port, socket).use { connection ->
                val executor = Executors.newSingleThreadExecutor()
                socket.observe.set(true)
                val reading = executor.submit<Throwable?> { runCatching { connection.assertClosedByServer() }.exceptionOrNull() }
                try {
                    assertTrue(socket.readEntered.await(2, TimeUnit.SECONDS), "The worker must enter the actual socket input")
                    assertFalse(socket.readExited.await(100, TimeUnit.MILLISECONDS), "The peer supplies no response body")
                    connection.close()
                    assertIs<SocketException>(reading.get(2, TimeUnit.SECONDS))
                    assertTrue(socket.readExited.await(2, TimeUnit.SECONDS))
                } finally {
                    connection.close()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Reader worker did not terminate")
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun truncatedChunkAndPeerResetRemainTransportTerminationReceipts() {
        for (reset in listOf(false, true)) {
            val allowTermination = CountDownLatch(1)
            withPeer({ socket, _ ->
                socket.getOutputStream().apply { write(headers("Transfer-Encoding: chunked\r\n") + "10\r\npartial".toByteArray()); flush() }
                check(allowTermination.await(5, TimeUnit.SECONDS))
                if (reset) socket.setSoLinger(true, 0)
            }) { port, _ ->
                MjpegMultipartConnection(port).use {
                    allowTermination.countDown()
                    it.assertClosedByServer()
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun unsupportedEncodingAndMalformedOrOversizedHttpFramingFail() {
        val responses = listOf(
            headers("Content-Encoding: gzip\r\n"),
            headers("Transfer-Encoding: gzip, chunked\r\n"),
            headers("Transfer-Encoding: chunked\r\nContent-Length: 0\r\n"),
            "HTTP/1.1 200 OK\r\nX-Long: ${"x".repeat(8192)}\r\n\r\n".toByteArray(),
            ("HTTP/1.1 200 OK\r\n" + (1..3).joinToString("") { "X-$it: ${"x".repeat(6000)}\r\n" } + "\r\n").toByteArray(),
        )
        for (response in responses) withPeer({ socket, _ ->
            socket.getOutputStream().apply { write(response); flush() }
        }) { port, _ ->
            assertFailsWith<IllegalStateException> { MjpegMultipartConnection(port).close() }
        }
        for (body in listOf("-1\r\n", "1\r\nXoops\r\n", "0\r\nX-Long: ${"x".repeat(8192)}\r\n\r\n")) {
            withPeer({ socket, _ ->
                socket.getOutputStream().apply { write(headers("Transfer-Encoding: chunked\r\n") + body.toByteArray()); flush() }
            }) { port, _ ->
                MjpegMultipartConnection(port).use { connection ->
                    assertFailsWith<IllegalStateException> { connection.assertClosedByServer() }
                }
            }
        }
    }

    private fun headers(extra: String = ""): ByteArray =
        "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=fixture\r\n$extra\r\n".toByteArray(Charsets.US_ASCII)

    private fun part(payload: String): ByteArray =
        "--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: ${payload.length}\r\n\r\n$payload\r\n".toByteArray(Charsets.US_ASCII)

    private fun chunk(bytes: ByteArray): ByteArray = "${bytes.size.toString(16)};fixture=yes\r\n".toByteArray() + bytes + "\r\n".toByteArray()

    private class Peer {
        val stop = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)

        fun awaitClientClose(socket: Socket, allowTransportError: Boolean = false) {
            try {
                assertEquals(-1, socket.getInputStream().read())
            } catch (failure: SocketException) {
                if (!allowTransportError || socket.isClosed || stop.count == 0L) throw failure
            }
            clientClosed.countDown()
        }
    }

    private fun withPeer(serve: (Socket, Peer) -> Unit, assertions: (Int, Peer) -> Unit) {
        val peer = Peer()
        val accepted = AtomicReference<Socket>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val worker = executor.submit {
                try {
                    server.accept().use { socket ->
                        accepted.set(socket)
                        socket.soTimeout = 5_000
                        val request = StringBuilder()
                        while (!request.endsWith("\r\n\r\n")) {
                            val value = socket.getInputStream().read()
                            if (value < 0) throw EOFException("No viewer request")
                            check(request.length < 4096)
                            request.append(value.toChar())
                        }
                        assertTrue(request.contains("Accept-Encoding: identity\r\n"))
                        serve(socket, peer)
                    }
                } catch (failure: SocketException) {
                    if (peer.stop.count != 0L) throw failure
                }
            }
            try {
                assertions(server.localPort, peer)
            } finally {
                peer.stop.countDown()
                accepted.get()?.close()
                server.close()
                try {
                    worker.get(5, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Peer worker did not terminate")
                }
            }
        }
    }

    private class ObservedSocket : Socket() {
        val observe = AtomicBoolean()
        val readEntered = CountDownLatch(1)
        val readExited = CountDownLatch(1)

        override fun getInputStream(): InputStream {
            val input = super.getInputStream()
            return object : InputStream() {
                override fun read(): Int = observed { input.read() }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = observed { input.read(bytes, offset, length) }

                private fun observed(read: () -> Int): Int {
                    val watching = observe.get()
                    if (watching) readEntered.countDown()
                    try {
                        return read()
                    } finally {
                        if (watching) readExited.countDown()
                    }
                }
            }
        }
    }

    private class ShortReadSocket : Socket() {
        val delayReads = AtomicBoolean()
        val delayedReads = AtomicInteger()
        private var rawInput: InputStream? = null

        fun availableBytes(): Int = checkNotNull(rawInput).available()

        override fun getInputStream(): InputStream {
            val input = super.getInputStream()
            rawInput = input
            return object : InputStream() {
                override fun available(): Int = input.available()
                override fun read(): Int = input.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val count = input.read(bytes, offset, minOf(64, length))
                    if (delayReads.get()) {
                        delayedReads.incrementAndGet()
                        SystemClock.sleep(20)
                    }
                    return count
                }
            }
        }
    }
}
