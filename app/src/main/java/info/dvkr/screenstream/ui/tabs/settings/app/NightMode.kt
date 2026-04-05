package info.dvkr.screenstream.ui.tabs.settings.app

import android.os.Build
import androidx.annotation.ArrayRes
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.settings.app.common.SettingActionRow

@ArrayRes
private val nightModeOptionsRes =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) R.array.app_pref_night_mode_options_api21_28
    else R.array.app_pref_night_mode_options_api29

private val nightModes =
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
        listOf(
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
            AppCompatDelegate.MODE_NIGHT_NO,
        )
    else
        listOf(
            AppCompatDelegate.MODE_NIGHT_YES,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO
        )

@Composable
internal fun NightModeRow(
    @AppCompatDelegate.NightMode nightMode: Int,
    onShowDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nightModeEntries = stringArrayResource(id = nightModeOptionsRes).zip(nightModes)
    val selectedNightMode = nightModeEntries.firstOrNull { it.second == nightMode } ?: nightModeEntries[1]

    SettingActionRow(
        iconRes = R.drawable.theme_light_dark,
        title = stringResource(id = R.string.app_pref_night_mode),
        summary = selectedNightMode.first,
        onClick = onShowDetail,
        modifier = modifier
    )
}

@Composable
internal fun NightModeDetail(
    headerContent: @Composable (String) -> Unit,
    @AppCompatDelegate.NightMode nightMode: Int,
    onNightModeSelected: (Int) -> Unit
) {
    val nightModeEntries = stringArrayResource(id = nightModeOptionsRes).zip(nightModes)
    val selectedNightMode = nightModeEntries.firstOrNull { it.second == nightMode } ?: nightModeEntries[1]

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        headerContent(stringResource(id = R.string.app_pref_night_mode))

        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(vertical = 8.dp)
                .selectableGroup()
                .verticalScroll(rememberScrollState())
        ) {
            nightModeEntries.forEach { (label, mode) ->
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = selectedNightMode.second == mode,
                            onClick = {
                                if (selectedNightMode.second != mode) {
                                    onNightModeSelected(mode)
                                }
                            },
                            role = Role.RadioButton
                        )
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .defaultMinSize(minHeight = 48.dp)
                        .minimumInteractiveComponentSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selectedNightMode.second == mode, onClick = null)
                    Text(text = label, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}
