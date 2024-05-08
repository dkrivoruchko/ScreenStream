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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
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
import info.dvkr.screenstream.ui.LocalContentBoundsInWindow
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsTabContent(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsTabViewModel = koinViewModel()
) {
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val listPanePreferredWidth = calculateListPanePreferredWidth(windowAdaptiveInfo, boundsInWindow = LocalContentBoundsInWindow.current)
    val scaffoldDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo).copy(horizontalPartitionSpacerSize = 0.dp)
    val scaffoldNavigator = rememberListDetailPaneScaffoldNavigator(scaffoldDirective)

    val lazyListState = rememberLazyListState()

    ListDetailPaneScaffold(
        directive = scaffoldNavigator.scaffoldDirective,
        value = scaffoldNavigator.scaffoldValue,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(listPanePreferredWidth)) {
                SettingsListPane(
                    lazyListState = lazyListState,
                    settingsListFlow = settingsViewModel.settingsListFlow,
                    searchTextFlow = settingsViewModel.searchTextFlow,
                    onSearchTextChange = { text -> settingsViewModel.setSearchText(text) },
                    onSettingSelected = { moduleSettingsId ->
                        settingsViewModel.onSettingSelected(moduleSettingsId)
                        scaffoldNavigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                    }
                )
            }
        },
        detailPane = {
            AnimatedPane(modifier = Modifier.fillMaxSize()) {
                val selectedItem = settingsViewModel.selectedItemFlow.collectAsStateWithLifecycle()
                selectedItem.value?.DetailUI(onBackClick = { if (scaffoldNavigator.canNavigateBack()) scaffoldNavigator.navigateBack() }) { title ->
                    DetailUITitle(scaffoldNavigator, title)
                }
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun DetailUITitle(navigator: ThreePaneScaffoldNavigator<*>, title: String) {
    val canNavigateBack = remember { navigator.canNavigateBack() }

    BackHandler(enabled = canNavigateBack) { navigator.navigateBack() }

    if (canNavigateBack) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navigator.navigateBack() }) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.app_pref_back))
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
    onSettingSelected: (ModuleSettings.Id) -> Unit
) {
    val settingsList = settingsListFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BoxWithConstraints {
        val horizontalPadding = remember(maxWidth) { if (maxWidth >= 480.dp) 16.dp else 0.dp }

        Column(modifier = Modifier.fillMaxSize()) {
            AdaptiveBanner(modifier = Modifier.fillMaxWidth())

            SettingsListHeader(searchTextFlow, onSearchTextChange, horizontalPadding)

            HorizontalDivider(modifier = Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                state = lazyListState
            ) {
                settingsList.value.forEach { module ->
                    stickyHeader(key = module.id, contentType = "HEADER") {
                        module.TitleUI(
                            horizontalPadding = horizontalPadding,
                            modifier = Modifier.animateItemPlacement()
                        )
                    }

                    module.groups.forEach { settingsGroup ->
                        item(key = "${module.id}#${settingsGroup.id}", contentType = "HEADER") {
                            settingsGroup.TitleUI(
                                horizontalPadding = horizontalPadding,
                                modifier = Modifier.animateItemPlacement()
                            )
                        }

                        itemsIndexed(
                            items = settingsGroup.items,
                            key = { _, settingsItem -> "${module.id}#${settingsGroup.id}#${settingsItem.id}" },
                            contentType = { _, _ -> "ITEM" },
                            itemContent = { index, settingsItem ->
                                Column(modifier = Modifier.animateItemPlacement()) {
                                    settingsItem.ListUI(
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
    horizontalPadding: Dp = 0.dp
) {
    val searchVisible = remember { mutableStateOf(searchTextFlow.value.isNotBlank()) }

    Crossfade(
        targetState = searchVisible.value,
        modifier = Modifier
            .background(color = MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = horizontalPadding, vertical = 8.dp)
            .fillMaxWidth(),
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
        modifier = Modifier
            .size(size)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.app_tab_settings),
            modifier = Modifier.weight(1.0F),
            style = MaterialTheme.typography.headlineSmall
        )
        IconButton(onClick = { searchVisible.value = true }) {
            Icon(imageVector = Icons.Default.Search, contentDescription = stringResource(id = R.string.app_pref_search))
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .focusRequester(focusRequester),
        placeholder = { Text(text = stringResource(id = R.string.app_pref_settings_search)) },
        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = stringResource(id = R.string.app_pref_search)) },
        trailingIcon = {
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    searchVisible.value = false
                    searchTextLocalProxy.value = ""
                    onSearchTextChange.invoke("")
                }
            ) {
                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(id = R.string.app_pref_close))
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