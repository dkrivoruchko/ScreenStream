package io.screenstream.mjpeg.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class HttpResponseHeadersTest {
    @Test
    fun rejectsConflictingLiteralLengthsIgnoringHeaderCase() {
        assertThrows(IllegalStateException::class.java) {
            headers("Content-Length: 999", "content-length: 3")
        }
    }

    @Test
    fun rejectsLengthAndTransferEncodingInEitherOrderIgnoringHeaderCase() {
        for (lines in listOf(
            listOf("Content-Length: 3", "transfer-encoding: chunked"),
            listOf("Transfer-Encoding: chunked", "content-length: 3"),
        )) {
            assertThrows(IllegalStateException::class.java) { headers(*lines.toTypedArray()) }
        }
    }

    @Test
    fun rejectsRepeatedCorsOriginEvenWhenIdentical() {
        for (origin in listOf("*", "https://viewer.example")) {
            assertThrows(IllegalStateException::class.java) {
                headers("Access-Control-Allow-Origin: $origin", "access-control-allow-origin: $origin")
            }
        }
    }

    @Test
    fun acceptsEitherFramingFieldWithOneCorsOrigin() {
        val lengthDelimited = headers("Content-Length: 3", "Access-Control-Allow-Origin: *")
        assertEquals("3", lengthDelimited["Content-Length"])
        assertEquals(listOf("*"), lengthDelimited.getAll("Access-Control-Allow-Origin"))
        val chunked = headers("Transfer-Encoding: chunked", "Access-Control-Allow-Origin: *")
        assertEquals("chunked", chunked["Transfer-Encoding"])
        assertEquals(listOf("*"), chunked.getAll("Access-Control-Allow-Origin"))
    }

    @Test
    fun rejectsConflictingSingletonTypeAndCorsValues() {
        for ((name, first, second) in listOf(
            Triple("Content-Type", "image/jpeg", "text/plain"),
            Triple("Access-Control-Allow-Origin", "https://foreign.example", "*"),
        )) {
            assertThrows(IllegalStateException::class.java) { headers("$name: $first", "$name: $second") }
        }
    }

    @Test
    fun preservesRepeatableFieldsWithoutCombiningSetCookie() {
        val headers = headers("Set-Cookie: first=1; Path=/", "set-cookie: second=2; Path=/")
        assertEquals(listOf("first=1; Path=/", "second=2; Path=/"), headers.getAll("Set-Cookie"))
    }

    @Test
    fun recipientParsingAcceptsIdenticalLengthOccurrencesAndRetainsMultiplicity() {
        val headers = headers("Content-Length: 3", "content-length: 3")
        assertEquals("3", headers["content-length"])
        assertEquals(listOf("3", "3"), headers.getAll("Content-Length"))
    }

    private fun headers(vararg lines: String): HttpResponseHeaders {
        val input = (lines.toList() + "").iterator()
        return readHttpResponseHeaders { input.next() }
    }
}
