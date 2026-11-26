package info.dvkr.screenstream.ui.modal

import android.os.Build
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import info.dvkr.screenstream.R
import android.app.Dialog as AndroidDialog

internal val AppDialogMaxWidth = 480.dp

/** Placement in the window area inside system bars and cutouts. */
internal data class ModalPane(val bounds: DpRect, val imeFallbackBounds: DpRect? = null)

/** A null [title] lets the caller supply the preview surface, such as for a QR code. */
@Composable
internal fun AppDialog(
    onDismiss: () -> Unit,
    title: String?,
    pane: ModalPane? = null,
    content: @Composable () -> Unit,
) {
    val usePlatformFrame = title == null && pane == null
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = usePlatformFrame,
            decorFitsSystemWindows = usePlatformFrame,
            windowTitle = title.orEmpty(),
        ),
    ) {
        HandleDialogEscape(onDismiss)
        if (usePlatformFrame) {
            content()
            return@Dialog
        }

        val surface: @Composable (Modifier) -> Unit = { placement ->
            val bounded = placement.widthIn(max = AppDialogMaxWidth)
            if (title == null) {
                Surface(bounded, color = Color.Transparent, content = content)
            } else {
                Surface(
                    modifier = bounded.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = if (pane == null) 0.dp else 3.dp,
                    content = content,
                )
            }
        }
        if (pane == null) {
            surface(Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp))
        } else {
            PaneDialogContent(pane, onDismiss, surface)
        }
    }
}

@Composable
private fun PaneDialogContent(pane: ModalPane, onDismiss: () -> Unit, content: @Composable (Modifier) -> Unit) {
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val imeInsets = WindowInsets.ime
    // The platform resizes Compose dialog windows for the IME through Android 11.
    val dialogInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) safeInsets.union(imeInsets) else safeInsets
    val dismissLabel = stringResource(R.string.app_action_close)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(dialogInsets)
            .consumeWindowInsets(imeInsets),
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = null, indication = null, onClick = onDismiss)
                .semantics { contentDescription = dismissLabel },
        )
        val availableHeight = (minOf(pane.bounds.bottom, maxHeight) - pane.bounds.top).coerceAtLeast(0.dp)
        val bounds = pane.imeFallbackBounds?.takeIf {
            availableHeight < 136.dp && minOf(it.bottom, maxHeight) - it.top > availableHeight
        } ?: pane.bounds
        content(
            Modifier
                .absolutePadding(left = bounds.left, top = bounds.top)
                .size(bounds.width, bounds.height)
                .padding(12.dp)
                .wrapContentSize(),
        )
    }
}

@Composable
internal fun HandleDialogEscape(onEscape: () -> Unit) {
    // The dialog can receive a hardware key before any of its children gets focus.
    val dialog = (LocalView.current.parent as? DialogWindowProvider)?.window?.callback as? AndroidDialog
    val currentOnEscape by rememberUpdatedState(onEscape)
    DisposableEffect(dialog) {
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) currentOnEscape()
                true
            }
        }
        onDispose { dialog?.setOnKeyListener(null) }
    }
}
