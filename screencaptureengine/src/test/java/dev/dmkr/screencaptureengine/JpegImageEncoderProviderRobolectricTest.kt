package dev.dmkr.screencaptureengine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import dev.dmkr.screencaptureengine.internal.encoding.jpeg.createFrameworkJpegEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JpegImageEncoderProviderRobolectricTest {
    @Test
    fun createEncoderAutoPolicyReportsFrameworkBitmapCompressInfo() {
        val encoder = JpegImageEncoderProvider().createEncoder(request(width = 16, height = 16))

        assertEquals(
            ImageEncoderInfo(
                providerId = "jpeg",
                outputFormat = EncodedImageFormats.Jpeg,
                backendName = JpegEncoderBackend.FrameworkBitmapCompress.name,
            ),
            encoder.info,
        )
    }

    @Test
    fun createEncoderFrameworkOnlyReportsFrameworkBitmapCompressInfo() {
        val encoder = JpegImageEncoderProvider(
            backendPolicy = JpegEncoderBackendPolicy.FrameworkOnly,
        ).createEncoder(request(width = 16, height = 16))

        assertEquals(JpegEncoderBackend.FrameworkBitmapCompress.name, encoder.info.backendName)
    }

    @Test
    fun createEncoderAcceptsLegalTinyMaxEncodedBytes1024() {
        val encoder = JpegImageEncoderProvider().createEncoder(
            request(width = 64, height = 64, maxEncodedBytes = 1_024),
        )

        assertEquals("jpeg", encoder.info.providerId)
    }

    @Test
    fun createEncoderRejectsRawSpanLargerThanByteBufferLimit() {
        try {
            JpegImageEncoderProvider().createEncoder(
                request(width = 1, height = 2, rowStrideBytes = Int.MAX_VALUE),
            )
            fail("Expected ImageEncoderUnavailableException")
        } catch (exception: ImageEncoderUnavailableException) {
            assertTrue(exception.message.orEmpty().contains("raw input byte span"))
        }
    }

    @Test
    fun encodeTightRowsWritesDecodableJpeg() {
        val width = 16
        val height = 16
        val sink = RecordingEncodedImageSink()
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))

        val result = encoder.encode(
            input(width, height, pixels = solidPixels(width, height, Rgba(230, 20, 20))),
            sink,
        )

        assertEquals(ImageEncodeResult.Success, result)
        val bitmap = decodeJpeg(sink.bytes())
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertDominantRedAt(bitmap, width / 2, height / 2)
    }

    @Test
    fun encodePaddedRowsIgnorePoisonPadding() {
        val width = 16
        val height = 16
        val rowStrideBytes = width * 4 + 16
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(
            request(width, height, rowStrideBytes = rowStrideBytes),
        )
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(
            input(
                width = width,
                height = height,
                rowStrideBytes = rowStrideBytes,
                pixels = solidPixels(width, height, Rgba(20, 220, 20)),
                paddingPoison = 0xFF.toByte(),
            ),
            sink,
        )

        assertEquals(ImageEncodeResult.Success, result)
        assertDominantGreenAt(decodeJpeg(sink.bytes()), width / 2, height / 2)
    }

    @Test
    fun encodeReadsRgbaByteOrderAndTopToBottomRows() {
        val width = 32
        val height = 32
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(input(width, height, pixels = quadrantPixels(width, height)), sink)

        assertEquals(ImageEncodeResult.Success, result)
        val decoded = decodeJpeg(sink.bytes())
        assertDominantRedAt(decoded, 8, 8)
        assertDominantGreenAt(decoded, 24, 8)
        assertDominantBlueAt(decoded, 8, 24)
        assertDominantYellowAt(decoded, 24, 24)
    }

    @Test
    fun encodeUploadsEveryLogicalRowBandToTheBitmap() {
        val width = 48
        val height = 64
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()
        val pixels = List(width * height) { index ->
            when ((index / width) * 4 / height) {
                0 -> Rgba(230, 20, 20)
                1 -> Rgba(20, 220, 20)
                2 -> Rgba(20, 20, 230)
                else -> Rgba(230, 220, 20)
            }
        }

        val result = encoder.encode(input(width, height, pixels = pixels), sink)

        assertEquals(ImageEncodeResult.Success, result)
        val decoded = decodeJpeg(sink.bytes())
        assertDominantRedAt(decoded, width / 2, 8)
        assertDominantGreenAt(decoded, width / 2, 24)
        assertDominantBlueAt(decoded, width / 2, 40)
        assertDominantYellowAt(decoded, width / 2, 56)
    }

    @Test
    fun encodeIgnoresSourceAlphaAndProducesOpaqueDecodedBitmap() {
        val width = 16
        val height = 16
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(
            input(width, height, pixels = solidPixels(width, height, Rgba(220, 20, 20, a = 0))),
            sink,
        )

        assertEquals(ImageEncodeResult.Success, result)
        val decodedPixel = decodeJpeg(sink.bytes()).getPixel(width / 2, height / 2)
        assertEquals(255, Color.alpha(decodedPixel))
        assertDominantRed(decodedPixel)
    }

    @Test
    fun encodeReadsCanonicalAbsoluteOffsetsFromBufferView() {
        val width = 16
        val height = 16
        val rowStrideBytes = width * 4
        val parent = ByteBuffer.allocate(37 + rowStrideBytes * height)
        repeat(parent.capacity()) { index ->
            parent.put(index, 0x7F.toByte())
        }
        parent.position(37)
        parent.limit(parent.capacity())
        val view = parent.slice()
        writePixels(view, width, height, rowStrideBytes, solidPixels(width, height, Rgba(20, 20, 230)))
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(TestImageEncoderInput(width, height, rowStrideBytes, view), sink)

        assertEquals(ImageEncodeResult.Success, result)
        assertDominantBlueAt(decodeJpeg(sink.bytes()), width / 2, height / 2)
        assertEquals(0, view.position())
        assertEquals(rowStrideBytes * height, view.limit())
    }

    @Test
    fun encodeNonZeroBufferPositionFailsWithoutMutatingBufferState() {
        val width = 16
        val height = 16
        val buffer = rgbaBuffer(width, height, width * 4, solidPixels(width, height, Rgba(20, 20, 230)))
        buffer.position(4)
        buffer.limit(buffer.capacity() - 4)
        val encoder = JpegImageEncoderProvider().createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(TestImageEncoderInput(width, height, width * 4, buffer), sink)

        assertTrue(result is ImageEncodeResult.Failed)
        assertEquals(4, buffer.position())
        assertEquals(buffer.capacity() - 4, buffer.limit())
        assertEquals(0, sink.byteCount)
    }

    @Test
    fun encodeInsufficientLimitFailsWithoutMutatingBufferState() {
        val width = 16
        val height = 16
        val buffer = rgbaBuffer(width, height, width * 4, solidPixels(width, height, Rgba(20, 20, 230)))
        buffer.limit(buffer.capacity() - 1)
        val encoder = JpegImageEncoderProvider().createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(TestImageEncoderInput(width, height, width * 4, buffer), sink)

        assertTrue(result is ImageEncodeResult.Failed)
        assertEquals(0, buffer.position())
        assertEquals(buffer.capacity() - 1, buffer.limit())
        assertEquals(0, sink.byteCount)
    }

    @Test
    fun encodePreservesCanonicalBufferPositionAndLimit() {
        val width = 16
        val height = 16
        val buffer = rgbaBuffer(width, height, width * 4, solidPixels(width, height, Rgba(20, 220, 20)))
        val encoder = JpegImageEncoderProvider(quality = 100).createEncoder(request(width, height))
        val sink = RecordingEncodedImageSink()

        val result = encoder.encode(TestImageEncoderInput(width, height, width * 4, buffer), sink)

        assertEquals(ImageEncodeResult.Success, result)
        assertEquals(0, buffer.position())
        assertEquals(buffer.capacity(), buffer.limit())
    }

    @Test
    fun encodeSinkRejectsFirstWriteReturnsFailed() {
        val result = encodeWithSink(RejectingEncodedImageSink)

        assertTrue(result is ImageEncodeResult.Failed)
    }

    @Test
    fun encodeSinkThrowsReturnsFailed() {
        val result = encodeWithSink(ThrowingEncodedImageSink)

        assertTrue(result is ImageEncodeResult.Failed)
    }

    @Test
    fun encodeDoesNotSwallowSinkOutOfMemoryError() {
        try {
            encodeWithSink(OutOfMemoryEncodedImageSink)
            fail("Expected OutOfMemoryError")
        } catch (error: OutOfMemoryError) {
            assertEquals("sink allocation failed", error.message)
        }
    }

    @Test
    fun encodeRestoresSinkOutOfMemoryErrorWhenPlatformCompressionSwallowsIt() {
        val expected = OutOfMemoryError("sink allocation failed")
        val encoder = createFrameworkJpegEncoder(
            request = request(width = 16, height = 16),
            quality = 80,
            compress = swallowingCompressor,
        )

        try {
            encoder.encode(
                input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
                ThrowingInstanceEncodedImageSink(expected),
            )
            fail("Expected OutOfMemoryError")
        } catch (error: OutOfMemoryError) {
            assertSame(expected, error)
        }
    }

    @Test
    fun encodeRetainsOrdinarySinkFailureWhenPlatformCompressionSwallowsIt() {
        val expected = IllegalStateException("sink failed")
        val encoder = createFrameworkJpegEncoder(
            request = request(width = 16, height = 16),
            quality = 80,
            compress = swallowingCompressor,
        )

        val result = encoder.encode(
            input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
            ThrowingInstanceEncodedImageSink(expected),
        )

        assertTrue(result is ImageEncodeResult.Failed)
        assertSame(expected, (result as ImageEncodeResult.Failed).cause)
    }

    @Test
    fun encodeRetainsSinkRejectionWhenPlatformCompressionSwallowsIt() {
        val encoder = createFrameworkJpegEncoder(
            request = request(width = 16, height = 16),
            quality = 80,
            compress = swallowingCompressor,
        )

        val result = encoder.encode(
            input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
            RejectingEncodedImageSink,
        )

        assertTrue(result is ImageEncodeResult.Failed)
        result as ImageEncodeResult.Failed
        assertEquals("Framework JPEG sink write failed.", result.message)
        assertEquals(null, result.cause)
    }

    @Test
    fun encodeCompressionFalseWithoutSinkFailureReturnsCompressionFailure() {
        val encoder = createFrameworkJpegEncoder(
            request = request(width = 16, height = 16),
            quality = 80,
            compress = { _, _, _ -> false },
        )

        val result = encoder.encode(
            input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
            RecordingEncodedImageSink(),
        )

        assertTrue(result is ImageEncodeResult.Failed)
        result as ImageEncodeResult.Failed
        assertEquals("Framework JPEG compression failed.", result.message)
        assertEquals(null, result.cause)
    }

    @Test
    fun encodePropagatesUnexpectedCompressorError() {
        val expected = AssertionError("compressor failed")
        val encoder = createFrameworkJpegEncoder(
            request = request(width = 16, height = 16),
            quality = 80,
            compress = { _, _, _ -> throw expected },
        )

        try {
            encoder.encode(
                input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
                RecordingEncodedImageSink(),
            )
            fail("Expected AssertionError")
        } catch (error: AssertionError) {
            assertSame(expected, error)
        }
    }

    @Test
    fun encodeSinkRejectsThenFurtherWritesDoNotReachSink() {
        val sink = RejectOnceThenAcceptSink()
        val result = encodeWithSink(sink)

        assertTrue(result is ImageEncodeResult.Failed)
        assertEquals(0, sink.byteCount)
        assertEquals(1, sink.writeCallCount)
    }

    @Test
    fun encodePropagatesConfiguredQualityToFrameworkJpegCompression() {
        val width = 96
        val height = 96
        val pixels = highFrequencyGradientPixels(width, height)

        val lowQualityBytes = encodeJpegBytes(quality = 10, width = width, height = height, pixels = pixels)
        val highQualityBytes = encodeJpegBytes(quality = 95, width = width, height = height, pixels = pixels)

        assertDecodedDimensions(lowQualityBytes, width, height)
        assertDecodedDimensions(highQualityBytes, width, height)
        assertTrue("JPEG quality should affect encoded bytes", !lowQualityBytes.contentEquals(highQualityBytes))
        assertTrue(
            "High-quality JPEG should be meaningfully larger: low=${lowQualityBytes.size}, high=${highQualityBytes.size}",
            highQualityBytes.size > lowQualityBytes.size * 11 / 10,
        )
    }

    @Test
    fun closeIsIdempotent() {
        val encoder = JpegImageEncoderProvider().createEncoder(request(16, 16))

        encoder.close()
        encoder.close()
    }

    @Test
    fun encodeAfterCloseReturnsFailed() {
        val encoder = JpegImageEncoderProvider().createEncoder(request(16, 16))
        encoder.close()

        val result = encoder.encode(
            input(16, 16, pixels = solidPixels(16, 16, Rgba(20, 220, 20))),
            RecordingEncodedImageSink(),
        )

        assertTrue(result is ImageEncodeResult.Failed)
    }

    private fun encodeWithSink(sink: EncodedImageSink): ImageEncodeResult {
        val width = 16
        val height = 16
        val encoder = JpegImageEncoderProvider().createEncoder(request(width, height))
        return encoder.encode(
            input(width, height, pixels = solidPixels(width, height, Rgba(20, 220, 20))),
            sink,
        )
    }

    private fun encodeJpegBytes(quality: Int, width: Int, height: Int, pixels: List<Rgba>): ByteArray {
        val sink = RecordingEncodedImageSink()
        val encoder = JpegImageEncoderProvider(quality = quality).createEncoder(request(width, height))

        val result = encoder.encode(input(width, height, pixels = pixels), sink)

        assertEquals(ImageEncodeResult.Success, result)
        return sink.bytes()
    }

    private fun request(
        width: Int,
        height: Int,
        rowStrideBytes: Int = width * 4,
        maxEncodedBytes: Int = 8 * 1024 * 1024,
    ): ImageEncoderRequest =
        ImageEncoderRequest(
            width = width,
            height = height,
            rowStrideBytes = rowStrideBytes,
            maxEncodedBytes = maxEncodedBytes,
            inputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
        )

    private fun input(
        width: Int,
        height: Int,
        rowStrideBytes: Int = width * 4,
        pixels: List<Rgba>,
        paddingPoison: Byte = 0,
    ): TestImageEncoderInput =
        TestImageEncoderInput(
            width = width,
            height = height,
            rowStrideBytes = rowStrideBytes,
            buffer = rgbaBuffer(width, height, rowStrideBytes, pixels, paddingPoison),
        )

    private fun rgbaBuffer(
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        pixels: List<Rgba>,
        paddingPoison: Byte = 0,
    ): ByteBuffer =
        ByteBuffer.allocate(rowStrideBytes * height).also { buffer ->
            repeat(buffer.capacity()) { index ->
                buffer.put(index, paddingPoison)
            }
            writePixels(buffer, width, height, rowStrideBytes, pixels)
        }

    private fun writePixels(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        pixels: List<Rgba>,
    ) {
        require(pixels.size == width * height)
        pixels.forEachIndexed { index, pixel ->
            val x = index % width
            val y = index / width
            val offset = y * rowStrideBytes + x * 4
            buffer.put(offset, pixel.r.toByte())
            buffer.put(offset + 1, pixel.g.toByte())
            buffer.put(offset + 2, pixel.b.toByte())
            buffer.put(offset + 3, pixel.a.toByte())
        }
    }

    private fun solidPixels(width: Int, height: Int, color: Rgba): List<Rgba> =
        List(width * height) { color }

    private fun quadrantPixels(width: Int, height: Int): List<Rgba> =
        List(width * height) { index ->
            val x = index % width
            val y = index / width
            when {
                x < width / 2 && y < height / 2 -> Rgba(230, 20, 20)
                x >= width / 2 && y < height / 2 -> Rgba(20, 220, 20)
                x < width / 2 -> Rgba(20, 20, 230)
                else -> Rgba(230, 220, 20)
            }
        }

    private fun highFrequencyGradientPixels(width: Int, height: Int): List<Rgba> =
        List(width * height) { index ->
            val x = index % width
            val y = index / width
            val checker = if ((x + y) % 2 == 0) 72 else -72
            Rgba(
                r = ((x * 255 / (width - 1)) + checker).coerceIn(0, 255),
                g = ((y * 255 / (height - 1)) - checker).coerceIn(0, 255),
                b = (((x xor y) * 17) and 0xFF),
                a = 255 - ((x * 3 + y * 5) and 0x7F),
            )
        }

    private fun decodeJpeg(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun assertDecodedDimensions(bytes: ByteArray, width: Int, height: Int) {
        val bitmap = decodeJpeg(bytes)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
    }

    private fun assertDominantRedAt(bitmap: Bitmap, x: Int, y: Int) {
        assertDominantRed(averageColor(bitmap, x, y))
    }

    private fun assertDominantGreenAt(bitmap: Bitmap, x: Int, y: Int) {
        assertDominantGreen(averageColor(bitmap, x, y))
    }

    private fun assertDominantBlueAt(bitmap: Bitmap, x: Int, y: Int) {
        assertDominantBlue(averageColor(bitmap, x, y))
    }

    private fun assertDominantYellowAt(bitmap: Bitmap, x: Int, y: Int) {
        assertDominantYellow(averageColor(bitmap, x, y))
    }

    private fun averageColor(bitmap: Bitmap, centerX: Int, centerY: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (y in (centerY - 2)..(centerY + 2)) {
            for (x in (centerX - 2)..(centerX + 2)) {
                val pixel = bitmap.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count += 1
            }
        }
        return Color.rgb(red / count, green / count, blue / count)
    }

    private fun assertDominantRed(color: Int) {
        assertTrue("red was ${Color.red(color)}", Color.red(color) > 170)
        assertTrue("green was ${Color.green(color)}", Color.green(color) < 120)
        assertTrue("blue was ${Color.blue(color)}", Color.blue(color) < 120)
    }

    private fun assertDominantGreen(color: Int) {
        assertTrue("red was ${Color.red(color)}", Color.red(color) < 140)
        assertTrue("green was ${Color.green(color)}", Color.green(color) > 160)
        assertTrue("blue was ${Color.blue(color)}", Color.blue(color) < 140)
    }

    private fun assertDominantBlue(color: Int) {
        assertTrue("red was ${Color.red(color)}", Color.red(color) < 130)
        assertTrue("green was ${Color.green(color)}", Color.green(color) < 130)
        assertTrue("blue was ${Color.blue(color)}", Color.blue(color) > 160)
    }

    private fun assertDominantYellow(color: Int) {
        assertTrue("red was ${Color.red(color)}", Color.red(color) > 170)
        assertTrue("green was ${Color.green(color)}", Color.green(color) > 150)
        assertTrue("blue was ${Color.blue(color)}", Color.blue(color) < 140)
    }

    private data class Rgba(
        val r: Int,
        val g: Int,
        val b: Int,
        val a: Int = 255,
    )

    private class TestImageEncoderInput(
        override val width: Int,
        override val height: Int,
        override val rowStrideBytes: Int,
        override val buffer: ByteBuffer,
        override val format: ImageEncoderInputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque,
    ) : ImageEncoderInput

    private class RecordingEncodedImageSink(
        override val maxByteCount: Int = 8 * 1024 * 1024,
    ) : EncodedImageSink {
        private val bytes = mutableListOf<Byte>()
        override val byteCount: Int
            get() = bytes.size

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean {
            if (bytes.size + byteCount > maxByteCount) return false
            repeat(byteCount) { index ->
                bytes += source[offset + index]
            }
            return true
        }

        override fun write(source: ByteBuffer, byteCount: Int): Boolean {
            if (bytes.size + byteCount > maxByteCount || source.remaining() < byteCount) return false
            repeat(byteCount) {
                bytes += source.get()
            }
            return true
        }

        fun bytes(): ByteArray = bytes.toByteArray()
    }

    private data object RejectingEncodedImageSink : EncodedImageSink {
        override val byteCount: Int = 0
        override val maxByteCount: Int = 1

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean = false

        override fun write(source: ByteBuffer, byteCount: Int): Boolean = false
    }

    private data object ThrowingEncodedImageSink : EncodedImageSink {
        override val byteCount: Int = 0
        override val maxByteCount: Int = 1

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean =
            throw IllegalStateException("sink failed")

        override fun write(source: ByteBuffer, byteCount: Int): Boolean =
            throw IllegalStateException("sink failed")
    }

    private data object OutOfMemoryEncodedImageSink : EncodedImageSink {
        override val byteCount: Int = 0
        override val maxByteCount: Int = Int.MAX_VALUE

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean =
            throw OutOfMemoryError("sink allocation failed")

        override fun write(source: ByteBuffer, byteCount: Int): Boolean =
            throw OutOfMemoryError("sink allocation failed")
    }

    private class ThrowingInstanceEncodedImageSink(
        private val throwable: Throwable,
    ) : EncodedImageSink {
        override val byteCount: Int = 0
        override val maxByteCount: Int = Int.MAX_VALUE

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean = throw throwable

        override fun write(source: ByteBuffer, byteCount: Int): Boolean = throw throwable
    }

    private class RejectOnceThenAcceptSink : EncodedImageSink {
        private var rejected = false
        override var byteCount: Int = 0
            private set
        var writeCallCount: Int = 0
            private set
        override val maxByteCount: Int = 8 * 1024 * 1024

        override fun write(source: ByteArray, offset: Int, byteCount: Int): Boolean {
            writeCallCount += 1
            if (!rejected) {
                rejected = true
                return false
            }
            this.byteCount += byteCount
            return true
        }

        override fun write(source: ByteBuffer, byteCount: Int): Boolean {
            writeCallCount += 1
            if (!rejected) {
                rejected = true
                return false
            }
            source.position(source.position() + byteCount)
            this.byteCount += byteCount
            return true
        }
    }

    private companion object {
        val swallowingCompressor: (Bitmap, Int, OutputStream) -> Boolean = { _, _, output ->
            try {
                output.write(byteArrayOf(1, 2, 3))
            } catch (_: Throwable) {
                // Android's native Bitmap compressor clears OutputStream throwables and returns false.
            }
            false
        }
    }
}
