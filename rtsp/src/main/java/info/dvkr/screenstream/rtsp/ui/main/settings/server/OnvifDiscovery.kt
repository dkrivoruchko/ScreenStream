package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingSwitchRow

@Composable
internal fun OnvifDiscoveryRow(
    enabled: Boolean,
    onvifDiscoveryEnabled: Boolean,
    onValueChange: (Boolean) -> Unit
) {
    SettingSwitchRow(
        enabled = enabled,
        checked = onvifDiscoveryEnabled,
        iconRes = R.drawable.lan_24px,
        title = stringResource(id = R.string.rtsp_pref_onvif_discovery),
        summary = stringResource(id = R.string.rtsp_pref_onvif_discovery_summary),
        onValueChange = onValueChange
    )
}
