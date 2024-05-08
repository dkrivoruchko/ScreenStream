package info.dvkr.screenstream.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.unit.toRect
import androidx.window.core.layout.WindowWidthSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ui.conditional
import info.dvkr.screenstream.logger.AppLogger
import info.dvkr.screenstream.logger.CollectingLogsUi
import info.dvkr.screenstream.notification.NotificationPermission
import info.dvkr.screenstream.tile.TileActionService
import info.dvkr.screenstream.ui.tabs.ScreenStreamTab
import info.dvkr.screenstream.ui.tabs.about.AboutTab
import info.dvkr.screenstream.ui.tabs.settings.SettingsTab
import info.dvkr.screenstream.ui.tabs.stream.StreamTab
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun ScreenStreamContent(
    updateFlow: StateFlow<((Boolean) -> Unit)?>,
    modifier: Modifier = Modifier,
    isLoggingOn: Boolean = AppLogger.isLoggingOn
) {
    if (isLoggingOn) {
        Column(modifier = modifier.fillMaxSize()) {
            CollectingLogsUi(modifier = Modifier.fillMaxWidth())
            MainContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1F)
            )
        }
    } else {
        MainContent(modifier = modifier.fillMaxSize())
    }

    val updateFlowState = updateFlow.collectAsState()
    if (updateFlowState.value != null) {
        AppUpdateRequestUI(
            onConfirmButtonClick = { updateFlowState.value?.invoke(true) },
            onDismissButtonClick = { updateFlowState.value?.invoke(false) }
        )
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        NotificationPermission()
        TileActionService.AddTileRequest()
    }
}

internal val LocalContentBoundsInWindow = staticCompositionLocalOf { Rect.Zero }

@Composable
private fun MainContent(
    modifier: Modifier = Modifier,
    tabs: Array<ScreenStreamTab> = arrayOf(StreamTab, SettingsTab, AboutTab)
) {
    val selectedTabIndex = rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedTabIndex.intValue != 0) { selectedTabIndex.intValue = 0 }

    val layoutType = with(currentWindowAdaptiveInfo()) {
        when {
            windowPosture.isTabletop -> NavigationSuiteType.NavigationBar
            windowSizeClass.windowWidthSizeClass != WindowWidthSizeClass.COMPACT -> NavigationSuiteType.NavigationRail
            else -> NavigationSuiteType.NavigationBar
        }
    }

    val windowSize = currentWindowSize()
    val contentBoundsInWindow = remember { mutableStateOf(windowSize.toIntRect().toRect()) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEachIndexed { index, screen ->
                item(
                    selected = selectedTabIndex.intValue == index,
                    onClick = { selectedTabIndex.intValue = index },
                    icon = {
                        Icon(
                            imageVector = if (selectedTabIndex.intValue == index) screen.iconSelected else screen.icon,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .conditional(layoutType == NavigationSuiteType.NavigationRail) { padding(vertical = 8.dp) }
                        .padding(horizontal = 4.dp),
                    label = { Text(text = stringResource(screen.labelResId)) }
                )
            }
        },
        modifier = modifier,
        layoutType = layoutType
    ) {
        AnimatedContent(
            targetState = selectedTabIndex.intValue,
            modifier = Modifier.onPlaced { contentBoundsInWindow.value = it.boundsInWindow() },
            transitionSpec = {
                fadeIn(animationSpec = tween(300, delayMillis = 90, easing = EaseIn))
                    .togetherWith(fadeOut(animationSpec = tween(150, easing = EaseOut)))
            },
            label = "TabContent"
        ) { tabIndex ->
            CompositionLocalProvider(LocalContentBoundsInWindow provides contentBoundsInWindow.value) {
                tabs[tabIndex].Content(modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (layoutType != NavigationSuiteType.NavigationBar) {
        val view = LocalView.current
        if (view.isInEditMode.not()) {
            val color = MaterialTheme.colorScheme.background
            SideEffect {
                (view.context as ComponentActivity).enableEdgeToEdge(statusBarColor = color, navigationBarColor = color)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppUpdateRequestUI(
    onConfirmButtonClick: () -> Unit,
    onDismissButtonClick: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissButtonClick,
        shape = MaterialTheme.shapes.medium,
        dragHandle = null
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_notification_small_24dp), contentDescription = null)
            Text(
                text = stringResource(id = R.string.app_activity_update_dialog_title),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp + 24.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = stringResource(id = R.string.app_activity_update_dialog_message),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .padding(end = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismissButtonClick,
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
            TextButton(onClick = onConfirmButtonClick) {
                Text(text = stringResource(id = R.string.app_activity_update_dialog_restart))
            }
        }
    }
}