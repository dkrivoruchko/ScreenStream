package info.dvkr.screenstream.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.app.InlineAdaptiveBanner
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.settings.AppSettings
import info.dvkr.screenstream.tile.TileActionService
import info.dvkr.screenstream.ui.SettingsDestination
import info.dvkr.screenstream.ui.theme.dynamicThemeAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.IOException

@Composable
internal fun SettingsRoute(
    onOpenDetail: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    appSettings: AppSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val settingsData = appSettings.data.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val notificationSettingsIntent = context.appNotificationSettingsIntent()
    val openLanguageDetail = dropUnlessResumed { onOpenDetail(SettingsDestination.APP_LANGUAGE) }

    SettingsScreen(
        nightMode = settingsData.nightMode,
        dynamicTheme = settingsData.dynamicTheme,
        showDynamicTheme = dynamicThemeAvailable,
        showNotifications = notificationSettingsIntent != null,
        showTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        onLanguageClick = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                openLanguageDetail()
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_LOCALE_SETTINGS).setData(Uri.fromParts("package", context.packageName, null))
                )
            }
        },
        onNightModeClick = dropUnlessResumed { onOpenDetail(SettingsDestination.DARK_THEME) },
        onDynamicThemeChange = { dynamicTheme ->
            if (settingsData.dynamicTheme != dynamicTheme) {
                scope.launch {
                    try {
                        appSettings.updateData { copy(dynamicTheme = dynamicTheme) }
                    } catch (error: IOException) {
                        XLog.e(context.getLog("SettingsRoute", "Failed to save dynamic theme"), error)
                    }
                }
            }
        },
        onNotificationSettingsClick = {
            notificationSettingsIntent?.let { intent ->
                try {
                    context.startActivity(intent)
                } catch (failure: ActivityNotFoundException) {
                    XLog.w(context.getLog("NotificationSettings", "Settings Activity is unavailable"), failure)
                } catch (failure: SecurityException) {
                    XLog.w(context.getLog("NotificationSettings", "Settings Activity launch was denied"), failure)
                }
            }
        },
        onAddTileClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TileActionService.showAddTileRequest(context)
            }
        },
        modifier = modifier,
    )
}

private fun Context.appNotificationSettingsIntent(): Intent? {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    return intent.takeIf { it.resolveActivity(packageManager) != null }
}

@Composable
private fun SettingsScreen(
    nightMode: Int,
    dynamicTheme: Boolean,
    showDynamicTheme: Boolean,
    showNotifications: Boolean,
    showTile: Boolean,
    onLanguageClick: () -> Unit,
    onNightModeClick: () -> Unit,
    onDynamicThemeChange: (Boolean) -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onAddTileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    LazyColumn(
        modifier = modifier
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

        item(key = "APP_LOCALE", contentType = "ITEM") {
            SettingsItem(showDivider = false) {
                SettingActionRow(
                    iconRes = R.drawable.translate_24px,
                    title = stringResource(R.string.app_pref_locale),
                    summary = stringResource(R.string.app_pref_locale_summary),
                    onClick = onLanguageClick,
                )
            }
        }

        item(key = "NIGHT_MODE", contentType = "ITEM") {
            SettingsItem {
                SettingActionRow(
                    iconRes = R.drawable.theme_light_dark,
                    title = stringResource(R.string.app_pref_night_mode),
                    summary = nightModeSummary(nightMode),
                    onClick = onNightModeClick,
                )
            }
        }

        if (showDynamicTheme) {
            item(key = "DYNAMIC_THEME", contentType = "ITEM") {
                SettingsItem {
                    SettingSwitchRow(
                        checked = dynamicTheme,
                        iconRes = R.drawable.palette_24px,
                        title = stringResource(R.string.app_pref_dynamic_theme),
                        summary = stringResource(R.string.app_pref_dynamic_theme_summary),
                        onValueChange = onDynamicThemeChange,
                    )
                }
            }
        }

        if (showNotifications) {
            item(key = "NOTIFICATIONS", contentType = "ITEM") {
                SettingsItem {
                    SettingActionRow(
                        iconRes = R.drawable.notifications_24px,
                        title = stringResource(R.string.app_pref_notification),
                        summary = stringResource(R.string.app_pref_notification_summary),
                        onClick = onNotificationSettingsClick,
                    )
                }
            }
        }

        if (showTile) {
            item(key = "TILE", contentType = "ITEM") {
                SettingsItem {
                    SettingActionRow(
                        iconRes = R.drawable.tile_small_24px,
                        title = stringResource(R.string.app_pref_tile),
                        summary = stringResource(R.string.app_pref_tile_summary),
                        onClick = onAddTileClick,
                    )
                }
            }
        }

        item(key = "AD_BANNER", contentType = "AD") {
            InlineAdaptiveBanner(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LazyItemScope.SettingsItem(
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateItem()
    ) {
        if (showDivider) HorizontalDivider()
        content()
    }
}
