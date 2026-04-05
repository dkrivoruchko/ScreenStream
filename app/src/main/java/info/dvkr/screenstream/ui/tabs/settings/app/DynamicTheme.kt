package info.dvkr.screenstream.ui.tabs.settings.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.settings.app.common.SettingSwitchRow

@Composable
internal fun DynamicThemeRow(
    dynamicTheme: Boolean,
    onValueChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SettingSwitchRow(
        checked = dynamicTheme,
        iconRes = R.drawable.palette_24px,
        title = stringResource(id = R.string.app_pref_dynamic_theme),
        summary = stringResource(id = R.string.app_pref_dynamic_theme_summary),
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled
    )
}
