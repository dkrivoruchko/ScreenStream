package info.dvkr.screenstream.ui

import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width

internal data class AppPanePlacement(
    val primaryBounds: DpRect,
    val lowerBounds: DpRect? = null,
    val secondaryBounds: DpRect? = null,
    val dividerBounds: DpRect? = null,
    val usesHingeBounds: Boolean = false,
) {
    val primaryLayoutBounds = lowerBounds?.let { primaryBounds.copy(bottom = it.bottom) } ?: primaryBounds
    val tabletopGap = lowerBounds?.let { it.top - primaryBounds.bottom }
}

internal fun Density.calculateAppPanePlacement(
    viewportSizePx: IntSize,
    viewportOriginPx: Offset,
    hinges: List<HingeInfo>,
    allowWidthBasedSplit: Boolean,
): AppPanePlacement {
    val viewportBounds = DpRect(0.dp, 0.dp, viewportSizePx.width.toDp(), viewportSizePx.height.toDp())
    // When the system reports several folds, lay out around the first eligible one.
    for (hinge in hinges) {
        if (!hinge.isSeparating && !hinge.isOccluding) continue

        val hingeBoundsPx = hinge.bounds.translate(-viewportOriginPx)
        val viewportLength = if (hinge.isVertical) viewportBounds.width else viewportBounds.height
        val firstHingeEdge = (if (hinge.isVertical) hingeBoundsPx.left else hingeBoundsPx.top).toDp()
        val secondHingeEdge = (if (hinge.isVertical) hingeBoundsPx.right else hingeBoundsPx.bottom).toDp()
        val secondPaneSize = viewportLength - secondHingeEdge
        // A partly cropped hinge can still leave a usable pane on one side.
        val largestPaneSize = maxOf(firstHingeEdge, secondPaneSize)
        if (largestPaneSize <= 0.dp || largestPaneSize >= viewportLength) continue

        val minimumPaneSize = if (hinge.isVertical) 280.dp else 120.dp
        val firstPaneBounds = if (hinge.isVertical) viewportBounds.copy(right = firstHingeEdge) else viewportBounds.copy(bottom = firstHingeEdge)
        val secondPaneBounds = if (hinge.isVertical) viewportBounds.copy(left = secondHingeEdge) else viewportBounds.copy(top = secondHingeEdge)
        if (minOf(firstHingeEdge, secondPaneSize) < minimumPaneSize) {
            return AppPanePlacement(
                primaryBounds = if (firstHingeEdge >= secondPaneSize) firstPaneBounds else secondPaneBounds,
                usesHingeBounds = true,
            )
        }

        return if (hinge.isVertical) {
            AppPanePlacement(
                primaryBounds = firstPaneBounds,
                secondaryBounds = secondPaneBounds,
                dividerBounds =
                    if (hinge.isOccluding) null else secondPaneBounds.copy(right = secondPaneBounds.left + DividerDefaults.Thickness),
                usesHingeBounds = true,
            )
        } else {
            AppPanePlacement(primaryBounds = firstPaneBounds, lowerBounds = secondPaneBounds, usesHingeBounds = true)
        }
    }

    if (!allowWidthBasedSplit) return AppPanePlacement(primaryBounds = viewportBounds)

    val dividerWidthPx = DividerDefaults.Thickness.roundToPx().coerceAtMost(viewportSizePx.width)
    // Match the equal-weight Row: the settings pane gets the extra pixel on odd splits.
    val dividerLeftPx = (viewportSizePx.width - dividerWidthPx) / 2
    val dividerLeft = dividerLeftPx.toDp()
    val dividerRight = (dividerLeftPx + dividerWidthPx).toDp()
    return AppPanePlacement(
        primaryBounds = viewportBounds.copy(right = dividerLeft),
        secondaryBounds = viewportBounds.copy(left = dividerRight),
        dividerBounds = viewportBounds.copy(left = dividerLeft, right = dividerRight),
    )
}
