package info.dvkr.screenstream.ui.settings

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.settings.AppSettings
import info.dvkr.screenstream.tile.TileActionService
import info.dvkr.screenstream.ui.AdaptiveAppModal
import info.dvkr.screenstream.ui.dynamicThemeAvailable
import io.screenstream.foundation.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.IOException

private val nightModeWriteMutex = Mutex()

private enum class SettingsRow { LANGUAGE, DARK_THEME }

@Composable
internal fun SettingsModal(
    onDismissRequest: () -> Unit,
    dialogBounds: DpRect?,
    appSettings: AppSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope()
) {
    val settingsData = appSettings.data.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val log: Logger = koinInject(parametersHolder = remember(context) { parametersOf(context) })
    var showLanguages by retain { mutableStateOf(false) }
    var nightModeMenuExpanded by retain { mutableStateOf(false) }
    var restoreFocusTo by retain { mutableStateOf<SettingsRow?>(null) }
    val settingsScroll = retain { ScrollState(0) }
    val languagesScroll = if (showLanguages) retain { ScrollState(0) } else null
    val languageFocus = remember { FocusRequester() }
    val nightModeFocus = remember { FocusRequester() }

    fun returnToSettings(): Boolean {
        if (!showLanguages) return false
        restoreFocusTo = SettingsRow.LANGUAGE
        showLanguages = false
        return true
    }

    fun dismissNightModeMenu() {
        nightModeMenuExpanded = false
        restoreFocusTo = SettingsRow.DARK_THEME
    }

    fun showMessage(@StringRes messageRes: Int) {
        if (!scope.isActive || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    fun saveNightMode(selectedMode: Int) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (!isActive) return@launch
            try {
                // Accepted choices can still be pending when the activity recreates.
                withContext(NonCancellable) {
                    nightModeWriteMutex.withLock {
                        appSettings.updateData {
                            if (selectedMode == displayedNightMode(nightMode)) this else copy(nightMode = selectedMode)
                        }
                    }
                }
            } catch (error: IOException) {
                log.e("NightModeSettings", "Failed to save night mode", error)
                showMessage(R.string.app_pref_save_error)
            }
        }
    }

    val openLanguageSettings = dropUnlessResumed {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageUri = Uri.fromParts("package", context.packageName, null)
            if (context.tryOpenSettings(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, packageUri), log)) {
                return@dropUnlessResumed
            }
        }
        showLanguages = true
    }
    val openNotificationSettings = dropUnlessResumed {
        if (!context.openNotificationSettings(log)) {
            showMessage(R.string.app_pref_notifications_open_error)
        }
    }

    AdaptiveAppModal(
        title = stringResource(if (showLanguages) R.string.app_pref_language else R.string.app_settings_title),
        onDismissRequest = onDismissRequest,
        onBack = if (showLanguages) ({ returnToSettings() }) else null,
        dialogBounds = dialogBounds,
    ) {
        val body = Modifier
            .fillMaxWidth()
            .verticalScroll(languagesScroll ?: settingsScroll)
        Column(
            if (showLanguages) body
                .selectableGroup()
                .padding(horizontal = 16.dp, vertical = 8.dp) else body
        ) {
            if (showLanguages) {
                LanguageOptions { language ->
                    if (returnToSettings()) {
                        val locales = language?.let { LocaleListCompat.create(it) } ?: LocaleListCompat.getEmptyLocaleList()
                        AppCompatDelegate.setApplicationLocales(locales)
                    }
                }
                return@Column
            }

            SideEffect {
                when (restoreFocusTo) {
                    SettingsRow.LANGUAGE -> languageFocus.requestFocus()
                    SettingsRow.DARK_THEME -> nightModeFocus.requestFocus()
                    null -> Unit
                }
                restoreFocusTo = null
            }

            SettingActionRow(
                iconRes = R.drawable.app_icon_translate,
                title = stringResource(R.string.app_pref_language),
                summary = stringResource(R.string.app_pref_language_summary),
                onClick = openLanguageSettings,
                modifier = Modifier.focusRequester(languageFocus),
            )

            HorizontalDivider()
            NightModeOptions(
                nightMode = settingsData.nightMode,
                menuExpanded = nightModeMenuExpanded,
                onOpenMenu = dropUnlessResumed { nightModeMenuExpanded = true },
                onDismissMenu = ::dismissNightModeMenu,
                onNightModeSelect = { selectedMode ->
                    if (nightModeMenuExpanded) {
                        dismissNightModeMenu()
                        saveNightMode(selectedMode)
                    }
                },
                modifier = Modifier.focusRequester(nightModeFocus),
            )

            if (dynamicThemeAvailable) {
                HorizontalDivider()
                DynamicColorSetting(checked = settingsData.dynamicTheme) { dynamicTheme ->
                    scope.launch {
                        try {
                            appSettings.updateData { copy(dynamicTheme = dynamicTheme) }
                        } catch (error: IOException) {
                            log.e("SettingsRoute", "Failed to save dynamic theme", error)
                            showMessage(R.string.app_pref_save_error)
                        }
                    }
                }
            }

            HorizontalDivider()
            SettingActionRow(
                iconRes = R.drawable.app_icon_notifications,
                title = stringResource(R.string.app_pref_notifications),
                summary = stringResource(R.string.app_pref_notifications_summary),
                onClick = openNotificationSettings,
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                HorizontalDivider()
                SettingActionRow(
                    iconRes = R.drawable.app_icon_tile_small,
                    title = stringResource(R.string.app_pref_add_tile),
                    summary = stringResource(R.string.app_pref_add_tile_summary),
                    onClick = dropUnlessResumed {
                        TileActionService.requestAddTile(context, log) { result ->
                            when (result) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                    showMessage(R.string.app_pref_tile_already_added)

                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
                                StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> Unit

                                else -> showMessage(R.string.app_pref_add_tile_error)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun DynamicColorSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        leadingContent = { Icon(painterResource(R.drawable.app_icon_palette), contentDescription = null) },
        supportingContent = { Text(stringResource(R.string.app_pref_dynamic_color_summary)) },
        verticalAlignment = Alignment.CenterVertically,
        trailingContent = {
            val end = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else 0f
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.graphicsLayer {
                    scaleX = 0.75f
                    scaleY = 0.75f
                    transformOrigin = TransformOrigin(end, 0.5f)
                },
                thumbContent = if (checked) {
                    {
                        Icon(
                            painterResource(R.drawable.app_icon_check_small),
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        },
    ) { Text(stringResource(R.string.app_pref_dynamic_color)) }
}

private fun Context.openNotificationSettings(log: Logger): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        if (tryOpenSettings(intent, log)) return true
    }
    val packageUri = Uri.fromParts("package", packageName, null)
    return tryOpenSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri), log)
}

private fun Context.tryOpenSettings(intent: Intent, log: Logger): Boolean = try {
    startActivity(intent)
    true
} catch (failure: ActivityNotFoundException) {
    log.w("SettingsScreen", "No activity for ${intent.action}", failure)
    false
} catch (failure: SecurityException) {
    log.w("SettingsScreen", "Activity launch denied for ${intent.action}", failure)
    false
}

@Composable
private fun SettingActionRow(
    @DrawableRes iconRes: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Button },
        leadingContent = { Icon(painterResource(iconRes), contentDescription = null) },
        supportingContent = { Text(summary) },
    ) { Text(title) }
}
