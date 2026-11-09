package io.screenstream.mjpeg.device

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

private const val MAX_FRAME_BYTES = 32 * 1024 * 1024
private const val MAX_LINE_BYTES = 8192
private const val MAX_HEADER_BYTES = 16 * 1024

/** Independent, bounded wire reader; it does not use production framing or JPEG writers. */
internal class MjpegMultipartReader(private val input: InputStream, boundary: String) {
    private val marker: String

    init {
        require(boundary.length in 1..200 && boundary.all { it.code in 32..126 }) { "Invalid multipart boundary" }
        marker = "--$boundary"
    }

    fun nextFrame(): ByteArray {
        check(readLine() == marker) { "Multipart boundary differs from its Content-Type" }
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine()
            headerBytes += line.length + 2
            check(headerBytes <= MAX_HEADER_BYTES) { "Oversized multipart headers" }
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            check(separator > 0) { "Malformed multipart header" }
            val name = line.substring(0, separator)
            check(name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Malformed multipart header name" }
            val key = name.lowercase()
            check(key !in headers) { "Duplicate multipart header: $name" }
            headers[key] = line.substring(separator + 1).trim()
        }
        check(headers["content-type"] == "image/jpeg") { "Missing or invalid multipart Content-Type" }
        val lengthText = checkNotNull(headers["content-length"]) { "Missing multipart Content-Length" }
        check(lengthText.isNotEmpty() && lengthText.all { it in '0'..'9' }) { "Invalid multipart Content-Length" }
        val length = checkNotNull(lengthText.toIntOrNull()) { "Oversized multipart Content-Length" }
        val bytes = readJpegPayload(input, length)
        check(input.read() == '\r'.code && input.read() == '\n'.code) { "Missing multipart payload CRLF" }
        return bytes
    }

    private fun readLine(): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_LINE_BYTES) {
            val next = input.read()
            if (next < 0) throw EOFException("Multipart ended while reading framing")
            if (next == '\r'.code) {
                check(input.read() == '\n'.code) { "Multipart line must end with CRLF" }
                return bytes.toString(Charsets.US_ASCII.name())
            }
            check(next in 32..126 || next == '\t'.code) { "Invalid multipart header byte" }
            bytes.write(next)
        }
        error("Oversized multipart line")
    }
}

internal fun readJpegPayload(input: InputStream, length: Int): ByteArray {
    require(length in 1..MAX_FRAME_BYTES) { "Invalid JPEG Content-Length: $length" }
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(bytes, offset, length - offset)
        if (read < 0) throw EOFException("JPEG ended at $offset/$length bytes")
        if (read == 0) {
            val next = input.read()
            if (next < 0) throw EOFException("JPEG ended at $offset/$length bytes")
            bytes[offset++] = next.toByte()
        } else {
            offset += read
        }
    }
    return bytes
}
