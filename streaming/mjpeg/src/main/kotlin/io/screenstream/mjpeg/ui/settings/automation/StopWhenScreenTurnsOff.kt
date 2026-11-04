package io.screenstream.mjpeg.ui.settings.automation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem

internal object StopWhenScreenTurnsOffSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Automation,
        titleRes = R.string.mjpeg_pref_automation_stop_on_screen_off,
        summaryRes = R.string.mjpeg_pref_automation_stop_on_screen_off_summary,
    )
}

@Composable
internal fun StopWhenScreenTurnsOffSettingItem(
    stopOnScreenOff: Boolean,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
) {
    SettingsItem(
        title = stringResource(StopWhenScreenTurnsOffSearch.info.titleRes),
        summary = stringResource(StopWhenScreenTurnsOffSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_screen_lock_portrait,
        onClick = { onBehaviorChange { copy(stopOnScreenOff = !this.stopOnScreenOff) } },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (stopOnScreenOff) ToggleableState.On else ToggleableState.Off
        },
        trailingContent = {
            SettingSwitch(
                checked = stopOnScreenOff,
                compact = true,
            )
        },
    )
}
