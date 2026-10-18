package io.screenstream.mjpeg.ui.settings.connection

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
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.settings.NetworkSettings
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.SettingSearchInfo
import io.screenstream.mjpeg.ui.settings.SettingsGroupCard
import io.screenstream.streaming.logE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val connectionSettingSearchEntries: List<SettingSearchInfo> = listOf(
    NetworkFilterSearch.info,
    HttpPortSearch.info,
)

@Composable
internal fun ConnectionSettingsGroup(
    networkSettings: NetworkSettings,
    mjpegSettings: MjpegSettings,
    editPolicy: MjpegSettings.EditPolicy,
    onSaveError: (String) -> Unit,
    visibleSettings: Set<SettingSearchInfo>?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val saveErrorText = stringResource(R.string.mjpeg_pref_network_save_error)
    val currentEditPolicy by rememberUpdatedState(editPolicy)
    val coroutineScope = rememberCoroutineScope()

    fun updateNetwork(transform: NetworkSettings.() -> NetworkSettings) {
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                mjpegSettings.updateData {
                    val proposed = copy(network = network.transform())
                    if (currentEditPolicy.allows(this, proposed)) proposed else this
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mjpegSettings.logE("Connection.save", "Could not save connection settings", failure)
                onSaveError(saveErrorText)
            }
        }
    }

    SettingsGroupCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        searching = visibleSettings != null,
        group = SettingSearchGroup.Connection,
        iconRes = R.drawable.mjpeg_icon_settings_ethernet,
        modifier = Modifier.fillMaxWidth(),
    ) {
        connectionSettingSearchEntries
            .filter { visibleSettings == null || it in visibleSettings }
            .forEachIndexed { index, searchInfo ->
                key(searchInfo) {
                    if (index > 0) HorizontalDivider()
                    when (searchInfo) {
                        NetworkFilterSearch.info -> NetworkFilterSettingItem(networkSettings.filter, ::updateNetwork)
                        HttpPortSearch.info -> HttpPortSettingItem(networkSettings.httpPort, editPolicy.canEditStreamSetup, ::updateNetwork)
                        else -> Unit
                    }
                }
            }
    }
}
