package info.dvkr.screenstream.ui.tabs.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.AdaptiveBanner
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.notification.NotificationHelper
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.logger.AppLogger
import info.dvkr.screenstream.tile.TileActionService
import info.dvkr.screenstream.ui.tabs.settings.app.AppLocaleDetail
import info.dvkr.screenstream.ui.tabs.settings.app.AppLocaleRow
import info.dvkr.screenstream.ui.tabs.settings.app.DynamicThemeRow
import info.dvkr.screenstream.ui.tabs.settings.app.LoggingRow
import info.dvkr.screenstream.ui.tabs.settings.app.NightModeDetail
import info.dvkr.screenstream.ui.tabs.settings.app.NightModeRow
import info.dvkr.screenstream.ui.tabs.settings.app.NotificationsRow
import info.dvkr.screenstream.ui.tabs.settings.app.TileRow
import info.dvkr.screenstream.ui.theme.dynamicThemeAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class AppSetting { APP_LOCALE, NIGHT_MODE }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun SettingsTabContent(
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    appSettings: AppSettings = koinInject(),
    notificationHelper: NotificationHelper = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val settingsData = appSettings.data.collectAsStateWithLifecycle().value

    val context = LocalContext.current
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val listPanePreferredWidth = calculateListPanePreferredWidth(windowAdaptiveInfo, boundsInWindow)
    val scaffoldDirective = calculatePaneScaffoldDirective(windowAdaptiveInfo, HingePolicy.AvoidOccluding)
        .copy(verticalPartitionSpacerSize = 0.dp, horizontalPartitionSpacerSize = 0.dp)
    val navigator = rememberListDetailPaneScaffoldNavigator<AppSetting>(scaffoldDirective)
    val lazyListState = rememberLazyListState()

    BackHandler(enabled = navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(listPanePreferredWidth)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    AdaptiveBanner(modifier = Modifier.fillMaxWidth())

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .imePadding(),
                        state = lazyListState
                    ) {
                        item(key = "SETTINGS_HEADER", contentType = "HEADER") {
                            Text(
                                text = stringResource(id = R.string.app_pref_settings),
                                modifier = Modifier
                                    .background(color = MaterialTheme.colorScheme.secondaryContainer)
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                fontSize = 18.sp,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        item(key = AppSetting.APP_LOCALE, contentType = "ITEM") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            ) {
                                AppLocaleRow(
                                    onShowDetail = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, AppSetting.APP_LOCALE) } })
                            }
                        }

                        item(key = AppSetting.NIGHT_MODE, contentType = "ITEM") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                            ) {
                                NightModeRow(
                                    nightMode = settingsData.nightMode,
                                    onShowDetail = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, AppSetting.NIGHT_MODE) } })
                            }
                        }

                        if (dynamicThemeAvailable) {
                            item(key = "DYNAMIC_THEME", contentType = "ITEM") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                ) {
                                    HorizontalDivider()
                                    DynamicThemeRow(
                                        dynamicTheme = settingsData.dynamicTheme,
                                        onValueChange = { dynamicTheme ->
                                            if (settingsData.dynamicTheme != dynamicTheme) {
                                                scope.launch { appSettings.updateData { copy(dynamicTheme = dynamicTheme) } }
                                            }
                                        })
                                }
                            }
                        }

                        if (notificationHelper.canOpenAppNotificationSettings()) {
                            item(key = "NOTIFICATIONS", contentType = "ITEM") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                ) {
                                    HorizontalDivider()
                                    NotificationsRow(onClick = { context.startActivity(notificationHelper.getNotificationSettingsIntent()) })
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            item(key = "TILE", contentType = "ITEM") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                ) {
                                    HorizontalDivider()
                                    TileRow(onClick = { TileActionService.showAddTileRequest(context) })
                                }
                            }
                        }

                        if (AppLogger.isLoggingOn) {
                            item(key = "LOGGING", contentType = "ITEM") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                ) {
                                    HorizontalDivider()
                                    LoggingRow()
                                }
                            }
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane(modifier = Modifier.fillMaxSize()) {
                when (navigator.currentDestination?.contentKey) {
                    AppSetting.APP_LOCALE -> AppLocaleDetail(
                        headerContent = { title -> DetailUITitle(title, navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } } })

                    AppSetting.NIGHT_MODE -> NightModeDetail(
                        headerContent = { title -> DetailUITitle(title, navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } } },
                        nightMode = settingsData.nightMode,
                        onNightModeSelected = { nightMode ->
                            if (settingsData.nightMode != nightMode) {
                                scope.launch { appSettings.updateData { copy(nightMode = nightMode) } }
                            }
                        })

                    else -> Unit
                }
            }
        },
        modifier = modifier
    )
}

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
