package io.screenstream.capture.internal

import java.nio.ByteBuffer

internal class Rgba8888Layout private constructor(
    internal val widthPx: Int,
    internal val heightPx: Int,
    internal val rowByteCount: Int,
    internal val byteCount: Int,
) {
    internal fun hasSameDimensionsAs(other: Rgba8888Layout): Boolean =
        (widthPx == other.widthPx) && (heightPx == other.heightPx) && (byteCount == other.byteCount)

    internal companion object {
        internal const val BYTES_PER_PIXEL: Int = 4

        internal fun create(widthPx: Int, heightPx: Int): Rgba8888Layout {
            require(widthPx > 0)
            require(heightPx > 0)
            val rowByteCount = Math.multiplyExact(widthPx.toLong(), BYTES_PER_PIXEL.toLong())
            if (rowByteCount > Int.MAX_VALUE.toLong()) {
                throw ArithmeticException("RGBA row exceeds the addressable Int range")
            }
            val byteCount = Math.multiplyExact(rowByteCount, heightPx.toLong())
            if (byteCount > Int.MAX_VALUE.toLong()) {
                throw ArithmeticException("RGBA carrier exceeds the addressable Int range")
            }
            return Rgba8888Layout(
                widthPx = widthPx,
                heightPx = heightPx,
                rowByteCount = rowByteCount.toInt(),
                byteCount = byteCount.toInt(),
            )
        }
    }
}

internal fun ByteBuffer.isExactWritableRgbaCarrier(expectedByteCount: Int): Boolean =
    (expectedByteCount > 0) && isDirect && !isReadOnly && (position() == 0) &&
            (limit() == expectedByteCount) && (capacity() == expectedByteCount)
