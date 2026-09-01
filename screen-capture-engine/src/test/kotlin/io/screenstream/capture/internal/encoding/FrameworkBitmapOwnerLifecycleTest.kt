package io.screenstream.capture.internal.encoding

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import io.mockk.every
import io.mockk.spyk
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
internal class FrameworkBitmapOwnerLifecycleTest {
    // Verification: ENC-06
    @Test
    fun bitmapCreationClassifiesExhaustionAndInternalFailureWithoutResidue() {
        fun assertFailure(failure: Throwable, expectedProblem: ScreenCaptureProblem) {
            val owner = FrameworkBitmapOwner(Rgba8888Layout.create(widthPx = 2, heightPx = 2))

            val creation = owner.allocateIntoPendingOwner(
                createArgb8888Bitmap = { _, _ -> throw failure },
            ) as FrameworkBitmapOwner.Creation.Failed

            assertSame(expectedProblem, creation.problem)
            assertSame(failure, creation.cause)
            assertNull(creation.ownerResidue)
        }

        assertFailure(OutOfMemoryError("bitmap allocation exhausted"), ScreenCaptureProblem.ResourceExhausted)
        assertFailure(IllegalStateException("bitmap allocation failed"), ScreenCaptureProblem.InternalFailure)
    }

    // Verification: ENC-06
    @Test
    fun rowScratchAllocationClassifiesFailuresAndRetainsBitmapForRetirement() {
        fun assertFailure(failure: Throwable, expectedProblem: ScreenCaptureProblem) {
            val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
            val paddedRowByteCount = layout.rowByteCount + Rgba8888Layout.BYTES_PER_PIXEL
            val paddedByteCount = paddedRowByteCount * layout.heightPx
            val bitmap = spyk(Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888))
            every { bitmap.rowBytes } returns paddedRowByteCount
            every { bitmap.byteCount } returns paddedByteCount
            every { bitmap.allocationByteCount } returns paddedByteCount
            val owner = FrameworkBitmapOwner(layout)

            try {
                val creation = owner.allocateIntoPendingOwner(
                    createArgb8888Bitmap = { _, _ -> bitmap },
                    allocateArgbRowScratch = { throw failure },
                ) as FrameworkBitmapOwner.Creation.Failed

                assertSame(expectedProblem, creation.problem)
                assertSame(failure, creation.cause)
                assertSame(owner, creation.ownerResidue)
            } finally {
                assertSame(EncodingRetirement.Closed, owner.retireIfIdle())
                assertSame(EncodingRetirement.Closed, owner.retireIfIdle())
            }
            assertTrue(bitmap.isRecycled)
        }

