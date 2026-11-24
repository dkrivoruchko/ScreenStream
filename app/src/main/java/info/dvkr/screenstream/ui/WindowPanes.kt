package info.dvkr.screenstream.ui

import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.window.core.layout.WindowSizeClass

internal fun Modifier.inPane(bounds: DpRect): Modifier =
    absolutePadding(left = bounds.left, top = bounds.top).size(bounds.width, bounds.height)

/** Bounds are relative to the window inside system bars and cutouts. */
internal data class ModalPane(val bounds: DpRect, val imeFallbackBounds: DpRect? = null)

internal data class WindowPanes(
    val primary: DpRect,
    val lower: DpRect? = null,
    val side: DpRect? = null,
    val dividerX: Dp? = null,
    val isFoldConstrained: Boolean = false,
) {
    fun modalPane(bounds: DpRect): ModalPane? = if (isFoldConstrained) ModalPane(bounds) else null
}

internal fun Density.calculateWindowPanes(
    viewportSize: DpSize,
    viewportOriginPx: Offset,
    hinges: List<HingeInfo>,
    windowSizeClass: WindowSizeClass,
): WindowPanes {
    val viewportBounds = DpRect(0.dp, 0.dp, viewportSize.width, viewportSize.height)
    // When the system reports several folds, lay out around the first eligible one.
    for (hinge in hinges) {
        if (!hinge.isSeparating && !hinge.isOccluding) continue

        val isVertical = hinge.isVertical
        val hingeBoundsPx = hinge.bounds.translate(-viewportOriginPx)
        val viewportLength = if (isVertical) viewportSize.width else viewportSize.height
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
            return WindowPanes(primary = largerPane, isFoldConstrained = true)
        }

        return if (isVertical) {
            WindowPanes(
                primary = firstPaneBounds,
                side = secondPaneBounds,
                dividerX = secondHingeEdge.takeUnless { hinge.isOccluding },
                isFoldConstrained = true,
            )
        } else {
            WindowPanes(primary = firstPaneBounds, lower = secondPaneBounds, isFoldConstrained = true)
        }
    }

    val wideWindow = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) ||
            windowSizeClass.isAtLeastBreakpoint(
                WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
                WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND,
            )
    if (!wideWindow || viewportSize.width < 600.dp || viewportSize.height < 300.dp) return WindowPanes(viewportBounds)

    // Allocate the odd pixel to the side pane, keeping one exactly measured gap.
    val dividerWidthPx = DividerDefaults.Thickness.roundToPx()
    val primaryWidthPx = (viewportSize.width.roundToPx() - dividerWidthPx) / 2
    val dividerX = primaryWidthPx.toDp()
    return WindowPanes(
        primary = viewportBounds.copy(right = dividerX),
        side = viewportBounds.copy(left = (primaryWidthPx + dividerWidthPx).toDp()),
        dividerX = dividerX,
    )
}
