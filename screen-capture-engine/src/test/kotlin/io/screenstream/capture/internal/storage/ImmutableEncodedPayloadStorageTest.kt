package io.screenstream.capture.internal.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

internal class ImmutableEncodedPayloadStorageTest {
    // Verification: STO-01
    @Test
    fun copiesOrderedSegmentsAtBothDestinationEdges() {
        val payload = ImmutableEncodedPayload(
            segments = arrayOf(byteArrayOf(1, 2), byteArrayOf(3), byteArrayOf(4, 5)),
            byteCount = 5,
        )

        val atStart = ByteArray(7) { 9 }
        assertEquals(5, payload.copyTo(atStart))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 9, 9), atStart)

        val atEnd = ByteArray(7) { 9 }
        assertEquals(5, payload.copyTo(atEnd, destinationOffset = 2))
        assertArrayEquals(byteArrayOf(9, 9, 1, 2, 3, 4, 5), atEnd)
    }

    // Verification: STO-01
    @Test
    fun invalidDestinationRangeLeavesDestinationUnchanged() {
        val payload = ImmutableEncodedPayload(arrayOf(byteArrayOf(1, 2), byteArrayOf(3)), byteCount = 3)

        val negativeOffset = byteArrayOf(8, 8, 8, 8)
        val negativeBefore = negativeOffset.copyOf()
        assertThrows(IndexOutOfBoundsException::class.java) { payload.copyTo(negativeOffset, -1) }
        assertArrayEquals(negativeBefore, negativeOffset)

        val insufficientTail = byteArrayOf(7, 7, 7, 7)
        val insufficientBefore = insufficientTail.copyOf()
        assertThrows(IndexOutOfBoundsException::class.java) { payload.copyTo(insufficientTail, 2) }
        assertArrayEquals(insufficientBefore, insufficientTail)
    }

    // Verification: STO-01
    @Test
    fun flatteningReturnsIndependentCallerOwnedArrays() {
        val payload = ImmutableEncodedPayload(arrayOf(byteArrayOf(4), byteArrayOf(5, 6)), byteCount = 3)

        val first = payload.toByteArray()
        val second = payload.toByteArray()
        assertNotSame(first, second)
        assertArrayEquals(byteArrayOf(4, 5, 6), first)
        assertArrayEquals(byteArrayOf(4, 5, 6), second)

        first[0] = 99
        assertArrayEquals(byteArrayOf(4, 5, 6), payload.toByteArray())
    }

    // Verification: STO-01
    @Test
    fun constructionRejectsEmptyOrContradictorySegmentGraphs() {
        assertThrows(IllegalArgumentException::class.java) {
            ImmutableEncodedPayload(emptyArray(), byteCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImmutableEncodedPayload(arrayOf(byteArrayOf()), byteCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImmutableEncodedPayload(arrayOf(byteArrayOf(1, 2)), byteCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImmutableEncodedPayload(arrayOf(byteArrayOf(1)), byteCount = 2)
        }

        val oneMiBSegment = ByteArray(1 shl 20)
        val aliasedSegments = Array(4_097) { oneMiBSegment }
        assertEquals((1L shl 32) + oneMiBSegment.size, aliasedSegments.sumOf { it.size.toLong() })
        assertThrows(IllegalArgumentException::class.java) {
            ImmutableEncodedPayload(segments = aliasedSegments, byteCount = oneMiBSegment.size)
        }
    }
}
