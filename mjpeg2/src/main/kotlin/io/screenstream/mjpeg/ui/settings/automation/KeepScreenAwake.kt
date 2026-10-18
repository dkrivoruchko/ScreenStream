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

internal object KeepScreenAwakeSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Automation,
        titleRes = R.string.mjpeg_pref_stream_keep_awake,
        summaryRes = R.string.mjpeg_pref_stream_keep_awake_summary,
    )
}

@Composable
internal fun KeepScreenAwakeSettingItem(
    keepScreenAwake: Boolean,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
) {
    SettingsItem(
        title = stringResource(KeepScreenAwakeSearch.info.titleRes),
        summary = stringResource(KeepScreenAwakeSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_bedtime_off,
        onClick = { onBehaviorChange { copy(keepScreenAwake = !this.keepScreenAwake) } },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (keepScreenAwake) ToggleableState.On else ToggleableState.Off
        },
        trailingContent = {
            SettingSwitch(
                checked = keepScreenAwake,
                compact = true,
            )
        },
    )
}
