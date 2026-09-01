package io.screenstream.capture.internal

import io.screenstream.capture.SourceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class SourceRegionBoundsGeometryTest {
    // Verification: SES-04
    @Test
    fun fullRegionOwnsTheOnlyColumnOfAOnePixelSource() {
        val bounds = requireBounds(SourceRegion.Full, sourceWidthPx = 1)

        assertEquals(0, bounds.leftPx)
        assertEquals(1, bounds.rightPx)
        assertEquals(1, bounds.widthPx)
    }

    // Verification: SES-04
    @Test
    fun halfRegionsSplitTheMinimumEvenWidthWithoutOverlap() {
        val left = requireBounds(SourceRegion.LeftHalf, sourceWidthPx = 2)
        val right = requireBounds(SourceRegion.RightHalf, sourceWidthPx = 2)

        assertEquals(0, left.leftPx)
        assertEquals(1, left.rightPx)
        assertEquals(1, left.widthPx)
        assertEquals(1, right.leftPx)
        assertEquals(2, right.rightPx)
        assertEquals(1, right.widthPx)
    }

    // Verification: SES-04
    @Test
    fun oddWidthAssignsTheFinalColumnToTheRightHalf() {
        val left = requireBounds(SourceRegion.LeftHalf, sourceWidthPx = 5)
        val right = requireBounds(SourceRegion.RightHalf, sourceWidthPx = 5)

        assertEquals(0, left.leftPx)
        assertEquals(2, left.rightPx)
        assertEquals(2, left.widthPx)
        assertEquals(2, right.leftPx)
        assertEquals(5, right.rightPx)
        assertEquals(3, right.widthPx)
    }

    // Verification: SES-04
    @Test
    fun nonpositiveSourcesAndOnePixelHalfRegionsAreUnavailable() {
        listOf(0, -1).forEach { sourceWidthPx ->
            assertNull(SourceRegionBounds.resolve(SourceRegion.Full, sourceWidthPx))
        }
        assertNull(SourceRegionBounds.resolve(SourceRegion.LeftHalf, sourceWidthPx = 1))
        assertNull(SourceRegionBounds.resolve(SourceRegion.RightHalf, sourceWidthPx = 1))
    }

    private fun requireBounds(sourceRegion: SourceRegion, sourceWidthPx: Int): SourceRegionBounds =
        SourceRegionBounds.resolve(sourceRegion, sourceWidthPx) ?: error("expected source bounds")
}
