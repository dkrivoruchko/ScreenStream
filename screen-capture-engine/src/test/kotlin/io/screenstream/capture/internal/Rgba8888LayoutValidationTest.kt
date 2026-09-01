package io.screenstream.capture.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

internal class Rgba8888LayoutValidationTest {
    // Verification: SES-04
    // Verification: CAP-02
    @Test
    fun createUsesCheckedRgbaRowAndCarrierByteCounts() {
        val onePixel = Rgba8888Layout.create(widthPx = 1, heightPx = 1)
        assertEquals(4, onePixel.rowByteCount)
        assertEquals(4, onePixel.byteCount)

        val largestAddressableRow = Rgba8888Layout.create(
            widthPx = Int.MAX_VALUE / Rgba8888Layout.BYTES_PER_PIXEL,
            heightPx = 1,
        )
        assertEquals(Int.MAX_VALUE - 3, largestAddressableRow.rowByteCount)
        assertEquals(Int.MAX_VALUE - 3, largestAddressableRow.byteCount)

        assertThrows(ArithmeticException::class.java) {
            Rgba8888Layout.create(
                widthPx = (Int.MAX_VALUE / Rgba8888Layout.BYTES_PER_PIXEL) + 1,
                heightPx = 1,
            )
        }
        assertThrows(ArithmeticException::class.java) {
            Rgba8888Layout.create(
                widthPx = Int.MAX_VALUE / Rgba8888Layout.BYTES_PER_PIXEL,
                heightPx = 2,
            )
        }
    }

    // Verification: SES-04
    // Verification: CAP-02
    @Test
    fun createRequiresPositiveDimensionsAndComparesAllAddressedDimensions() {
        listOf(0, -1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                Rgba8888Layout.create(widthPx = invalid, heightPx = 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                Rgba8888Layout.create(widthPx = 1, heightPx = invalid)
            }
        }

        val layout = Rgba8888Layout.create(widthPx = 3, heightPx = 2)
        assertTrue(layout.hasSameDimensionsAs(Rgba8888Layout.create(widthPx = 3, heightPx = 2)))
        assertFalse(layout.hasSameDimensionsAs(Rgba8888Layout.create(widthPx = 2, heightPx = 3)))
    }

    // Verification: CAP-04
    @Test
    fun exactWritableCarrierRequiresExactDirectMutableZeroPositionShape() {
        val expectedByteCount = 16
        assertTrue(ByteBuffer.allocateDirect(expectedByteCount).isExactWritableRgbaCarrier(expectedByteCount))
        assertFalse(ByteBuffer.allocate(expectedByteCount).isExactWritableRgbaCarrier(expectedByteCount))
        assertFalse(
            ByteBuffer.allocateDirect(expectedByteCount)
                .asReadOnlyBuffer()
                .isExactWritableRgbaCarrier(expectedByteCount),
        )
        assertFalse(
            ByteBuffer.allocateDirect(expectedByteCount).apply { position(1) }
                .isExactWritableRgbaCarrier(expectedByteCount),
        )
        assertFalse(
            ByteBuffer.allocateDirect(expectedByteCount).apply { limit(expectedByteCount - 1) }
                .isExactWritableRgbaCarrier(expectedByteCount),
        )
        assertFalse(
            ByteBuffer.allocateDirect(expectedByteCount + 1).apply { limit(expectedByteCount) }
                .isExactWritableRgbaCarrier(expectedByteCount),
        )
        assertFalse(ByteBuffer.allocateDirect(expectedByteCount + 1).isExactWritableRgbaCarrier(expectedByteCount))
        assertFalse(ByteBuffer.allocateDirect(expectedByteCount).isExactWritableRgbaCarrier(0))
        assertFalse(ByteBuffer.allocateDirect(expectedByteCount).isExactWritableRgbaCarrier(-1))
    }
}
