package info.dvkr.screenstream.ui.tabs.settings.app.settings.general

import android.content.res.Resources
import android.os.Build
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ModuleSettings
import info.dvkr.screenstream.common.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

internal object NightMode : ModuleSettings.Item {
    override val id: String = AppSettings.Key.NIGHT_MODE.name
    override val position: Int = 1
    override val available: Boolean = true

    override fun has(resources: Resources, text: String): Boolean = with(resources) {
        getString(R.string.app_pref_night_mode).contains(text, ignoreCase = true) ||
                getStringArray(nightModeOptionsRes).any { it.contains(text, ignoreCase = true) }
    }

    @Composable
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, enabled: Boolean, onDetailShow: () -> Unit) {
        val appSettings = koinInject<AppSettings>()
        val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
        val nightModeOptions = stringArrayResource(id = nightModeOptionsRes)
        val nightModeSummary = nightModeOptions[getNightModeIndex(appSettingsState.value.nightMode)]

        NightModeUI(horizontalPadding, nightModeSummary, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val appSettings = koinInject<AppSettings>()
        val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
        val nightModeOptions = stringArrayResource(id = nightModeOptionsRes)
        val nightModeIndex = getNightModeIndex(appSettingsState.value.nightMode)
        val scope = rememberCoroutineScope()

        NightModeDetailUI(headerContent, nightModeOptions, nightModeIndex) { index ->
            if (nightModeIndex != index) {
                scope.launch { appSettings.updateData { copy(nightMode = nightModesCompat[index].mode) } }
            }
        }
    }

    @ArrayRes
    private val nightModeOptionsRes =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) R.array.app_pref_night_mode_options_api21_28
        else R.array.app_pref_night_mode_options_api29

    private fun getNightModeIndex(@AppCompatDelegate.NightMode nightMode: Int): Int =
        nightModesCompat.firstOrNull { it.mode == nightMode }?.index ?: nightModesCompat[1].index

    private data class NightMode(val index: Int, @field:AppCompatDelegate.NightMode val mode: Int)

    private val nightModesCompat =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            listOf(
                NightMode(0, AppCompatDelegate.MODE_NIGHT_YES),
                NightMode(1, AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY),
                NightMode(2, AppCompatDelegate.MODE_NIGHT_NO),
            )
        else
            listOf(
                NightMode(0, AppCompatDelegate.MODE_NIGHT_YES),
                NightMode(1, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
                NightMode(2, AppCompatDelegate.MODE_NIGHT_NO)
            )
}

@Composable
private fun NightModeUI(
    horizontalPadding: Dp,
    nightModeSummary: String,
    onDetailShow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onDetailShow)
            .padding(horizontal = horizontalPadding + 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painter = painterResource(R.drawable.theme_light_dark), contentDescription = null, modifier = Modifier.padding(end = 16.dp))

        Column(modifier = Modifier.weight(1F)) {
            Text(
                text = stringResource(id = R.string.app_pref_night_mode),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                fontSize = 18.sp,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = nightModeSummary,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun NightModeDetailUI(
    headerContent: @Composable (String) -> Unit,
    nightModeOptions: Array<String>,
    nightModeIndex: Int,
    onSelected: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent.invoke(stringResource(id = R.string.app_pref_night_mode))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            nightModeOptions.forEachIndexed { index, text ->
                Row(
                    modifier = Modifier
                        .selectable(selected = nightModeIndex == index, onClick = { onSelected.invoke(index) }, role = Role.RadioButton)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = nightModeIndex == index, onClick = null)
                    Text(text = text, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}