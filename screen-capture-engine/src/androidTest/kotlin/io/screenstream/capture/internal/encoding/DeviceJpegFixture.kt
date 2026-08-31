package io.screenstream.capture.internal.encoding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.storage.ImmutableEncodedPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object DeviceJpegFixture {
    internal const val WIDTH_PX: Int = 64
    internal const val HEIGHT_PX: Int = 48
    internal const val JPEG_QUALITY: Int = 80
    private const val TILE_SIZE_PX: Int = 16
    private const val INTERIOR_START_PX: Int = 4
    private const val INTERIOR_END_EXCLUSIVE_PX: Int = 12

    internal val layout: Rgba8888Layout = Rgba8888Layout.create(WIDTH_PX, HEIGHT_PX)

    private val tileColors: Array<IntArray> = arrayOf(
        intArrayOf(0xE02020, 0xB34D26, 0x20B0C0, 0x20C040),
        intArrayOf(0xC020C0, 0x404040, 0x808080, 0xD0D0D0),
        intArrayOf(0x2040E0, 0x7030B0, 0x26994D, 0xE0C020),
    )

    internal fun fill(carrier: ByteBuffer) {
        assertTrue(carrier.isDirect)
        assertEquals(0, carrier.position())
        assertEquals(layout.byteCount, carrier.limit())
        assertEquals(layout.byteCount, carrier.capacity())
        for (y in 0 until HEIGHT_PX) {
            for (x in 0 until WIDTH_PX) {
                val rgb = tileColors[y / TILE_SIZE_PX][x / TILE_SIZE_PX]
                val offset = (y * layout.rowByteCount) + (x * Rgba8888Layout.BYTES_PER_PIXEL)
                carrier.put(offset, (rgb ushr 16).toByte())
                carrier.put(offset + 1, (rgb ushr 8).toByte())
                carrier.put(offset + 2, rgb.toByte())
                carrier.put(offset + 3, 0xFF.toByte())
            }
        }
        assertEquals(0, carrier.position())
    }

    internal fun assertPayload(payload: ImmutableEncodedPayload) {
        assertTrue(payload.byteCount > 0)
        val firstCopy = payload.toByteArray()
        val originalFirstByte = firstCopy[0]
        firstCopy[0] = (firstCopy[0].toInt() xor 0xFF).toByte()
        val secondCopy = payload.toByteArray()
        assertNotSame(firstCopy, secondCopy)
        assertEquals(originalFirstByte, secondCopy[0])

        val decoded = BitmapFactory.decodeByteArray(secondCopy, 0, secondCopy.size)
            ?: error("The platform Bitmap decoder rejected the produced JPEG")
        try {
            assertDecoded(decoded)
        } finally {
            decoded.recycle()
        }
    }

    private fun assertDecoded(decoded: Bitmap) {
        assertEquals(WIDTH_PX, decoded.width)
        assertEquals(HEIGHT_PX, decoded.height)
        val pixels = IntArray(WIDTH_PX * HEIGHT_PX)
        decoded.getPixels(pixels, 0, WIDTH_PX, 0, 0, WIDTH_PX, HEIGHT_PX)
        for (pixel in pixels) {
            assertEquals(0xFF, pixel ushr 24)
        }

        val grayMeans = DoubleArray(3)
        for (tileRow in tileColors.indices) {
            for (tileColumn in tileColors[tileRow].indices) {
                val expected = tileColors[tileRow][tileColumn]
                val expectedChannels = intArrayOf(expected ushr 16, (expected ushr 8) and 0xFF, expected and 0xFF)
                val channelErrors = LongArray(3)
                val channelSums = LongArray(3)
                var sampleCount = 0
                for (interiorY in INTERIOR_START_PX until INTERIOR_END_EXCLUSIVE_PX) {
                    val y = (tileRow * TILE_SIZE_PX) + interiorY
                    val rowErrors = LongArray(3)
                    for (interiorX in INTERIOR_START_PX until INTERIOR_END_EXCLUSIVE_PX) {
                        val x = (tileColumn * TILE_SIZE_PX) + interiorX
                        val pixel = pixels[(y * WIDTH_PX) + x]
                        val actualChannels = intArrayOf((pixel ushr 16) and 0xFF, (pixel ushr 8) and 0xFF, pixel and 0xFF)
                        for (channel in 0..2) {
                            val error = abs(actualChannels[channel] - expectedChannels[channel])
                            channelErrors[channel] += error.toLong()
                            rowErrors[channel] += error.toLong()
                            channelSums[channel] += actualChannels[channel].toLong()
                        }
                        sampleCount += 1
                    }
                    for (channel in 0..2) {
                        assertTrue(
                            "tile=($tileColumn,$tileRow), row=$interiorY, channel=$channel exceeded row MAE",
                            rowErrors[channel].toDouble() / INTERIOR_WIDTH_PX <= 36.0,
                        )
                    }
                }
                for (channel in 0..2) {
                    assertTrue(
                        "tile=($tileColumn,$tileRow), channel=$channel exceeded interior MAE",
                        channelErrors[channel].toDouble() / sampleCount <= 24.0,
                    )
                }

                if ((tileRow == 1) && (tileColumn in 1..3)) {
                    val means = DoubleArray(3) { channel -> channelSums[channel].toDouble() / sampleCount }
                    val spread = max(means[0], max(means[1], means[2])) - min(means[0], min(means[1], means[2]))
                    assertTrue("gray tile channel spread exceeded 8", spread <= 8.0)
                    grayMeans[tileColumn - 1] = means.average()
                }
            }
        }
        assertTrue(grayMeans[1] - grayMeans[0] >= 32.0)
        assertTrue(grayMeans[2] - grayMeans[1] >= 32.0)
    }

    private const val INTERIOR_WIDTH_PX: Int = INTERIOR_END_EXCLUSIVE_PX - INTERIOR_START_PX
}
