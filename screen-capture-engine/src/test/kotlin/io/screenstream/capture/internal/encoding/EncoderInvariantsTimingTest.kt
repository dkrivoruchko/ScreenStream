package io.screenstream.capture.internal.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

internal class EncoderInvariantsTimingTest {
    // Verification: ENC-01
    @Test
    fun encodeDurationIsExactIncludingZeroAndRejectsInvalidClockOrder() {
        assertEquals(0L, checkedJpegEncodeDurationNanos(startedAtNanos = 0L, finishedAtNanos = 0L))
        assertEquals(7L, checkedJpegEncodeDurationNanos(startedAtNanos = 11L, finishedAtNanos = 18L))

        assertThrows(IllegalArgumentException::class.java) {
            checkedJpegEncodeDurationNanos(startedAtNanos = -1L, finishedAtNanos = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkedJpegEncodeDurationNanos(startedAtNanos = 2L, finishedAtNanos = 1L)
        }
    }
}
