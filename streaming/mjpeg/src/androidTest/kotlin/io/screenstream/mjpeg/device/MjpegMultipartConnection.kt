package io.screenstream.mjpeg.device

import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

/** Test-only HTTP/1.1 viewer. Owning the socket lets its read budget interrupt Android blocking IO. */
internal class MjpegMultipartConnection(port: Int, private val socket: Socket = Socket()) : Closeable {
    private var deadlineElapsedMillis: Long? = null
    private val input: InputStream
    private val reader: MjpegMultipartReader

    init {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            val wireInput = socket.getInputStream()
            val boundedSocketInput = object : InputStream() {
                override fun read(): Int {
                    beforeRead()
                    val value = wireInput.read()
                    checkDeadline()
                    return value
                }

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    beforeRead()
                    val count = wireInput.read(bytes, offset, length)
                    checkDeadline()
                    return count
                }

                override fun available(): Int = wireInput.available()
            }
            // Guard every socket read, including repeated reads inside BufferedInputStream.read.
            val boundedInput = boundedSocketInput.buffered()
            socket.getOutputStream().apply {
                write(("GET /mjpeg HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                        "Accept-Encoding: identity\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                flush()
            }
            val headers = withinReadBudget(15_000) {
                check(readLine(boundedInput).matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) { "Expected HTTP 200" }
                readHeaders(boundedInput)
            }
            check(headers["content-encoding"].let { it == null || it.equals("identity", ignoreCase = true) }) { "Unsupported content encoding" }
            val type = checkNotNull(headers["content-type"]) { "Missing multipart Content-Type" }
            check(type.substringBefore(';').trim().equals("multipart/x-mixed-replace", ignoreCase = true)) { "Unexpected Content-Type: $type" }
            val boundary = type.split(';').drop(1).map { it.trim() }.singleOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.trim('"') ?: error("Missing multipart boundary: $type")
            val transfer = headers["transfer-encoding"]
            input = when {
                transfer != null -> {
                    check(transfer.equals("chunked", ignoreCase = true) && "content-length" !in headers) { "Unsupported transfer framing" }
                    ChunkedInput(boundedInput)
                }
                "content-length" in headers -> FixedLengthInput(boundedInput, checkNotNull(headers["content-length"]?.toLongOrNull()).also { require(it >= 0) })
                else -> boundedInput
            }
            reader = MjpegMultipartReader(input, boundary)
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    fun nextFrame(): ByteArray = withinReadBudget(15_000) { reader.nextFrame() }

    fun awaitFrame(description: String, timeoutMillis: Long = 15_000, matches: (ByteArray) -> Boolean) {
        try {
            withinReadBudget(timeoutMillis) {
                while (true) {
                    val matched = matches(reader.nextFrame())
                    checkDeadline() // Matching/decoding may finish after the read itself.
                    if (matched) return@withinReadBudget
                }
            }
        } catch (failure: SocketTimeoutException) {
            throw SocketTimeoutException("Multipart did not deliver $description within its read budget").apply { initCause(failure) }
        }
    }

    fun assertClosedByServer(timeoutMillis: Long = 15_000) {
        check(!socket.isClosed) { "A locally closed viewer cannot prove server closure" }
        try {
            withinReadBudget(timeoutMillis) {
                val buffer = ByteArray(8192)
                try {
                    while (input.read(buffer) >= 0) {
                        // Drain the decoded response; every underlying read shares this budget.
                    }
                } catch (_: EOFException) {
                    // A transport EOF inside an unfinished HTTP chunk also ends the response.
                } catch (failure: SocketException) {
                    if (socket.isClosed) throw failure // Local containment must never prove peer closure.
                }
            }
        } catch (failure: SocketTimeoutException) {
            throw AssertionError("Exit left the multipart connection open", failure)
        }
    }

    private fun beforeRead() {
        checkDeadline()
        val remaining = deadlineElapsedMillis?.minus(SystemClock.elapsedRealtime()) ?: 5_000L
        if (remaining <= 0) throw SocketTimeoutException("Multipart aggregate read deadline expired")
        socket.soTimeout = minOf(5_000L, remaining).toInt()
    }

    private fun checkDeadline() {
        if (deadlineElapsedMillis?.let { SystemClock.elapsedRealtime() >= it } == true) {
            throw SocketTimeoutException("Multipart aggregate read deadline expired")
        }
    }

    private fun <T> withinReadBudget(timeoutMillis: Long, action: () -> T): T {
        require(timeoutMillis > 0)
        check(deadlineElapsedMillis == null) { "Multipart read budgets cannot overlap" }
        deadlineElapsedMillis = SystemClock.elapsedRealtime() + timeoutMillis
        try {
            val result = action()
            checkDeadline() // A late EOF is not a successful close receipt.
            return result
        } catch (failure: SocketTimeoutException) {
            socket.close()
            throw failure
        } finally {
            deadlineElapsedMillis = null
        }
    }

    override fun close() {
        socket.close() // Socket.close also interrupts a blocked read on another thread.
    }

    private class FixedLengthInput(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = input.read()
            if (value < 0) throw EOFException("Truncated HTTP response body")
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated HTTP response body")
            remaining -= count
            return count
        }
    }

    private class ChunkedInput(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var footerPending = false
        private var ended = false

        private fun prepareChunk(): Boolean {
            if (ended) return false
            if (remaining == 0L) {
                if (footerPending) check(readLine(input).isEmpty()) { "Invalid HTTP chunk footer" }
                val length = readLine(input).substringBefore(';')
                check(length.isNotEmpty() && length.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid HTTP chunk length" }
                remaining = checkNotNull(length.toLongOrNull(16)) { "Oversized HTTP chunk length" }
                if (remaining == 0L) {
                    readHeaders(input)
                    ended = true
                    return false
                }
                footerPending = true
            }
            return true
        }

        override fun read(): Int {
            if (!prepareChunk()) return -1
            val value = input.read()
            if (value < 0) throw EOFException("Truncated HTTP chunk")
            remaining--
            return value
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!prepareChunk()) return -1
            val count = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Truncated HTTP chunk")
            remaining -= count
            return count
        }
    }

    private companion object {
        fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 8192) {
                val value = input.read()
                if (value < 0) throw EOFException("HTTP ended while reading framing")
                if (value == '\r'.code) {
                    check(input.read() == '\n'.code) { "HTTP line must end with CRLF" }
                    return bytes.toString(Charsets.US_ASCII.name())
                }
                check(value in 32..126 || value == '\t'.code) { "Invalid HTTP header byte" }
                bytes.write(value)
            }
            error("Oversized HTTP line")
        }

        fun readHeaders(input: InputStream): Map<String, String> {
            val headers = mutableMapOf<String, String>()
            var totalBytes = 0
            while (true) {
                val line = readLine(input)
                totalBytes += line.length + 2
                check(totalBytes <= 16_384) { "Oversized HTTP headers/trailers" }
                if (line.isEmpty()) return headers
                val separator = line.indexOf(':')
                check(separator > 0) { "Malformed HTTP header" }
                val name = line.substring(0, separator).lowercase()
                check(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Malformed HTTP header name" }
                check(name !in headers) { "Duplicate HTTP header: $name" }
                headers[name] = line.substring(separator + 1).trim()
            }
        }
    }
}
