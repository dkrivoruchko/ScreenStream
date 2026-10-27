package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import io.screenstream.streaming.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val accessSettingSearchEntries: List<SettingSearchInfo> = listOf(
    PinProtectionSearch.info,
    PinSearch.info,
    LimitPinAttemptsSearch.info,
    HidePinWhenCaptureStartsSearch.info,
)

@Composable
internal fun AccessSettingsGroup(
    accessSettings: AccessSettings,
    mjpegSettings: MjpegSettings,
    editPolicy: MjpegSettings.EditPolicy,
    onSaveError: (String) -> Unit,
    visibleSettings: Set<SettingSearchInfo>?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val saveErrorText = stringResource(R.string.mjpeg_pref_access_save_error)
    val currentEditPolicy by rememberUpdatedState(editPolicy)
    val coroutineScope = rememberCoroutineScope()

    fun updateAccess(transform: AccessSettings.() -> AccessSettings) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mjpegSettings.updateData {
                    val proposed = copy(access = access.transform())
                    if (currentEditPolicy.allows(this, proposed)) proposed else this
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mjpegSettings.logE("Access.save", "Could not save access settings", failure)
                onSaveError(saveErrorText)
            }
        }
    }

    SettingsGroupCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        searching = visibleSettings != null,
        group = SettingSearchGroup.Access,
        iconRes = R.drawable.mjpeg_icon_access,
        modifier = Modifier.fillMaxWidth(),
    ) {
        accessSettingSearchEntries
            .filter { visibleSettings == null || it in visibleSettings }
            .forEachIndexed { index, searchInfo ->
                key(searchInfo) {
                    if (index > 0) HorizontalDivider()
                    when (searchInfo) {
                        PinProtectionSearch.info -> PinProtectionSettingItem(
                            accessSettings.pinEnabled,
                            accessSettings.pinPolicy,
                            editPolicy.canEditStreamSetup,
                            ::updateAccess
                        )

                        PinSearch.info -> PinSettingItem(
                            accessSettings.pin,
                            accessSettings.pinPolicy,
                            editPolicy.canEditStreamSetup,
                            ::updateAccess,
                        )

                        LimitPinAttemptsSearch.info -> LimitPinAttemptsSettingItem(
                            accessSettings.limitWrongPins,
                            accessSettings.blockMinutes,
                            editPolicy.canEditStreamSetup,
                            ::updateAccess
                        )

                        HidePinWhenCaptureStartsSearch.info -> HidePinWhenCaptureStartsSettingItem(
                            accessSettings.hidePinOnCaptureStart,
                            ::updateAccess,
                        )

                        else -> Unit
                    }
                }
            }
    }
}
