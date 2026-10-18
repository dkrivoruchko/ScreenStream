package io.screenstream.mjpeg.ui.settings.access

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.AccessSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingSwitch
import io.screenstream.mjpeg.ui.settings.SettingsItem

internal object HidePinWhenCaptureStartsSearch {
    val info: SettingSearchInfo = SettingSearchInfo(
        group = SettingSearchGroup.Access,
        titleRes = R.string.mjpeg_pref_access_hide_pin_on_start,
        summaryRes = R.string.mjpeg_pref_access_hide_pin_on_start_summary,
    )
}

@Composable
internal fun HidePinWhenCaptureStartsSettingItem(
    hidePinOnCaptureStart: Boolean,
    onAccessChange: (AccessSettings.() -> AccessSettings) -> Unit,
) {
    SettingsItem(
        title = stringResource(HidePinWhenCaptureStartsSearch.info.titleRes),
        summary = stringResource(HidePinWhenCaptureStartsSearch.info.summaryRes),
        iconRes = R.drawable.mjpeg_icon_password,
        onClick = {
            onAccessChange { copy(hidePinOnCaptureStart = !this.hidePinOnCaptureStart) }
        },
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = if (hidePinOnCaptureStart) ToggleableState.On else ToggleableState.Off
        },
        trailingContent = {
            SettingSwitch(
                checked = hidePinOnCaptureStart,
                compact = true,
            )
        },
    )
}
