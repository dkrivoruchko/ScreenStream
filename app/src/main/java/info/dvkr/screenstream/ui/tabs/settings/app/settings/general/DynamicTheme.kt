package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

internal object DynamicTheme : ModuleSettings.Item {
    override val id: String = AppSettings.Key.DYNAMIC_THEME.name
    override val position: Int = 2
    override val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_dynamic_theme).contains(text, ignoreCase = true) ||
                getString(R.string.app_pref_dynamic_theme_summary).contains(text, ignoreCase = true)
    }

    @Composable
    override fun ListUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) =
        DynamicThemeUI(horizontalPadding, coroutineScope)
}

@Composable
private fun DynamicThemeUI(
    horizontalPadding: Dp,
    scope: CoroutineScope,
    appSettings: AppSettings = koinInject()
) {
    val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
    val dynamicTheme = remember { derivedStateOf { appSettingsState.value.dynamicTheme } }

    Row(
        modifier = Modifier
            .toggleable(
                value = dynamicTheme.value,
                onValueChange = { scope.launch { withContext(NonCancellable) { appSettings.updateData { copy(dynamicTheme = it) } } } }
            )
            .padding(start = horizontalPadding + 16.dp, end = horizontalPadding + 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.FilterBAndW,
            contentDescription = stringResource(id = R.string.app_pref_dynamic_theme),
            modifier = Modifier.padding(end = 16.dp)
        )

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.app_pref_dynamic_theme),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = stringResource(id = R.string.app_pref_dynamic_theme_summary),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Switch(
            checked = dynamicTheme.value,
            onCheckedChange = null,
            modifier = Modifier.scale(0.7F),
        )
    }
}