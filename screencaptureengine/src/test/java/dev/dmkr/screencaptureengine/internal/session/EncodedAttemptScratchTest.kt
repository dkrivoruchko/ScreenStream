package dev.dmkr.screencaptureengine.internal.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class EncodedAttemptScratchTest {
    @Test
    fun finishSuccessReturnsImmutableAcceptedBytes() {
        val scratch = EncodedAttemptScratch(maxByteCount = 8).begin()
        val source = byteArrayOf(1, 2, 3, 4)

        assertEquals(true, scratch.write(source, offset = 1, byteCount = 2))
        val bytes = scratch.finishSuccess() ?: throw AssertionError("encoded bytes were rejected")
        source[1] = 99

        assertArrayEquals(byteArrayOf(2, 3), bytes)
    }

    @Test
    fun capRejectionIsPermanentAndSuccessAfterRejectionReturnsNull() {
        val scratch = EncodedAttemptScratch(maxByteCount = 4).begin()

        assertEquals(true, scratch.write(byteArrayOf(1, 2, 3), offset = 0, byteCount = 3))
        assertFalse(scratch.write(byteArrayOf(4, 5), offset = 0, byteCount = 2))
        assertFalse(scratch.write(byteArrayOf(6), offset = 0, byteCount = 1))

        assertEquals(3, scratch.byteCount)
        assertEquals(true, scratch.wasRejected)
        assertNull(scratch.finishSuccess())
    }

    @Test
    fun byteBufferCapRejectionDoesNotConsumeSourcePosition() {
        val scratch = EncodedAttemptScratch(maxByteCount = 2).begin()
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3))

        assertFalse(scratch.write(source, byteCount = 3))

        assertEquals(0, source.position())
        assertEquals(0, scratch.byteCount)
        assertEquals(true, scratch.wasRejected)
        scratch.finishDiscard()
    }

    @Test
    fun discardAllowsScratchReuseWithoutPublishingPartialBytes() {
        val scratch = EncodedAttemptScratch(maxByteCount = 8).begin()
        assertEquals(true, scratch.write(byteArrayOf(1, 2, 3), offset = 0, byteCount = 3))
        scratch.finishDiscard()

        scratch.begin()
        assertEquals(true, scratch.write(byteArrayOf(4, 5), offset = 0, byteCount = 2))

        assertArrayEquals(byteArrayOf(4, 5), scratch.finishSuccess())
    }

    @Test
    fun activeScratchCannotBeReusedOrTrimmed() {
        val scratch = EncodedAttemptScratch(maxByteCount = 8).begin()

        assertFails { scratch.begin() }
        assertFails { scratch.trimToSize() }

        scratch.finishDiscard()
        scratch.trimToSize()
        assertEquals(0, scratch.byteCount)
        assertEquals(false, scratch.wasRejected)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("Expected IllegalStateException.")
    }
}
