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
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R

@Composable
internal fun AdaptiveAppModal(
    title: String,
    onDismissRequest: () -> Unit,
    onBack: (() -> Unit)? = null,
    dialogBounds: DpRect? = null,
    content: @Composable () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerDpSize
    val isAtLeastMediumWidth = windowSize.width >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    val isAtLeastMediumHeight = windowSize.height >= WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp
    val onBackOrDismiss = onBack ?: onDismissRequest
    val titledContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBackOrDismiss) {
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
                if (onBack == null) {
                    IconButton(onClick = onBackOrDismiss) {
                        Icon(painterResource(R.drawable.app_icon_close), stringResource(R.string.app_action_close))
                    }
                }
            }
            content()
        }
    }

    if (dialogBounds != null || (isAtLeastMediumWidth && isAtLeastMediumHeight)) {
        AppDialog(title = title, onDismissRequest = onBackOrDismiss, dialogBounds = dialogBounds, content = titledContent)
    } else {
        AppModalBottomSheet(
            onDismissRequest = onDismissRequest,
            onBack = onBack,
            maxWidth = if (isAtLeastMediumWidth) 480.dp else Dp.Unspecified,
            content = titledContent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    onBack: (() -> Unit)?,
    maxWidth: Dp,
    content: @Composable () -> Unit,
) {
    val hasBackAction = onBack != null
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // Material can retain the callback across page changes and invoke it when a hide is cancelled.
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    var isSheetMounted by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = { if (isSheetMounted && !sheetState.isVisible) currentOnDismissRequest() },
        dragHandle = null,
        sheetMaxWidth = maxWidth,
        sheetState = sheetState,
        sheetGesturesEnabled = !hasBackAction,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !hasBackAction),
    ) {
        HandleModalEscape(onBack ?: onDismissRequest)
        if (onBack != null) BackHandler(onBack = onBack)
        content()
    }

    DisposableEffect(Unit) {
        isSheetMounted = true
        onDispose { isSheetMounted = false }
    }
}
