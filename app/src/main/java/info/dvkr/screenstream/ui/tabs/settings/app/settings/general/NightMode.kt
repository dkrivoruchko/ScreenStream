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
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
    override fun ItemUI(horizontalPadding: Dp, coroutineScope: CoroutineScope, onDetailShow: () -> Unit) {
        val appSettings = koinInject<AppSettings>()
        val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
        val nightModeOptions = stringArrayResource(id = nightModeOptionsRes)
        val nightModeSummary = remember { derivedStateOf { nightModeOptions[getNightModeIndex(appSettingsState.value.nightMode)] } }

        NightModeUI(horizontalPadding, nightModeSummary.value, onDetailShow)
    }

    @Composable
    override fun DetailUI(headerContent: @Composable (String) -> Unit) {
        val appSettings = koinInject<AppSettings>()
        val appSettingsState = appSettings.data.collectAsStateWithLifecycle()
        val nightModeOptions = stringArrayResource(id = nightModeOptionsRes)
        val nightModeIndex = remember { derivedStateOf { getNightModeIndex(appSettingsState.value.nightMode) } }
        val scope = rememberCoroutineScope()

        NightModeDetailUI(headerContent, nightModeOptions, nightModeIndex.value) { index ->
            if (nightModeIndex.value != index) {
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

    private data class NightMode(val index: Int, @AppCompatDelegate.NightMode val mode: Int)

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
        Icon(imageVector = Icon_ThemeLightDark, contentDescription = null, modifier = Modifier.padding(end = 16.dp))

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

private val Icon_ThemeLightDark: ImageVector = materialIcon(name = "ThemeLightDark") {
    materialPath {
        verticalLineToRelative(0.0F)
        moveTo(7.5F, 2.0F)
        curveTo(5.71F, 3.15F, 4.5F, 5.18F, 4.5F, 7.5F)
        curveTo(4.5F, 9.82F, 5.71F, 11.85F, 7.53F, 13.0F)
        curveTo(4.46F, 13.0F, 2.0F, 10.54F, 2.0F, 7.5F)
        arcTo(5.5F, 5.5F, 0.0F, false, true, 7.5F, 2.0F)
        moveTo(19.07F, 3.5F)
        lineTo(20.5F, 4.93F)
        lineTo(4.93F, 20.5F)
        lineTo(3.5F, 19.07F)
        lineTo(19.07F, 3.5F)
        moveTo(12.89F, 5.93F)
        lineTo(11.41F, 5.0F)
        lineTo(9.97F, 6.0F)
        lineTo(10.39F, 4.3F)
        lineTo(9.0F, 3.24F)
        lineTo(10.75F, 3.12F)
        lineTo(11.33F, 1.47F)
        lineTo(12.0F, 3.1F)
        lineTo(13.73F, 3.13F)
        lineTo(12.38F, 4.26F)
        lineTo(12.89F, 5.93F)
        moveTo(9.59F, 9.54F)
        lineTo(8.43F, 8.81F)
        lineTo(7.31F, 9.59F)
        lineTo(7.65F, 8.27F)
        lineTo(6.56F, 7.44F)
        lineTo(7.92F, 7.35F)
        lineTo(8.37F, 6.06F)
        lineTo(8.88F, 7.33F)
        lineTo(10.24F, 7.36F)
        lineTo(9.19F, 8.23F)
        lineTo(9.59F, 9.54F)
        moveTo(19.0F, 13.5F)
        arcTo(5.5F, 5.5F, 0.0F, false, true, 13.5F, 19.0F)
        curveTo(12.28F, 19.0F, 11.15F, 18.6F, 10.24F, 17.93F)
        lineTo(17.93F, 10.24F)
        curveTo(18.6F, 11.15F, 19.0F, 12.28F, 19.0F, 13.5F)
        moveTo(14.6F, 20.08F)
        lineTo(17.37F, 18.93F)
        lineTo(17.13F, 22.28F)
        lineTo(14.6F, 20.08F)
        moveTo(18.93F, 17.38F)
        lineTo(20.08F, 14.61F)
        lineTo(22.28F, 17.15F)
        lineTo(18.93F, 17.38F)
        moveTo(20.08F, 12.42F)
        lineTo(18.94F, 9.64F)
        lineTo(22.28F, 9.88F)
        lineTo(20.08F, 12.42F)
        moveTo(9.63F, 18.93F)
        lineTo(12.4F, 20.08F)
        lineTo(9.87F, 22.27F)
        lineTo(9.63F, 18.93F)
        close()
    }
}