        assertFailure(OutOfMemoryError("row scratch allocation exhausted"), ScreenCaptureProblem.ResourceExhausted)
        assertFailure(IllegalStateException("row scratch allocation failed"), ScreenCaptureProblem.InternalFailure)
    }

    // Verification: ENC-06
    @Test
    fun paddedRgbaTransferPreservesExactVisibleBitmapPixels() {
        val layout = Rgba8888Layout.create(widthPx = WIDTH_PX, heightPx = HEIGHT_PX)
        val paddedRowByteCount = layout.rowByteCount + Rgba8888Layout.BYTES_PER_PIXEL
        val paddedByteCount = paddedRowByteCount * layout.heightPx
        val bitmap = spyk(Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888))
        every { bitmap.rowBytes } returns paddedRowByteCount
        every { bitmap.byteCount } returns paddedByteCount
        every { bitmap.allocationByteCount } returns paddedByteCount
        val owner = FrameworkBitmapOwner(layout)
        val carrier = ByteBuffer.allocateDirect(layout.byteCount)
        fillTilePattern(carrier, layout)
        assertExactCarrierShape(carrier, layout)
        var useFinished = false

        try {
            val creationResult = owner.allocateIntoPendingOwner(
                createArgb8888Bitmap = { _, _ -> bitmap },
            )
            if (creationResult !is FrameworkBitmapOwner.Creation.Created) {
                fail("coherent padded Bitmap was rejected")
            }
            val creation = creationResult as FrameworkBitmapOwner.Creation.Created
            assertSame(owner, creation.owner)
            assertTrue(owner.beginUse())

            owner.transferExactRgba(carrier)
            assertExactCarrierShape(carrier, layout)
            assertExactVisiblePixels(bitmap, layout)

            assertTrue(owner.finishUse())
            useFinished = true
        } finally {
            if (!useFinished) owner.finishUse()
            assertSame(EncodingRetirement.Closed, owner.retireIfIdle())
        }
        assertTrue(bitmap.isRecycled)
    }

    // Verification: ENC-06
    @Test
    @Config(
        manifest = Config.NONE,
        sdk = [Build.VERSION_CODES.N, Build.VERSION_CODES.O, Build.VERSION_CODES.BAKLAVA],
    )
    fun invalidBitmapAdoptionShapesAreRejectedOnApplicableApiBands() {
        val layout = Rgba8888Layout.create(widthPx = 2, heightPx = 2)
        val exactByteCount = layout.rowByteCount * layout.heightPx
        val cases = mutableListOf(
            InvalidBitmapCase("wrong width") {
                Bitmap.createBitmap(layout.widthPx + 1, layout.heightPx, Bitmap.Config.ARGB_8888)
            },
            InvalidBitmapCase("wrong height") {
                Bitmap.createBitmap(layout.widthPx, layout.heightPx + 1, Bitmap.Config.ARGB_8888)
            },
            InvalidBitmapCase("non-ARGB_8888 config") {
                Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.RGB_565)
            },
            InvalidBitmapCase("immutable") {
                newValidBitmap(layout).also { bitmap -> shadowOf(bitmap).setMutable(false) }
            },
            InvalidBitmapCase("recycled") {
                newValidBitmap(layout).apply { recycle() }
            },
            InvalidBitmapCase("negative row byte count") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.rowBytes } returns -1 }
            },
            InvalidBitmapCase("negative byte count") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.byteCount } returns -1 }
            },
            InvalidBitmapCase("negative allocation byte count") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.allocationByteCount } returns -1 }
            },
            InvalidBitmapCase("row narrower than tight RGBA") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.rowBytes } returns layout.rowByteCount - 1 }
            },
            InvalidBitmapCase("row storage exceeds Int range") {
                spyk(newValidBitmap(layout)).also { bitmap ->
                    every { bitmap.rowBytes } returns Int.MAX_VALUE
                    every { bitmap.byteCount } returns Int.MAX_VALUE
                    every { bitmap.allocationByteCount } returns Int.MAX_VALUE
                }
            },
            InvalidBitmapCase("byte count differs from row storage") {
                spyk(newValidBitmap(layout)).also { bitmap ->
                    every { bitmap.byteCount } returns exactByteCount + 1
                    every { bitmap.allocationByteCount } returns exactByteCount + 1
                }
            },
            InvalidBitmapCase("allocation is smaller than byte count") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.allocationByteCount } returns exactByteCount - 1 }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            cases += InvalidBitmapCase("non-sRGB color space") {
                Bitmap.createBitmap(
                    layout.widthPx,
                    layout.heightPx,
                    Bitmap.Config.ARGB_8888,
                    true,
                    ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
                )
            }
            cases += InvalidBitmapCase("null color space") {
                spyk(newValidBitmap(layout)).also { bitmap -> every { bitmap.colorSpace } returns null }
            }
        }

        for (case in cases) {
            val bitmap = case.createBitmap()
            val owner = FrameworkBitmapOwner(layout)
            try {
                val creationResult = owner.allocateIntoPendingOwner(
                    createArgb8888Bitmap = { _, _ -> bitmap },
                )
                if (creationResult !is FrameworkBitmapOwner.Creation.Failed) {
                    fail("${case.name}: invalid Bitmap was adopted")
                }
                val creation = creationResult as FrameworkBitmapOwner.Creation.Failed

                assertSame(case.name, ScreenCaptureProblem.InternalFailure, creation.problem)
                assertSame(case.name, owner, creation.ownerResidue)
            } finally {
                assertSame(case.name, EncodingRetirement.Closed, owner.retireIfIdle())
            }
            assertTrue("${case.name}: Bitmap was not recycled", bitmap.isRecycled)
        }
    }

    private fun newValidBitmap(layout: Rgba8888Layout): Bitmap =
        Bitmap.createBitmap(layout.widthPx, layout.heightPx, Bitmap.Config.ARGB_8888)

    private fun fillTilePattern(carrier: ByteBuffer, layout: Rgba8888Layout) {
        for (y in 0 until layout.heightPx) {
            for (x in 0 until layout.widthPx) {
                val rgb = TILE_COLORS[y / TILE_SIZE_PX][x / TILE_SIZE_PX]
                val offset = (y * layout.rowByteCount) + (x * Rgba8888Layout.BYTES_PER_PIXEL)
                carrier.put(offset, (rgb ushr 16).toByte())
                carrier.put(offset + 1, (rgb ushr 8).toByte())
                carrier.put(offset + 2, rgb.toByte())
                carrier.put(offset + 3, 0xFF.toByte())
            }
        }
    }

    private fun assertExactCarrierShape(carrier: ByteBuffer, layout: Rgba8888Layout) {
        assertTrue(carrier.isDirect)
        assertFalse(carrier.isReadOnly)
        assertEquals(0, carrier.position())
        assertEquals(layout.byteCount, carrier.limit())
        assertEquals(layout.byteCount, carrier.capacity())
    }

    private fun assertExactVisiblePixels(bitmap: Bitmap, layout: Rgba8888Layout) {
        val pixels = IntArray(layout.widthPx * layout.heightPx)
        bitmap.getPixels(pixels, 0, layout.widthPx, 0, 0, layout.widthPx, layout.heightPx)
        for (y in 0 until layout.heightPx) {
            for (x in 0 until layout.widthPx) {
                val expected = OPAQUE_ALPHA_MASK or TILE_COLORS[y / TILE_SIZE_PX][x / TILE_SIZE_PX]
                assertEquals("pixel=($x,$y)", expected, pixels[(y * layout.widthPx) + x])
            }
        }
    }

    private class InvalidBitmapCase(
        val name: String,
        val createBitmap: () -> Bitmap,
    )

    private companion object {
        private const val WIDTH_PX: Int = 64
        private const val HEIGHT_PX: Int = 48
        private const val TILE_SIZE_PX: Int = 16
        private const val OPAQUE_ALPHA_MASK: Int = -0x1000000

        private val TILE_COLORS: Array<IntArray> = arrayOf(
            intArrayOf(0xE02020, 0xB34D26, 0x20B0C0, 0x20C040),
            intArrayOf(0xC020C0, 0x404040, 0x808080, 0xD0D0D0),
            intArrayOf(0x2040E0, 0x7030B0, 0x26994D, 0xE0C020),
        )
    }
}
