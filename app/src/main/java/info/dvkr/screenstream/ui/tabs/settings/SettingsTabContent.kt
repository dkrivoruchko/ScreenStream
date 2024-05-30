package info.dvkr.screenstream.ui.tabs.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import info.dvkr.screenstream.AdaptiveBanner
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsTabContent(
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsTabViewModel = koinViewModel()
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val listPanePreferredWidth = calculateListPanePreferredWidth(windowAdaptiveInfo, boundsInWindow)
    val scaffoldDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(horizontalPartitionSpacerSize = 0.dp)
    val navigator = rememberListDetailPaneScaffoldNavigator<ModuleSettings.Id>(scaffoldDirective)

    BackHandler(enabled = navigator.canNavigateBack()) { navigator.navigateBack() }

    val lazyListState = rememberLazyListState()

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
//            AnimatedPane(modifier = Modifier.preferredWidth(listPanePreferredWidth)) { //TODO Crash @ material3-adaptive = "1.0.0-beta02"
                SettingsListPane(
                    lazyListState = lazyListState,
                    settingsListFlow = settingsViewModel.settingsListFlow,
                    searchTextFlow = settingsViewModel.searchTextFlow,
                    onSearchTextChange = { text -> settingsViewModel.setSearchText(text) },
                    onSettingSelected = { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, it) },
                    modifier = Modifier.preferredWidth(listPanePreferredWidth)
                )
//            }
        },
        detailPane = {
//            AnimatedPane(modifier = Modifier.fillMaxSize()) { TODO Crash @ material3-adaptive = "1.0.0-beta02"
                settingsViewModel.getModuleSettingsItem(navigator.currentDestination?.content)
                    ?.DetailUI { title -> DetailUITitle(title, navigator.canNavigateBack()) { navigator.navigateBack() } }
//            }
        },
        modifier = modifier
    )
}

