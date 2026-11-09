package info.dvkr.screenstream.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntRect
import androidx.compose.ui.unit.toRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.about.AboutScreen
import info.dvkr.screenstream.ui.settings.SettingsDetail
import info.dvkr.screenstream.ui.settings.SettingsRoute
import info.dvkr.screenstream.ui.stream.StreamScreen
import io.screenstream.core.StreamingModuleManager
import kotlinx.serialization.Serializable

@Composable
internal fun AppNavigation(
    streamingModulesManager: StreamingModuleManager,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(AppTab.STREAM)
    val selectedTab = backStack.last { it is AppTab } as AppTab

    val goBack: () -> Unit = { if (backStack.size > 1) backStack.removeLastOrNull() }
    val isExiting = streamingModulesManager.state.collectAsStateWithLifecycle().value == StreamingModuleManager.State.Exiting
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onExitClick = dropUnlessResumed { streamingModulesManager.exit() }
    BackHandler(enabled = isExiting) {}

    val windowAdaptiveInfo = currentWindowAdaptiveInfoV2()
    val layoutType = with(windowAdaptiveInfo) {
        when {
            windowPosture.isTabletop -> NavigationSuiteType.NavigationBar
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> NavigationSuiteType.NavigationRail
            else -> NavigationSuiteType.NavigationBar
        }
    }

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            AppTab.entries.forEach { tab ->
                val selected = !isExiting && selectedTab == tab
                item(
                    selected = selected,
                    enabled = !isExiting,
                    onClick = {
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && backStack.last { it is AppTab } != tab) {
                            while (backStack.size > 1) backStack.removeLastOrNull()
                            if (tab != AppTab.STREAM) backStack.add(tab)
                        }
                    },
                    icon = { Icon(painter = painterResource(if (selected) tab.selectedIconRes else tab.iconRes), contentDescription = null) },
                    label = { Text(text = stringResource(tab.labelRes)) },
                )
            }
            item(
                selected = isExiting,
                enabled = !isExiting,
                onClick = onExitClick,
                icon = { Icon(painter = painterResource(R.drawable.close_24px), contentDescription = null) },
                label = { Text(text = stringResource(R.string.app_tab_exit)) },
            )
        },
        layoutType = layoutType,
    ) {
        AppNavHost(
            backStack = backStack,
            windowAdaptiveInfo = windowAdaptiveInfo,
            isExiting = isExiting,
            onOpenSettings = { destination ->
                if (backStack.last() != destination) {
                    while (backStack.size > 1) backStack.removeLastOrNull()
                    backStack.add(AppTab.SETTINGS)
                    backStack.add(destination)
                }
            },
            onBack = goBack,
        )
    }

    AppSystemBars(layoutType)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun AppNavHost(
    backStack: List<NavKey>,
    windowAdaptiveInfo: WindowAdaptiveInfo,
    isExiting: Boolean,
    onOpenSettings: (SettingsDestination) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val contentBoundsInWindow = remember { mutableStateOf(windowSize.toIntRect().toRect()) }
    val density = LocalDensity.current

    val calculatedDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo, HingePolicy.AvoidOccluding)
    val directive = calculatedDirective.copy(
        maxHorizontalPartitions = minOf(calculatedDirective.maxHorizontalPartitions, 2),
        defaultPanePreferredWidth = 360.dp,
        verticalPartitionSpacerSize = 0.dp,
        horizontalPartitionSpacerSize = 0.dp,
    )
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        shouldHandleSinglePaneLayout = true,
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
        directive = directive,
    )
    val entryProvider = entryProvider<NavKey> {
        entry(AppTab.STREAM) {
            StreamScreen(
                boundsInWindow = contentBoundsInWindow.value,
                modifier = Modifier.fillMaxSize(),
            )
        }
        entry(
            AppTab.SETTINGS,
            metadata = ListDetailSceneStrategy.listPane(sceneKey = AppTab.SETTINGS) +
                    ListDetailSceneStrategy.preferredPaneSize(
                        width = calculateListPanePreferredWidth(windowAdaptiveInfo, contentBoundsInWindow.value, density),
                    ),
        ) {
            SettingsRoute(onOpenDetail = onOpenSettings, modifier = Modifier.fillMaxSize())
        }
        entry(AppTab.ABOUT) {
            AboutScreen(modifier = Modifier.fillMaxSize())
        }
        entry<SettingsDestination>(metadata = ListDetailSceneStrategy.detailPane(sceneKey = AppTab.SETTINGS)) {
            SettingsDetail(destination = it, onBack = onBack)
        }
    }
    val entries = rememberDecoratedNavEntries(
        entries = backStack.map(entryProvider),
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
    )

    if (!isExiting) {
        NavDisplay(
            entries = entries,
            onBack = onBack,
            modifier = modifier.onPlaced { contentBoundsInWindow.value = it.boundsInWindow() },
            sceneStrategies = listOf(listDetailStrategy),
            transitionSpec = {
                fadeIn(animationSpec = tween(300, delayMillis = 90, easing = EaseIn))
                    .togetherWith(fadeOut(animationSpec = tween(150, easing = EaseOut)))
            },
            popTransitionSpec = {
                fadeIn(animationSpec = tween(300, delayMillis = 90, easing = EaseIn))
                    .togetherWith(fadeOut(animationSpec = tween(150, easing = EaseOut)))
            },
        )
    }
}

private fun calculateListPanePreferredWidth(
    windowAdaptiveInfo: WindowAdaptiveInfo,
    boundsInWindow: Rect,
    density: Density,
): Dp {
    if (boundsInWindow.isEmpty) return 360.dp
    val hinge = windowAdaptiveInfo.windowPosture.hingeList.firstOrNull()
    if (hinge != null && !hinge.isFlat) {
        if (!hinge.isVertical) return 360.dp
        return with(density) { (hinge.bounds.left - boundsInWindow.left).toDp() } // BookPosture
    }
    if (!windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) return 360.dp
    return with(density) { (boundsInWindow.width / 2).toDp() }
}

@Serializable
internal enum class AppTab(
    @field:DrawableRes internal val iconRes: Int,
    @field:DrawableRes internal val selectedIconRes: Int,
    @field:StringRes internal val labelRes: Int,
) : NavKey {
    STREAM(R.drawable.stream_24px, R.drawable.stream_24px, R.string.app_tab_stream),
    SETTINGS(R.drawable.settings_24px, R.drawable.settings_filled_24px, R.string.app_tab_settings),
    ABOUT(R.drawable.info_24px, R.drawable.info_filled_24px, R.string.app_tab_about),
}

@Serializable
internal enum class SettingsDestination : NavKey {
    APP_LANGUAGE,
    DARK_THEME,
}
