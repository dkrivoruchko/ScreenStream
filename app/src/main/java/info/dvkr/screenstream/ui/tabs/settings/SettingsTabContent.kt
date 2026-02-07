package info.dvkr.screenstream.ui.tabs.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.AdaptiveBanner
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.ui.tabs.settings.app.AppModuleSettings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsTabContent(
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    appModuleSettings: AppModuleSettings = koinInject()
) {
    val scope = rememberCoroutineScope()
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val listPanePreferredWidth = calculateListPanePreferredWidth(windowAdaptiveInfo, boundsInWindow)
    val scaffoldDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo, HingePolicy.AvoidOccluding)
        .copy(verticalPartitionSpacerSize = 0.dp, horizontalPartitionSpacerSize = 0.dp)
    val navigator = rememberListDetailPaneScaffoldNavigator<ModuleSettings.Id>(scaffoldDirective)

    BackHandler(enabled = navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

    val lazyListState = rememberLazyListState()

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(listPanePreferredWidth)) {
                SettingsListPane(
                    lazyListState = lazyListState,
                    settings = appModuleSettings,
                    onSettingSelected = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it) } },
                    modifier = Modifier.preferredWidth(listPanePreferredWidth)
                )
            }
        },
        detailPane = {
            AnimatedPane(modifier = Modifier.fillMaxSize()) {
                getModuleSettingsItem(appModuleSettings, navigator.currentDestination?.contentKey)
                    ?.DetailUI { title -> DetailUITitle(title, navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } } }
            }
        },
        modifier = modifier
    )
}

private fun getModuleSettingsItem(settings: ModuleSettings, settingId: ModuleSettings.Id?): ModuleSettings.Item? =
    if (settingId == null) null
    else if (settings.id != settingId.moduleId) null
    else settings.groups.firstOrNull { it.id == settingId.groupId }?.items?.firstOrNull { it.id == settingId.itemId }

@Composable
private fun DetailUITitle(title: String, canNavigateBack: Boolean, navigateBack: () -> Unit) {
    if (canNavigateBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = navigateBack) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = stringResource(id = R.string.app_pref_back)
                )
            }
            Text(
                text = title,
                modifier = Modifier.padding(start = 16.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium
            )
        }
    } else {
        Text(
            text = title,
            modifier = Modifier
                .background(color = MaterialTheme.colorScheme.secondaryContainer)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsListPane(
    lazyListState: LazyListState,
    settings: ModuleSettings,
    onSettingSelected: (ModuleSettings.Id) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier) {
        val horizontalPadding = if (this.maxWidth >= 480.dp) 16.dp else 0.dp

        val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
        val titleModifier = remember(maxWidth, secondaryContainer) {
            Modifier
                .background(color = secondaryContainer)
                .padding(horizontal = horizontalPadding + 16.dp, vertical = 8.dp)
                .fillMaxWidth()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AdaptiveBanner(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                state = lazyListState
            ) {
                // stickyHeader has bug in Compose
                item(key = settings.id, contentType = "HEADER") {
                    settings.TitleUI(modifier = titleModifier)
                }

                settings.groups.forEach { settingsGroup ->
                    item(key = "${settings.id}#${settingsGroup.id}", contentType = "HEADER") {
                        settingsGroup.TitleUI(modifier = titleModifier)
                    }

                    itemsIndexed(
                        items = settingsGroup.items,
                        key = { _, settingsItem -> "${settings.id}#${settingsGroup.id}#${settingsItem.id}" },
                        contentType = { _, _ -> "ITEM" },
                        itemContent = { index, settingsItem ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            ) {
                                settingsItem.ItemUI(
                                    horizontalPadding = horizontalPadding,
                                    coroutineScope = scope,
                                    onDetailShow = {
                                        onSettingSelected(ModuleSettings.Id(settings.id, settingsGroup.id, settingsItem.id))
                                    }
                                )
                                if (index != settingsGroup.items.size - 1) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun calculateListPanePreferredWidth(
    windowAdaptiveInfo: WindowAdaptiveInfo,
    boundsInWindow: Rect
): Dp {
    if (boundsInWindow.isEmpty) return 360.dp
    val hinge = windowAdaptiveInfo.windowPosture.hingeList.firstOrNull()
    return when {
        hinge == null || hinge.isFlat ->
            when {
                windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
                    -> with(LocalDensity.current) { (boundsInWindow.width / 2).toDp() }

                else -> 360.dp
            }

        hinge.isVertical -> with(LocalDensity.current) { (hinge.bounds.left - boundsInWindow.left).toDp() } // BookPosture
        else -> 360.dp
    }
}