@Composable
private fun DetailUITitle(title: String, canNavigateBack: Boolean, navigateBack: () -> Unit) {
    if (canNavigateBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = navigateBack) {
                Icon(imageVector = Icon_ArrowBack, contentDescription = stringResource(id = R.string.app_pref_back))
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
    settingsListFlow: StateFlow<List<ModuleSettings>>,
    searchTextFlow: StateFlow<String>,
    onSearchTextChange: (String) -> Unit,
    onSettingSelected: (ModuleSettings.Id) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settingsList = settingsListFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier) {
        val horizontalPadding = if (maxWidth >= 480.dp) 16.dp else 0.dp

        val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
        val titleModifier = remember(maxWidth, secondaryContainer) {
            Modifier.background(color = secondaryContainer)
                .padding(horizontal = horizontalPadding + 16.dp, vertical = 8.dp)
                .fillMaxWidth()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AdaptiveBanner(modifier = Modifier.fillMaxWidth())

            SettingsListHeader(searchTextFlow, onSearchTextChange, titleModifier)

            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                state = lazyListState
            ) {
                settingsList.value.forEach { module ->
                    stickyHeader(key = module.id, contentType = "HEADER") {
                        module.TitleUI(modifier = titleModifier)
                    }

                    module.groups.forEach { settingsGroup ->
                        item(key = "${module.id}#${settingsGroup.id}", contentType = "HEADER") {
                            settingsGroup.TitleUI(modifier = titleModifier)
                        }

                        itemsIndexed(
                            items = settingsGroup.items,
                            key = { _, settingsItem -> "${module.id}#${settingsGroup.id}#${settingsItem.id}" },
                            contentType = { _, _ -> "ITEM" },
                            itemContent = { index, settingsItem ->
                                Column(modifier = Modifier.fillMaxWidth().animateItem()) {
                                    settingsItem.ItemUI(
                                        horizontalPadding = horizontalPadding,
                                        coroutineScope = scope,
                                        onDetailShow = {
                                            onSettingSelected(ModuleSettings.Id(module.id, settingsGroup.id, settingsItem.id))
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
}

@Composable
private fun SettingsListHeader(
    searchTextFlow: StateFlow<String>,
    onSearchTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchVisible = remember { mutableStateOf(searchTextFlow.value.isNotBlank()) }

    Crossfade(
        targetState = searchVisible.value,
        modifier = modifier,
        label = "SearchCrossfade"
    ) { showSearch ->
        SubcomposeLayout { constraints ->
            val searchPlaceables = subcompose("SettingsListSearch") {
                SettingsListSearch(searchTextFlow, onSearchTextChange, searchVisible)
            }
                .map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }

            var maxWidth = 0
            var maxHeight = 0

            searchPlaceables.forEach { placeable ->
                maxWidth += placeable.width
                maxHeight = placeable.height
            }

            val titlePlaceables: List<Placeable> = subcompose("SettingsListTitle") {
                val size = DpSize(maxWidth.toDp(), maxHeight.toDp())
                SettingsListTitle(size, searchVisible)
            }.map { measurable -> measurable.measure(constraints) }

            layout(maxWidth, maxHeight) {
                if (showSearch) {
                    searchPlaceables.forEach { placeable -> placeable.placeRelative(0, 0) }
                } else {
                    titlePlaceables.forEach { placeable -> placeable.placeRelative(0, 0) }
                }
            }
        }
    }
}

@Composable
private fun SettingsListTitle(
    size: DpSize,
    searchVisible: MutableState<Boolean>
) {
    Row(
        modifier = Modifier.size(size).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.app_tab_settings),
            modifier = Modifier.weight(1F),
            style = MaterialTheme.typography.headlineSmall
        )
        IconButton(onClick = { searchVisible.value = true }) {
            Icon(imageVector = Icon_Search, contentDescription = stringResource(id = R.string.app_pref_search))
        }
    }
}

@Composable
private fun SettingsListSearch(
    searchTextFlow: StateFlow<String>,
    onSearchTextChange: (String) -> Unit,
    searchVisible: MutableState<Boolean>,
) {
    val searchTextLocalProxy = remember { mutableStateOf(searchTextFlow.value) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = searchTextLocalProxy.value,
        onValueChange = {
            searchTextLocalProxy.value = it
            onSearchTextChange.invoke(it)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focusRequester),
        placeholder = { Text(text = stringResource(id = R.string.app_pref_settings_search)) },
        leadingIcon = { Icon(imageVector = Icon_Search, contentDescription = stringResource(id = R.string.app_pref_search)) },
        trailingIcon = {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    searchVisible.value = false
                    searchTextLocalProxy.value = ""
                    onSearchTextChange.invoke("")
                }
            ) {
                Icon(imageVector = Icon_Close, contentDescription = stringResource(id = R.string.app_pref_close))
            }
        },
        singleLine = true
    )

    LaunchedEffect(Unit) { if (searchVisible.value) focusRequester.requestFocus() }
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
            when (windowAdaptiveInfo.windowSizeClass.windowWidthSizeClass) {
                WindowWidthSizeClass.EXPANDED -> with(LocalDensity.current) { (boundsInWindow.width / 2).toDp() }
                else -> 360.dp
            }

        hinge.isVertical -> with(LocalDensity.current) { (hinge.bounds.left - boundsInWindow.left).toDp() } // BookPosture
        else -> 360.dp
    }
}

private val Icon_Search: ImageVector = materialIcon(name = "Filled.Search") {
    materialPath {
        moveTo(15.5f, 14.0f)
        horizontalLineToRelative(-0.79f)
        lineToRelative(-0.28f, -0.27f)
        curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
        curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
        reflectiveCurveTo(3.0f, 5.91f, 3.0f, 9.5f)
        reflectiveCurveTo(5.91f, 16.0f, 9.5f, 16.0f)
        curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
        lineToRelative(0.27f, 0.28f)
        verticalLineToRelative(0.79f)
        lineToRelative(5.0f, 4.99f)
        lineTo(20.49f, 19.0f)
        lineToRelative(-4.99f, -5.0f)
        close()
        moveTo(9.5f, 14.0f)
        curveTo(7.01f, 14.0f, 5.0f, 11.99f, 5.0f, 9.5f)
        reflectiveCurveTo(7.01f, 5.0f, 9.5f, 5.0f)
        reflectiveCurveTo(14.0f, 7.01f, 14.0f, 9.5f)
        reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
        close()
    }
}

private val Icon_Close: ImageVector = materialIcon(name = "Filled.Close") {
    materialPath {
        moveTo(19.0f, 6.41f)
        lineTo(17.59f, 5.0f)
        lineTo(12.0f, 10.59f)
        lineTo(6.41f, 5.0f)
        lineTo(5.0f, 6.41f)
        lineTo(10.59f, 12.0f)
        lineTo(5.0f, 17.59f)
        lineTo(6.41f, 19.0f)
        lineTo(12.0f, 13.41f)
        lineTo(17.59f, 19.0f)
        lineTo(19.0f, 17.59f)
        lineTo(13.41f, 12.0f)
        close()
    }
}

private val Icon_ArrowBack: ImageVector = materialIcon(name = "AutoMirrored.Filled.ArrowBack", autoMirror = true) {
    materialPath {
        moveTo(20.0f, 11.0f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12.0f, 4.0f)
        lineToRelative(-8.0f, 8.0f)
        lineToRelative(8.0f, 8.0f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13.0f)
        horizontalLineTo(20.0f)
        verticalLineToRelative(-2.0f)
        close()
    }
}