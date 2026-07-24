package io.screenstream.engine.internal.storage

/** Immutable segmented JPEG bytes. Neither the arrays nor their reference container escape this owner. */
internal class ImmutableEncodedPayload internal constructor(
    private val segments: Array<ByteArray>,
    internal val byteCount: Int,
) {
    init {
        require(byteCount > 0)
        require(segments.isNotEmpty())

        var validatedByteCount = 0L
        for (segment in segments) {
            require(segment.isNotEmpty())
            validatedByteCount += segment.size.toLong()
            require(validatedByteCount <= Int.MAX_VALUE.toLong())
        }
        require(validatedByteCount == byteCount.toLong())
    }

    internal fun copyTo(destination: ByteArray, destinationOffset: Int = 0): Int {
        val copyEndExclusive = destinationOffset.toLong() + byteCount.toLong()
        if (destinationOffset < 0 || copyEndExclusive > destination.size.toLong()) {
            throw IndexOutOfBoundsException(
                "destinationOffset=$destinationOffset, byteCount=$byteCount, destinationSize=${destination.size}",
            )
        }

        var writeOffset = destinationOffset
        for (segment in segments) {
            System.arraycopy(segment, 0, destination, writeOffset, segment.size)
            writeOffset += segment.size
        }
        return byteCount
    }

    internal fun copyBytes(): ByteArray {
        val destination = ByteArray(byteCount)
        copyTo(destination)
        return destination
    }
}
