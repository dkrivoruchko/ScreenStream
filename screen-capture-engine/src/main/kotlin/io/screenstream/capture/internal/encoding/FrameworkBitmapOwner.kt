package io.screenstream.capture.internal.encoding

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import io.screenstream.capture.ScreenCaptureParameters
import io.screenstream.capture.ScreenCaptureProblem
import io.screenstream.capture.internal.Rgba8888Layout
import io.screenstream.capture.internal.isExactWritableRgbaCarrier
import java.nio.ByteBuffer

internal class FrameworkBitmapOwner(internal val layout: Rgba8888Layout) {
    internal sealed interface Creation {
        class Created(internal val owner: FrameworkBitmapOwner) : Creation

        class Failed(
            internal val problem: ScreenCaptureProblem,
            internal val cause: Throwable,
            internal val ownerResidue: FrameworkBitmapOwner?,
        ) : Creation
    }

    private enum class BitmapTransferMode { TightRgbaCopy, RgbaRowConversion, }

    private var bitmap: Bitmap? = null
    private var transferModeSlot: BitmapTransferMode? = null
    private var argbRowScratch: IntArray? = null
    private var useCount: Int = 0
    private var allocationAttempted: Boolean = false
    private var recycleAttempted: Boolean = false
    private var recycleFailure: Throwable? = null

    @SuppressLint("UseKtx")
    internal fun allocateIntoPendingOwner(
        createArgb8888Bitmap: (Int, Int) -> Bitmap = { widthPx, heightPx ->
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        },
        allocateArgbRowScratch: (Int) -> IntArray = { widthPx -> IntArray(widthPx) },
    ): Creation {
        check((!allocationAttempted) && (bitmap == null) && (transferModeSlot == null) && (argbRowScratch == null) && (useCount == 0))
        allocationAttempted = true
        val returnedBitmap = try {
            createArgb8888Bitmap(layout.widthPx, layout.heightPx)
        } catch (allocationFailure: OutOfMemoryError) {
            return Creation.Failed(problem = ScreenCaptureProblem.ResourceExhausted, cause = allocationFailure, ownerResidue = null)
        } catch (failure: Exception) {
            return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, ownerResidue = null)
        }

