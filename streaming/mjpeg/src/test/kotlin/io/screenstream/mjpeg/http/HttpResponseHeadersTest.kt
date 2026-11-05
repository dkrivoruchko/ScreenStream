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
    fun identicalLengthOccurrencesRemainUnambiguousAndRetainMultiplicity() {
        val headers = headers("Content-Length: 3", "content-length: 3")
        assertEquals("3", headers["content-length"])
        assertEquals(listOf("3", "3"), headers.getAll("Content-Length"))
    }

    private fun headers(vararg lines: String): HttpResponseHeaders {
        val input = (lines.toList() + "").iterator()
        return readHttpResponseHeaders { input.next() }
    }
}
