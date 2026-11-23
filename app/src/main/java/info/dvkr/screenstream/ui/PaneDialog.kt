package info.dvkr.screenstream.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Physical pane for a modal opened from a fold-aware layout.
 *
 * [bounds] and [imeFallback] are relative to the top-left of the window area inside system bars
 * and display cutouts. [imeFallback] is an alternate pane for a keyboard-obscured modal.
 */
internal data class ModalPane(
    val bounds: DpRect,
    val imeFallback: DpRect? = null,
    val isHalfOpen: Boolean = false,
)

/** Places a modal surface within the supplied [placement]. */
@Composable
internal fun PaneDialog(
    placement: ModalPane,
    onDismissRequest: () -> Unit,
    dismissLabel: String,
    compact: Boolean = false,
    windowTitle: String = "",
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, windowTitle = windowTitle),
    ) {
        val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val ime = WindowInsets.ime
        // Through Android 11 the dialog window already resizes for the keyboard.
        val viewportInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) safeInsets.union(ime) else safeInsets
        val pane = placement.bounds
        val fallbackPane = placement.imeFallback
        val padding = 12.dp
        val minimumHeight = 112.dp + padding * 2 // Room for a title and one editable row.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(viewportInsets)
                .consumeWindowInsets(ime),
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismissRequest)
                    .semantics { contentDescription = dismissLabel },
            )
            val paneHeight = minOf(maxHeight, pane.bottom) - pane.top
            val bounds = if (paneHeight >= minimumHeight) {
                pane
            } else {
                fallbackPane?.takeIf { minOf(maxHeight, it.bottom) - it.top > paneHeight } ?: pane
            }
            val width = (minOf(maxWidth, bounds.right) - bounds.left).coerceAtLeast(0.dp)
            val height = (minOf(maxHeight, bounds.bottom) - bounds.top).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier
                    .absoluteOffset(bounds.left, bounds.top)
                    .requiredSize(width, height)
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .then(if (compact) Modifier else Modifier.fillMaxWidth()),
                    shape = if (compact) RectangleShape else MaterialTheme.shapes.extraLarge,
                    color = if (compact) Color.Transparent else MaterialTheme.colorScheme.surface,
                    tonalElevation = if (compact) 0.dp else 3.dp,
                    content = content,
                )
            }
        }
    }
}
