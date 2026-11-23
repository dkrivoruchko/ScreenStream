package info.dvkr.screenstream.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.window.core.layout.WindowSizeClass
import io.screenstream.foundation.logging.Logger
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun AdaptiveWindow(
    modifier: Modifier = Modifier,
    content: @Composable (WindowPanes) -> Unit,
) {
    val windowInfo = currentWindowAdaptiveInfoV2()
    val hinges = windowInfo.windowPosture.hingeList
    val windowSizeClass = windowInfo.windowSizeClass
    if (hinges.size > 1) {
        val context = LocalContext.current
        val log: Logger = koinInject(parametersHolder = remember(context) { parametersOf(context) })
        LaunchedEffect(Unit) {
            val folds = hinges.joinToString { hinge ->
                val (left, top, right, bottom) = hinge.bounds
                val orientation = if (hinge.isVertical) "V" else "H"
                buildString {
                    append("$orientation[$left,$top,$right,$bottom]")
                    if (!hinge.isFlat) append(" halfOpen")
                    if (hinge.isSeparating) append(" separating")
                    if (hinge.isOccluding) append(" occluding")
                }
            }
            log.i(
                "AdaptiveWindow",
                "System folds (window px): $folds. Layout considers the first eligible fold.",
                IllegalStateException("Multiple folds reported by the window system"),
            )
        }
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { origin = it.positionInWindow() },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val density = LocalDensity.current
        val windowSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val windowOrigin = origin
        val panes = remember(windowSize, windowOrigin, density, hinges, windowSizeClass) {
            density.calculatePanes(windowSize, windowOrigin, hinges, windowSizeClass)
        }
        content(panes)
    }
}

internal data class WindowPanes(
    val headerBounds: DpRect,
    val headerModalPane: ModalPane? = null,
    val body: ContentPane = ContentPane(Modifier.size(headerBounds.width, headerBounds.height), headerModalPane, sharesHeaderPane = true),
    val side: ContentPane? = null,
    val dividerBounds: DpRect? = null,
    val isIsolated: Boolean = false,
) {
    val isTabletop: Boolean get() = !body.sharesHeaderPane
}

internal data class ContentPane(
    val modifier: Modifier,
    val modalPane: ModalPane? = null,
    val sharesHeaderPane: Boolean = false,
)

internal fun Modifier.inPaneBounds(bounds: DpRect): Modifier =
    absoluteOffset(bounds.left, bounds.top).requiredSize(bounds.width, bounds.height)

internal fun Modifier.inWindowViewport(panes: WindowPanes): Modifier {
    val viewport = fillMaxSize()
    if (!panes.isIsolated) return viewport

    // Keep the layout window-sized while measuring and positioning its content inside the isolated pane.
    val bounds = panes.headerBounds
    return viewport
        .absolutePadding(left = bounds.left, top = bounds.top)
        .wrapContentSize(AbsoluteAlignment.TopLeft)
        .size(bounds.width, bounds.height)
}

private fun Density.calculatePanes(
    windowSize: IntSize,
    origin: Offset,
    hinges: List<HingeInfo>,
    windowSizeClass: WindowSizeClass,
): WindowPanes {
    val windowRight = origin.x + windowSize.width
    val windowBottom = origin.y + windowSize.height
    val fullPane = DpRect(0.dp, 0.dp, windowSize.width.toDp(), windowSize.height.toDp())

    // When the system reports several folds, lay out around the first eligible one.
    for (hinge in hinges) {
        if (!hinge.isSeparating && !hinge.isOccluding) continue

        val vertical = hinge.isVertical
        val bounds = hinge.bounds
        val axisStart = if (vertical) origin.x else origin.y
        val axisEnd = if (vertical) windowRight else windowBottom
        val firstEdge = if (vertical) bounds.left else bounds.top
        val secondEdge = if (vertical) bounds.right else bounds.bottom
        if (!(firstEdge > axisStart && secondEdge < axisEnd)) continue

        val firstSize = firstEdge - axisStart
        val secondSize = axisEnd - secondEdge
        val minimumSize = if (vertical) 280.dp.toPx() else 120.dp.toPx()
        val split = minOf(firstSize, secondSize) >= minimumSize
        val firstBoundary = firstSize.toDp()
        val secondBoundary = (secondEdge - axisStart).toDp()
        val first = if (vertical) fullPane.copy(right = firstBoundary) else fullPane.copy(bottom = firstBoundary)
        val second = if (vertical) fullPane.copy(left = secondBoundary) else fullPane.copy(top = secondBoundary)
        val header = if (split || firstSize >= secondSize) first else second
        val headerModal = ModalPane(header, isHalfOpen = !hinge.isFlat)
        if (!split) return WindowPanes(header, headerModal, isIsolated = true)

        val otherPane = ContentPane(
            modifier = Modifier.inPaneBounds(second),
            modalPane = ModalPane(second, imeFallback = header.takeUnless { vertical }, isHalfOpen = !hinge.isFlat),
        )
        return if (vertical) WindowPanes(header, headerModal, side = otherPane)
        else WindowPanes(header, headerModal, body = otherPane)
    }

    val splitFlatWindow = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) &&
            (windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) ||
                    windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND)) &&
            fullPane.width >= 600.dp && fullPane.height >= 300.dp
    if (!splitFlatWindow) return WindowPanes(fullPane)

    val dividerLeft = (fullPane.width - 1.dp) / 2
    val dividerRight = dividerLeft + 1.dp
    return WindowPanes(
        headerBounds = fullPane.copy(right = dividerLeft),
        side = ContentPane(Modifier.inPaneBounds(fullPane.copy(left = dividerRight))),
        dividerBounds = fullPane.copy(left = dividerLeft, right = dividerRight),
    )
}
