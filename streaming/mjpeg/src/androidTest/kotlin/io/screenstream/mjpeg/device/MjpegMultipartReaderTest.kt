package io.screenstream.mjpeg.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
internal class MjpegMultipartReaderTest {
    @Test
    fun readsConsecutiveLiteralPartsUsingTheirDeclaredLengths() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x0a, 0xff.toByte(), 0xd9.toByte())
        val first = "--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray()
        val second = "--fixture\r\ncontent-type: image/jpeg\r\ncontent-length: 3\r\n\r\nXYZ\r\n".toByteArray()
        val reader = MjpegMultipartReader(ByteArrayInputStream(first + second), "fixture")
        assertContentEquals(jpeg, reader.nextFrame())
        assertContentEquals(byteArrayOf(88, 89, 90), reader.nextFrame())
    }

    @Test
    fun rejectsBareLfMalformedHeadersAndWrongBoundaries() {
        val wires = listOf(
            "--fixture\nContent-Type: image/jpeg\r\nContent-Length: 1\r\n\r\nX\r\n",
            "--fixture\r\nContent-Type image/jpeg\r\nContent-Length: 1\r\n\r\nX\r\n",
            "--fixture\r\nContent-Type: image/jpeg\nContent-Length: 1\r\n\r\nX\r\n",
            "--other\r\nContent-Type: image/jpeg\r\nContent-Length: 1\r\n\r\nX\r\n",
        )
        for (wire in wires) assertFailsWith<IllegalStateException> { reader(wire).nextFrame() }
    }

    @Test
    fun rejectsDuplicateHeadersIgnoringTheirCase() {
        val wires = listOf(
            "--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: 1\r\ncontent-length: 1\r\n\r\nX\r\n",
            "--fixture\r\nContent-Type: image/jpeg\r\ncontent-type: image/jpeg\r\nContent-Length: 1\r\n\r\nX\r\n",
        )
        for (wire in wires) assertFailsWith<IllegalStateException> { reader(wire).nextFrame() }
    }

    @Test
    fun rejectsMissingInvalidAndOversizedContentLengths() {
        val invalidHeaders = listOf(
            "Content-Type: image/jpeg\r\n",
            "Content-Type: image/png\r\nContent-Length: 1\r\n",
            "Content-Type: image/jpeg\r\nContent-Length: -1\r\n",
            "Content-Type: image/jpeg\r\nContent-Length: +1\r\n",
            "Content-Type: image/jpeg\r\nContent-Length: 2147483648\r\n",
        )
        for (headers in invalidHeaders) {
            assertFailsWith<IllegalStateException> { reader("--fixture\r\n$headers\r\nX\r\n").nextFrame() }
        }
        for (length in listOf(0, 33_554_433)) {
            assertFailsWith<IllegalArgumentException> {
                reader("--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: $length\r\n\r\nX\r\n").nextFrame()
            }
        }
    }

    @Test
    fun boundsIndividualLinesAndTheAggregateHeaderBlock() {
        val longLine = "--fixture\r\nX-Long: " + "x".repeat(8192) + "\r\n\r\n"
        assertFailsWith<IllegalStateException> { reader(longLine).nextFrame() }
        val manyHeaders = "--fixture\r\n" + (1..3).joinToString("") { "X-$it: ${"x".repeat(6000)}\r\n" } + "\r\n"
        assertFailsWith<IllegalStateException> { reader(manyHeaders).nextFrame() }
    }

    @Test
    fun rejectsTruncatedPayloadAndMissingPayloadCrlf() {
        assertFailsWith<EOFException> {
            reader("--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\nXY").nextFrame()
        }
        val prefix = "--fixture\r\nContent-Type: image/jpeg\r\nContent-Length: 1\r\n\r\nX"
        for (terminator in listOf("", "\n", "\rX", "--fixture\r\n")) {
            assertFailsWith<IllegalStateException> { reader(prefix + terminator).nextFrame() }
        }
    }

    @Test
    fun readsExactPayloadAcrossShortReadsAndRejectsInvalidSizes() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(1, length))
        }
        assertContentEquals(byteArrayOf(1, 2, 3), readJpegPayload(input, 3))
        assertContentEquals(byteArrayOf(4), readJpegPayload(input, 1))
        assertFailsWith<EOFException> { readJpegPayload(ByteArrayInputStream(byteArrayOf(1)), 2) }
        assertFailsWith<IllegalArgumentException> { readJpegPayload(ByteArrayInputStream(byteArrayOf()), 0) }
        assertFailsWith<IllegalArgumentException> { readJpegPayload(ByteArrayInputStream(byteArrayOf()), 33_554_433) }
    }

    private fun reader(wire: String): MjpegMultipartReader = MjpegMultipartReader(
        ByteArrayInputStream(wire.toByteArray(Charsets.US_ASCII)), "fixture",
    )
}
