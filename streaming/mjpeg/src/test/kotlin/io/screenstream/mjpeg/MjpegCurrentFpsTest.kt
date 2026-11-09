package io.screenstream.mjpeg

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class MjpegCurrentFpsTest {
    // Cases: MJPEG-CTL-16
    @Test
    fun aFirstReceiptHasNoFrameRateWithoutAPreviousCount() {
        assertNull(calculateMjpegCurrentFps(null, 0L, 1000L))
        assertNull(calculateMjpegCurrentFps(null, 42L, 1500L))
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun positiveIntervalsProduceFractionalAndFastFrameRates() {
        assertEquals(3.5, assertNotNull(calculateMjpegCurrentFps(10L, 17L, 2000L)), 0.0)
        assertEquals(12.5, assertNotNull(calculateMjpegCurrentFps(100L, 105L, 400L)), 0.0)
        assertEquals(1.875, assertNotNull(calculateMjpegCurrentFps(37L, 40L, 1600L)), 0.0)
        assertEquals(1000.0, assertNotNull(calculateMjpegCurrentFps(7L, 8L, 1L)), 0.0)
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun aPositiveIntervalWithNoAdditionalFramesHasZeroFrameRate() {
        assertEquals(0.0, assertNotNull(calculateMjpegCurrentFps(9L, 9L, 500L)), 0.0)
        assertEquals(0.0, assertNotNull(calculateMjpegCurrentFps(0L, 0L, 1L)), 0.0)
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun aDecreasingCounterHasNoFrameRate() {
        assertNull(calculateMjpegCurrentFps(17L, 16L, 1000L))
        assertNull(calculateMjpegCurrentFps(Long.MAX_VALUE, 0L, 1000L))
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun zeroAndNegativeReceiptIntervalsHaveNoFrameRate() {
        assertNull(calculateMjpegCurrentFps(2L, 8L, 0L))
        assertNull(calculateMjpegCurrentFps(2L, 8L, -500L))
        assertNull(calculateMjpegCurrentFps(2L, 8L, Long.MIN_VALUE))
    }

    // Cases: MJPEG-CTL-16
    @Test
    fun largeCountersRetainTheirIntegerDifferenceBeforeConvertingToARate() {
        assertEquals(9.223372036854776E18, assertNotNull(calculateMjpegCurrentFps(0L, Long.MAX_VALUE, 1000L)), 0.0)
        assertEquals(1000.0, assertNotNull(calculateMjpegCurrentFps(Long.MAX_VALUE - 1L, Long.MAX_VALUE, 1L)), 0.0)
        assertEquals(5.0, assertNotNull(calculateMjpegCurrentFps(Long.MAX_VALUE - 10L, Long.MAX_VALUE, 2000L)), 0.0)
    }
}