        bitmap = returnedBitmap
        return completeAfterBitmapReturn(allocateArgbRowScratch)
    }

    internal val isComplete: Boolean
        get() {
            if (bitmap == null) return false
            val exactTransferMode = transferModeSlot ?: return false
            return !recycleAttempted && when (exactTransferMode) {
                BitmapTransferMode.TightRgbaCopy -> argbRowScratch == null
                BitmapTransferMode.RgbaRowConversion -> argbRowScratch?.size == layout.widthPx
            }
        }

    internal val isInUse: Boolean
        get() = useCount != 0

    internal fun beginUse(): Boolean {
        if (useCount != 0 || !isComplete) return false
        useCount = 1
        return true
    }

    internal fun transferExactRgba(source: ByteBuffer) {
        check(useCount == 1)
        check(source.isExactWritableRgbaCarrier(layout.byteCount))
        val exactBitmap = checkNotNull(bitmap)
        when (checkNotNull(transferModeSlot)) {
            BitmapTransferMode.TightRgbaCopy -> exactBitmap.copyPixelsFromBuffer(source)
            BitmapTransferMode.RgbaRowConversion -> {
                val argbRow = checkNotNull(argbRowScratch)
                for (y in 0..<layout.heightPx) {
                    val sourceRowOffset = Math.multiplyExact(y, layout.rowByteCount)
                    for (x in 0..<layout.widthPx) {
                        val pixelOffset = sourceRowOffset + x * Rgba8888Layout.BYTES_PER_PIXEL
                        val red = source[pixelOffset].toInt() and 0xFF
                        val green = source[pixelOffset + 1].toInt() and 0xFF
                        val blue = source[pixelOffset + 2].toInt() and 0xFF
                        argbRow[x] = OPAQUE_ALPHA_MASK or (red shl 16) or (green shl 8) or blue
                    }
                    exactBitmap.setPixels(argbRow, 0, layout.widthPx, 0, y, layout.widthPx, 1)
                }
            }
        }
    }

    internal fun compressOnce(jpegQuality: Int, transaction: FrameworkEncodedTransaction): Boolean {
        check(useCount == 1)
        require(jpegQuality in ScreenCaptureParameters.JPEG_QUALITY_RANGE)
        return checkNotNull(bitmap).compress(Bitmap.CompressFormat.JPEG, jpegQuality, transaction.outputStream)
    }

    internal fun finishUse(): Boolean {
        if (useCount != 1) return false
        useCount = 0
        return true
    }

    internal fun retireIfIdle(): EncodingRetirement {
        if (useCount != 0) return EncodingRetirement.Retained(null)
        val exactBitmap = bitmap ?: return EncodingRetirement.Closed
        if (recycleAttempted) return EncodingRetirement.Retained(recycleFailure)
        recycleAttempted = true
        try {
            exactBitmap.recycle()
            if (!exactBitmap.isRecycled) {
                val failure = IllegalStateException("Bitmap.recycle returned without recycled evidence")
                recycleFailure = failure
                return EncodingRetirement.Retained(failure)
            }
        } catch (failure: Exception) {
            recycleFailure = failure
            return EncodingRetirement.Retained(failure)
        }
        bitmap = null
        transferModeSlot = null
        argbRowScratch = null
        return EncodingRetirement.Closed
    }

    private fun completeAfterBitmapReturn(
        allocateArgbRowScratch: (Int) -> IntArray,
    ): Creation {
        check(transferModeSlot == null && argbRowScratch == null && useCount == 0 && !recycleAttempted)
        val exactBitmap = checkNotNull(bitmap)
        val actualWidthPx: Int
        val actualHeightPx: Int
        val actualConfig: Bitmap.Config
        val actualMutable: Boolean
        val actualRowByteCount: Int
        val actualByteCount: Int
        val actualAllocationByteCount: Int
        val actualIsSrgb: Boolean
        try {
            actualWidthPx = exactBitmap.width
            actualHeightPx = exactBitmap.height
            actualConfig = checkNotNull(exactBitmap.config)
            actualMutable = exactBitmap.isMutable
            actualRowByteCount = exactBitmap.rowBytes
            actualByteCount = exactBitmap.byteCount
            actualAllocationByteCount = exactBitmap.allocationByteCount
            actualIsSrgb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                actualConfig != Bitmap.Config.HARDWARE && exactBitmap.colorSpace == ColorSpace.get(ColorSpace.Named.SRGB)
            } else {
                true
            }

            check(actualWidthPx == layout.widthPx)
            check(actualHeightPx == layout.heightPx)
            check(actualConfig == Bitmap.Config.ARGB_8888)
            check(actualMutable)
            check(!exactBitmap.isRecycled)
            check(actualIsSrgb)
            check(actualRowByteCount >= 0)
            check(actualByteCount >= 0)
            check(actualAllocationByteCount >= 0)
            check(actualRowByteCount >= layout.rowByteCount)
            val checkedBitmapByteCount = Math.multiplyExact(actualRowByteCount.toLong(), actualHeightPx.toLong())
            check(checkedBitmapByteCount <= Int.MAX_VALUE.toLong())
            check(checkedBitmapByteCount == actualByteCount.toLong())
            check(actualByteCount <= actualAllocationByteCount)
        } catch (failure: Exception) {
            return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, ownerResidue = this)
        }

        val transferMode = if (actualRowByteCount == layout.rowByteCount) {
            BitmapTransferMode.TightRgbaCopy
        } else {
            BitmapTransferMode.RgbaRowConversion
        }
        val exactArgbRowScratch = if (transferMode == BitmapTransferMode.RgbaRowConversion) {
            try {
                allocateArgbRowScratch(layout.widthPx)
            } catch (allocationFailure: OutOfMemoryError) {
                return Creation.Failed(problem = ScreenCaptureProblem.ResourceExhausted, cause = allocationFailure, ownerResidue = this)
            } catch (failure: Exception) {
                return Creation.Failed(problem = ScreenCaptureProblem.InternalFailure, cause = failure, ownerResidue = this)
            }
        } else {
            null
        }
        argbRowScratch = exactArgbRowScratch
        transferModeSlot = transferMode
        return Creation.Created(this)
    }

    internal companion object {
        private const val OPAQUE_ALPHA_MASK: Int = -0x1000000
    }
}
