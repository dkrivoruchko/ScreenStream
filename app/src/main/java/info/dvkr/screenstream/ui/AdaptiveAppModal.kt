package info.dvkr.screenstream.ui

import android.os.Build
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import kotlinx.coroutines.launch
import android.app.Dialog as AndroidDialog

private val appDialogMaxWidth = 480.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveAppModal(
    title: String,
    onDismiss: () -> Unit,
    pane: ModalPane? = null,
    onBack: (() -> Unit)? = null,
    actionFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalWindowInfo.current.containerDpSize
    val compactWidth = window.width < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    val compactHeight = window.height < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp
    val dockInPane = pane != null && (pane.isHalfOpen || pane.bounds.height < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp)
    val modalContent: @Composable (Modifier) -> Unit = { modifier ->
        if (onBack != null) BackHandler(onBack = onBack)
        AppModalContent(title, onDismiss, onBack, modifier, actionFocusRequester, content)
    }

    if (dockInPane || (pane == null && (compactWidth || compactHeight))) {
        AppModalBottomSheet(
            onDismiss = onDismiss,
            onBack = onBack,
            pane = pane,
            sheetMaxWidth = when {
                pane != null -> BottomSheetDefaults.SheetMaxWidth
                compactWidth -> Dp.Unspecified
                else -> appDialogMaxWidth
            },
        ) {
            modalContent(Modifier.weight(1f, fill = false))
        }
    } else {
        AppDialog(
            onDismiss = onBack ?: onDismiss,
            title = title,
            pane = pane,
        ) { modalContent(Modifier) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppModalBottomSheet(
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    pane: ModalPane?,
    sheetMaxWidth: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val attached = remember { mutableStateOf(false) }
    val sheetModifier = if (pane == null) Modifier else Modifier.inSheetPane(pane)
    ModalBottomSheet(
        onDismissRequest = {
            if (!attached.value) return@ModalBottomSheet
            (onBack ?: onDismiss)()
            scope.launch {
                withFrameNanos { }
                if (attached.value && !sheetState.isVisible) sheetState.show()
            }
        },
        modifier = sheetModifier,
        dragHandle = null,
        sheetMaxWidth = sheetMaxWidth,
        sheetState = sheetState,
        contentWindowInsets = {
            if (pane == null) BottomSheetDefaults.modalWindowInsets
            else WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom)
        },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = onBack == null),
    ) {
        HandleDialogEscape(onBack ?: onDismiss)
        content()
    }
    // Material's hide completion can run when disposal cancels its coroutine.
    DisposableEffect(Unit) {
        attached.value = true
        onDispose { attached.value = false }
    }
}

private fun Modifier.inSheetPane(placement: ModalPane): Modifier = composed {
    // Resolve insets in the sheet's dialog window when the modifier is applied.
    val systemInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // The Material dialog resizes for the keyboard before Android 11.
    val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowInsets.ime else WindowInsets(0, 0, 0, 0)
    windowInsetsPadding(systemInsets.union(ime).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
        .layout { measurable, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val systemBottom = systemInsets.getBottom(this)
            val imeBottom = ime.getBottom(this)
            val safeBottom = (height - maxOf(systemBottom, imeBottom)).coerceAtLeast(0)

            fun DpRect.visibleHeight(): Int = minOf(safeBottom, bottom.roundToPx()) - top.roundToPx()
            val pane = placement.bounds
            val paneHeight = pane.visibleHeight()
            val bounds = placement.imeFallback
                ?.takeIf { paneHeight < 112.dp.roundToPx() && it.visibleHeight() > paneHeight }
                ?: pane

            val left = bounds.left.roundToPx().coerceIn(0, width)
            val top = bounds.top.roundToPx().coerceIn(0, safeBottom)
            val right = bounds.right.roundToPx().coerceIn(left, width)
            val paneBottom = bounds.bottom.roundToPx()
            val reachesWindowBottom = imeBottom <= systemBottom && paneBottom >= safeBottom
            val bottom = if (reachesWindowBottom) height else paneBottom.coerceIn(top, safeBottom)
            val viewport = measurable.measure(Constraints.fixed(right - left, bottom - top))
            layout(width, height) { viewport.place(left, top) }
        }
        .clipToBounds()
        // Count space outside the fixed viewport as consumed before laying out the sheet.
        .recalculateWindowInsets()
        // Keep the exact clip viewport while passing its maximum height to the sheet's anchors.
        .wrapContentSize(Alignment.TopCenter)
}

@Composable
internal fun AppDialog(
    onDismiss: () -> Unit,
    title: String = "",
    pane: ModalPane? = null,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dialogContent: @Composable () -> Unit = {
        HandleDialogEscape(onDismiss)
        content()
    }
    if (pane != null) {
        PaneDialog(
            placement = pane,
            onDismissRequest = onDismiss,
            dismissLabel = stringResource(R.string.app_action_close),
            compact = compact,
            windowTitle = title,
            content = dialogContent,
        )
    } else if (compact) {
        Dialog(onDismissRequest = onDismiss, content = dialogContent)
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, windowTitle = title),
        ) {
            Surface(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .widthIn(max = appDialogMaxWidth)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                content = dialogContent,
            )
        }
    }
}

@Composable
private fun AppModalContent(
    title: String,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionFocusRequester: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val headerAction: @Composable () -> Unit = {
        IconButton(
            onClick = onBack ?: onDismiss,
            modifier = if (actionFocusRequester == null) Modifier else Modifier.focusRequester(actionFocusRequester),
        ) {
            Icon(
                painter = painterResource(if (onBack != null) R.drawable.app_icon_arrow_back else R.drawable.app_icon_close),
                contentDescription = stringResource(if (onBack != null) R.string.app_action_back else R.string.app_action_close),
            )
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) headerAction()
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (onBack == null) headerAction()
        }
        content()
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
