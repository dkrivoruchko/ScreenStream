package info.dvkr.screenstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveAppModal(
    title: String,
    onDismiss: () -> Unit,
    pane: ModalPane? = null,
    onBack: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerDpSize
    val hasCompactWidth = windowSize.width < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    val showBack = onBack != null
    val closeOrGoBack = onBack ?: onDismiss

    if (pane != null || (!hasCompactWidth && windowSize.height >= WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp)) {
        AppDialog(onDismiss = closeOrGoBack, title = title, pane = pane) {
            TitledModalContent(title, closeOrGoBack, showBack, actionModifier, content)
        }
        return
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // Material can retain the callback across page changes and invoke it when a hide is cancelled.
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var sheetMounted by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (sheetMounted && !sheetState.isVisible) currentOnDismiss() },
        dragHandle = null,
        sheetMaxWidth = if (hasCompactWidth) Dp.Unspecified else AppDialogMaxWidth,
        sheetState = sheetState,
        sheetGesturesEnabled = !showBack,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !showBack),
    ) {
        HandleDialogEscape(closeOrGoBack)
        if (onBack != null) BackHandler(onBack = onBack)
        TitledModalContent(title, closeOrGoBack, showBack, actionModifier, content)
    }
    DisposableEffect(Unit) {
        sheetMounted = true
        onDispose { sheetMounted = false }
    }
}

@Composable
private fun TitledModalContent(
    title: String,
    onAction: () -> Unit,
    showBack: Boolean,
    actionModifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onAction, modifier = actionModifier) {
                    Icon(painterResource(R.drawable.app_icon_arrow_back), stringResource(R.string.app_action_back))
                }
            }
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            if (!showBack) {
                IconButton(onClick = onAction, modifier = actionModifier) {
                    Icon(painterResource(R.drawable.app_icon_close), stringResource(R.string.app_action_close))
                }
            }
        }
        content()
    }
}
