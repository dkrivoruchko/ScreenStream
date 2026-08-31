package io.screenstream.capture.internal

import io.screenstream.capture.SourceRegion

internal class SourceRegionBounds private constructor(
    internal val leftPx: Int,
    internal val rightPx: Int,
) {
    internal val widthPx: Int
        get() = rightPx - leftPx

    internal companion object {
        internal const val MIN_HALF_REGION_WIDTH_PX: Int = 2

        internal fun resolve(sourceRegion: SourceRegion, sourceWidthPx: Int): SourceRegionBounds? {
            if (sourceWidthPx <= 0) return null
            if ((sourceRegion != SourceRegion.Full) && (sourceWidthPx < MIN_HALF_REGION_WIDTH_PX)) return null

            val midpointPx = sourceWidthPx / 2
            return when (sourceRegion) {
                SourceRegion.Full -> SourceRegionBounds(leftPx = 0, rightPx = sourceWidthPx)
                SourceRegion.LeftHalf -> SourceRegionBounds(leftPx = 0, rightPx = midpointPx)
                SourceRegion.RightHalf -> SourceRegionBounds(leftPx = midpointPx, rightPx = sourceWidthPx)
            }
        }
    }
}
