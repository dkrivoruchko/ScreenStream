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

internal object NotifySlowViewersSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Automation,
        titleRes = R.string.mjpeg_pref_automation_skipped_frames_warning,
        summaryRes = R.string.mjpeg_pref_automation_skipped_frames_warning_summary,
    )
}

@Composable
internal fun NotifySlowViewersSettingItem(
    notifySlowViewers: Boolean,
    onBehaviorChange: (StreamBehaviorSettings.() -> StreamBehaviorSettings) -> Unit,
) {
    SettingsItem(
        title = stringResource(NotifySlowViewersSearch.info.titleRes),
        summary = stringResource(NotifySlowViewersSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_wifi_notification,
        onClick = { onBehaviorChange { copy(notifySlowViewers = !this.notifySlowViewers) } },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (notifySlowViewers) ToggleableState.On else ToggleableState.Off
        },
        trailingContent = {
            SettingSwitch(
                checked = notifySlowViewers,
                compact = true,
            )
        },
    )
}
