package io.screenstream.mjpeg.ui.settings.automation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import io.screenstream.mjpeg.ui.settings.rememberMjpegSettingsSave

internal val automationSettingSearchEntries: List<SettingSearchInfo> = listOf(
    StopAfterAddressLossSearch.info,
    ImageAfterStopSearch.info,
    KeepScreenAwakeSearch.info,
    StopWhenScreenTurnsOffSearch.info,
    NotifySlowViewersSearch.info,
)

@Composable
internal fun AutomationSettingsGroup(
    behaviorSettings: StreamBehaviorSettings,
    mjpegSettings: MjpegSettings,
    onSaveError: (String) -> Unit,
    visibleSettings: Set<SettingSearchInfo>?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val saveErrorText = stringResource(R.string.mjpeg_pref_automation_save_error)
    val save = rememberMjpegSettingsSave(mjpegSettings, "Automation.save", "Could not save automation settings") { onSaveError(saveErrorText) }

    fun updateBehavior(transform: StreamBehaviorSettings.() -> StreamBehaviorSettings) = save { copy(behavior = behavior.transform()) }

    SettingsGroupCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        searching = visibleSettings != null,
        group = SettingSearchGroup.Automation,
        iconRes = R.drawable.mjpeg_icon_automation,
        modifier = Modifier.fillMaxWidth(),
    ) {
        automationSettingSearchEntries
            .filter { visibleSettings == null || it in visibleSettings }
            .forEachIndexed { index, searchInfo ->
                key(searchInfo) {
                    if (index > 0) HorizontalDivider()
                    when (searchInfo) {
                        StopAfterAddressLossSearch.info -> StopAfterAddressLossSettingItem(behaviorSettings.stopAfterNetworkLossSeconds, ::updateBehavior)
                        ImageAfterStopSearch.info -> ImageAfterStopSettingItem(behaviorSettings.postStopImage, ::updateBehavior)
                        KeepScreenAwakeSearch.info -> KeepScreenAwakeSettingItem(behaviorSettings.keepScreenAwake, ::updateBehavior)
                        StopWhenScreenTurnsOffSearch.info -> StopWhenScreenTurnsOffSettingItem(behaviorSettings.stopOnScreenOff, ::updateBehavior)
                        NotifySlowViewersSearch.info -> NotifySlowViewersSettingItem(behaviorSettings.notifySlowViewers, ::updateBehavior)
                        else -> Unit
                    }
                }
            }
    }
}
