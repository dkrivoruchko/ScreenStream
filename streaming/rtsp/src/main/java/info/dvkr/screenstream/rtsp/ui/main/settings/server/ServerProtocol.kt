package info.dvkr.screenstream.rtsp.ui.main.settings.server

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import info.dvkr.screenstream.rtsp.R
import info.dvkr.screenstream.rtsp.settings.RtspSettings
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SelectionEditor
import info.dvkr.screenstream.rtsp.ui.main.settings.common.SettingValueRow

@Composable
internal fun ServerProtocolRow(
    enabled: Boolean,
    protocol: RtspSettings.Values.ProtocolPolicy,
    onDetailShow: () -> Unit
) {
    SettingValueRow(
        enabled = enabled,
        iconRes = R.drawable.protocol_24px,
        title = stringResource(id = R.string.rtsp_pref_protocol),
        summary = stringResource(id = R.string.rtsp_pref_protocol_summary),
        valueText = protocol.name,
        onClick = onDetailShow
    )
}

@Composable
internal fun ServerProtocolEditor(
    protocol: RtspSettings.Values.ProtocolPolicy,
    onValueChange: (RtspSettings.Values.ProtocolPolicy) -> Unit
) {
    SelectionEditor(
        options = RtspSettings.Values.ProtocolPolicy.entries.map(RtspSettings.Values.ProtocolPolicy::name),
        selectedIndex = RtspSettings.Values.ProtocolPolicy.entries.indexOf(protocol),
        onValueChange = { index -> onValueChange(RtspSettings.Values.ProtocolPolicy.entries[index]) },
        description = stringResource(id = R.string.rtsp_pref_protocol_summary)
    )
}
