package info.dvkr.screenstream.ui

import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentDialog
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.focus.focusProperties
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
import info.dvkr.screenstream.R

/** Presents caller-owned content in a dialog. A null [title] leaves the surface to the caller. */
@Composable
internal fun AppDialog(
    title: String?,
    onDismissRequest: () -> Unit,
    dialogBounds: DpRect? = null,
    content: @Composable () -> Unit,
) {
    val usePlatformFrame = title == null && dialogBounds == null
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = dialogBounds == null,
            decorFitsSystemWindows = usePlatformFrame,
            windowTitle = title.orEmpty(),
        ),
    ) {
        HandleModalEscape(onDismissRequest)
        if (usePlatformFrame) {
            content()
            return@Dialog
        }

        val dialogSurface: @Composable (Modifier) -> Unit = { placementModifier ->
            if (title == null) {
                Surface(modifier = placementModifier, color = Color.Transparent, content = content)
            } else {
                Surface(
                    modifier = placementModifier,
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = if (dialogBounds == null) 0.dp else 3.dp,
                    content = content,
                )
            }
        }

        if (dialogBounds == null) {
            dialogSurface(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
            )
        } else {
            val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            val imeInsets = WindowInsets.ime
            // The platform resizes Compose dialog windows for the IME through Android 11.
            val paneInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) systemInsets.union(imeInsets) else systemInsets
            val dismissLabel = stringResource(R.string.app_action_close)
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(paneInsets)
                    .consumeWindowInsets(imeInsets),
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .focusProperties { if (title != null) canFocus = false }
                        .clickable(interactionSource = null, indication = null, onClick = onDismissRequest)
                        .semantics { contentDescription = dismissLabel },
                )

                dialogSurface(
                    Modifier
                        .absolutePadding(left = dialogBounds.left, top = dialogBounds.top)
                        .size(dialogBounds.width, dialogBounds.height)
                        .padding(12.dp)
                        .wrapContentSize()
                        .widthIn(max = 480.dp),
                )
            }
        }
    }
}

@Composable
internal fun HandleModalEscape(onEscape: () -> Unit) {
    // The dialog can receive a hardware key before any of its children gets focus.
    val dialog = LocalView.current.findViewTreeOnBackPressedDispatcherOwner() as? ComponentDialog
    val currentOnEscape by rememberUpdatedState(onEscape)
    DisposableEffect(dialog) {
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_ESCAPE || !event.hasNoModifiers()) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) currentOnEscape()
            true
        }
        onDispose { dialog?.setOnKeyListener(null) }
    }
}
