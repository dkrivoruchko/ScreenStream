package info.dvkr.screenstream.ui.main

import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

internal data class MainPanePlacement(
    val primary: DpRect,
    val lower: DpRect? = null,
    val settings: DpRect? = null,
    val divider: DpRect? = null,
    val isFolded: Boolean = false,
) {
    val primaryFrame = lower?.let { primary.copy(bottom = it.bottom) } ?: primary
    val tabletopGap = lower?.let { it.top - primary.bottom }
}

internal fun Density.calculateMainPanePlacement(
    viewportSizePx: IntSize,
    viewportOriginPx: Offset,
    hinges: List<HingeInfo>,
    splitFlatWindow: Boolean,
): MainPanePlacement {
    val viewportBounds = DpRect(0.dp, 0.dp, viewportSizePx.width.toDp(), viewportSizePx.height.toDp())
    // When the system reports several folds, lay out around the first eligible one.
    for (hinge in hinges) {
        if (!hinge.isSeparating && !hinge.isOccluding) continue

        val isVertical = hinge.isVertical
        val hingeBoundsPx = hinge.bounds.translate(-viewportOriginPx)
        val viewportLength = if (isVertical) viewportBounds.width else viewportBounds.height
        val firstHingeEdge = (if (isVertical) hingeBoundsPx.left else hingeBoundsPx.top).toDp()
        val secondHingeEdge = (if (isVertical) hingeBoundsPx.right else hingeBoundsPx.bottom).toDp()
        val secondPaneSize = viewportLength - secondHingeEdge
        // A partly cropped hinge can still leave a usable pane on one side.
        val largestPaneSize = maxOf(firstHingeEdge, secondPaneSize)
        if (largestPaneSize <= 0.dp || largestPaneSize >= viewportLength) continue

        val minimumPaneSize = if (isVertical) 280.dp else 120.dp
        val firstPaneBounds = if (isVertical) viewportBounds.copy(right = firstHingeEdge) else viewportBounds.copy(bottom = firstHingeEdge)
        val secondPaneBounds = if (isVertical) viewportBounds.copy(left = secondHingeEdge) else viewportBounds.copy(top = secondHingeEdge)
        if (minOf(firstHingeEdge, secondPaneSize) < minimumPaneSize) {
            val largerPane = if (firstHingeEdge >= secondPaneSize) firstPaneBounds else secondPaneBounds
            return MainPanePlacement(primary = largerPane, isFolded = true)
        }

        return if (isVertical) {
            MainPanePlacement(
                primary = firstPaneBounds,
                settings = secondPaneBounds,
                divider = if (hinge.isOccluding) null else secondPaneBounds.copy(
                    right = secondPaneBounds.left + DividerDefaults.Thickness,
                ),
                isFolded = true,
            )
        } else {
            MainPanePlacement(primary = firstPaneBounds, lower = secondPaneBounds, isFolded = true)
        }
    }

    if (!splitFlatWindow) return MainPanePlacement(primary = viewportBounds)

    val dividerWidthPx = DividerDefaults.Thickness.roundToPx().coerceAtMost(viewportSizePx.width)
    // Match the equal-weight Row: the settings pane gets the extra pixel on odd splits.
    val dividerLeftPx = (viewportSizePx.width - dividerWidthPx) / 2
    val dividerLeft = dividerLeftPx.toDp()
    val dividerRight = (dividerLeftPx + dividerWidthPx).toDp()
    return MainPanePlacement(
        primary = viewportBounds.copy(right = dividerLeft),
        settings = viewportBounds.copy(left = dividerRight),
        divider = viewportBounds.copy(left = dividerLeft, right = dividerRight),
    )
}
