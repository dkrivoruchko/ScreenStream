package io.screenstream.mjpeg.ui.settings.automation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.screenstream.core.logE
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.StreamBehaviorSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()

    fun updateBehavior(transform: StreamBehaviorSettings.() -> StreamBehaviorSettings) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mjpegSettings.updateData { copy(behavior = behavior.transform()) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mjpegSettings.logE("Automation.save", "Could not save automation settings", failure)
                onSaveError(saveErrorText)
            }
        }
    }

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
