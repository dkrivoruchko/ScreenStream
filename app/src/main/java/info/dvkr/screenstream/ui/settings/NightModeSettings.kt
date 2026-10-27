package info.dvkr.screenstream.ui.settings

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.IOException

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
internal fun nightModeSummary(@AppCompatDelegate.NightMode nightMode: Int): String {
    val nightModeEntries = stringArrayResource(id = nightModeOptionsRes).zip(nightModes)
    return (nightModeEntries.firstOrNull { it.second == nightMode } ?: nightModeEntries[1]).first
}

@Composable
internal fun NightModeSettings(
    onBack: () -> Unit,
    appSettings: AppSettings = koinInject(),
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val context = LocalContext.current
    val settingsData = appSettings.data.collectAsStateWithLifecycle().value
    SettingsDetailLayout(
        title = stringResource(R.string.app_pref_night_mode),
        onBack = onBack,
        modifier = Modifier.fillMaxWidth(),
    ) {
        NightModeOptions(
            nightMode = settingsData.nightMode,
            onNightModeSelect = { nightMode ->
                if (settingsData.nightMode != nightMode) {
                    scope.launch {
                        try {
                            appSettings.updateData { copy(nightMode = nightMode) }
                        } catch (error: IOException) {
                            XLog.e(context.getLog("NightModeSettings", "Failed to save night mode"), error)
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun NightModeOptions(
    @AppCompatDelegate.NightMode nightMode: Int,
    onNightModeSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nightModeEntries = stringArrayResource(id = nightModeOptionsRes).zip(nightModes)
    val selectedMode = (nightModeEntries.firstOrNull { it.second == nightMode } ?: nightModeEntries[1]).second

    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .padding(vertical = 8.dp)
            .selectableGroup()
            .verticalScroll(rememberScrollState())
    ) {
        nightModeEntries.forEach { (label, mode) ->
            Row(
                modifier = Modifier
                    .selectable(
                        selected = selectedMode == mode,
                        onClick = {
                            if (selectedMode != mode) {
                                onNightModeSelect(mode)
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
                RadioButton(selected = selectedMode == mode, onClick = null)
                Text(text = label, modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
