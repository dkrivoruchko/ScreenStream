package io.screenstream.mjpeg.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.Section
import io.screenstream.core.module.StreamingModule.Ui.Section.Role
import io.screenstream.mjpeg.MjpegCaptureSession
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
import io.screenstream.mjpeg.ui.main.ClientConnectionsEditor
import io.screenstream.mjpeg.ui.main.StreamAddressesContent
import io.screenstream.mjpeg.ui.main.StreamStatisticsContent
import io.screenstream.mjpeg.ui.settings.SearchSettingsField
import io.screenstream.mjpeg.ui.settings.SettingSearchGroup
import io.screenstream.mjpeg.ui.settings.access.AccessSettingsGroup
import io.screenstream.mjpeg.ui.settings.automation.AutomationSettingsGroup
import io.screenstream.mjpeg.ui.settings.connection.ConnectionSettingsGroup
import io.screenstream.mjpeg.ui.settings.images.ImageSettingsGroup
import io.screenstream.mjpeg.ui.settings.rememberMatchingSettings
import io.screenstream.mjpeg.ui.settings.webpage.WebPageSettingsGroup

@Composable
internal fun MjpegContent(
    uiController: UiController,
    mjpegSettings: MjpegSettings,
    layout: @Composable (StreamingModule.Ui) -> Unit,
) {
    val uiState by uiController.state.collectAsStateWithLifecycle()
    // Compose retained editors in the first restored frame, before the settings flow emits.
    var retainedNonSecretSettings by retain { mutableStateOf<MjpegSettings.Data?>(null) }
    val settingsData by mjpegSettings.data.collectAsStateWithLifecycle(initialValue = retainedNonSecretSettings)
    SideEffect(settingsData) {
        retainedNonSecretSettings = settingsData?.let { it.copy(access = it.access.copy(pin = null)) }
    }
    val searchQuery = rememberTextFieldState()
    val matchingSettings = rememberMatchingSettings(searchQuery.text.toString())
    var addressesExpanded by retain { mutableStateOf(true) }
    var statisticsExpanded by retain { mutableStateOf(false) }
    var showClientConnections by retain { mutableStateOf(false) }
    var expandedGroups by retain { mutableStateOf(emptySet<SettingSearchGroup>()) }

    fun isGroupVisible(group: SettingSearchGroup): Boolean =
        matchingSettings == null || matchingSettings.any { it.group == group }

    fun setGroupExpanded(group: SettingSearchGroup, expanded: Boolean) {
        expandedGroups = if (expanded) expandedGroups + group else expandedGroups - group
    }

    if (showClientConnections && uiState.statistics.clients.isEmpty()) {
        SideEffect(Unit) { showClientConnections = false }
    }

    val uiSections = buildList {
        add(Section("interfaces", Role.Overview) {
            StreamAddressesContent(
                addressServers = uiState.addressServers,
                appliedPin = uiState.appliedPin,
                hidePinOnCapture = uiState.hidePinOnCapture,
                expanded = addressesExpanded,
                onExpandedChange = { addressesExpanded = it },
                onRetry = { serverId -> uiController.send(UiController.Command.RetryServer(serverId)) },
            )
        })
        add(Section("statistics", Role.Overview) {
            val statistics = uiState.statistics
            StreamStatisticsContent(
                statistics = statistics,
                expanded = statisticsExpanded,
                onExpandedChange = { statisticsExpanded = it },
                onShowFullList = {
                    if (uiController.state.value.statistics.clients.isNotEmpty()) showClientConnections = true
                },
            )
            if (showClientConnections && statistics.clients.isNotEmpty()) {
                ClientConnectionsEditor(statistics = statistics, onDismiss = { showClientConnections = false })
            }
        })
        uiState.suspensionProblem?.let { problem ->
            add(Section("suspension", Role.Overview) { SuspensionProblemMessage(problem) })
        }
        add(Section("settings-search", Role.SettingsSearch) {
            SearchSettingsField(query = searchQuery, modifier = Modifier.padding(vertical = 4.dp))
        })
        settingsData?.let { settings ->
            if (matchingSettings?.isEmpty() == true) {
                add(Section("settings-search-empty", Role.Settings) {
                    Text(stringResource(R.string.mjpeg_pref_search_no_results), Modifier.padding(16.dp))
                })
            }
            for (group in SettingSearchGroup.entries) {
                if (!isGroupVisible(group)) continue
                add(Section("settings-${group.name.lowercase()}", Role.Settings) {
                    val expanded = group in expandedGroups
                    val onExpandedChange: (Boolean) -> Unit = { setGroupExpanded(group, it) }
                    when (group) {
                        SettingSearchGroup.Image -> ImageSettingsGroup(
                            imageSettings = settings.image,
                            mjpegSettings = mjpegSettings,
                            editPolicy = uiState.editPolicy,
                            visibleSettings = matchingSettings,
                            expanded = expanded,
                            onExpandedChange = onExpandedChange,
                        )

                        SettingSearchGroup.Automation -> AutomationSettingsGroup(
                            behaviorSettings = settings.behavior,
                            mjpegSettings = mjpegSettings,
                            visibleSettings = matchingSettings,
                            expanded = expanded,
                            onExpandedChange = onExpandedChange,
                        )

                        SettingSearchGroup.Connection -> ConnectionSettingsGroup(
                            networkSettings = settings.network,
                            mjpegSettings = mjpegSettings,
                            editPolicy = uiState.editPolicy,
                            visibleSettings = matchingSettings,
                            expanded = expanded,
                            onExpandedChange = onExpandedChange,
                        )

                        SettingSearchGroup.WebPage -> WebPageSettingsGroup(
                            webPageSettings = settings.web,
                            mjpegSettings = mjpegSettings,
                            visibleSettings = matchingSettings,
                            expanded = expanded,
                            onExpandedChange = onExpandedChange,
                        )

                        SettingSearchGroup.Access -> AccessSettingsGroup(
                            accessSettings = settings.access,
                            mjpegSettings = mjpegSettings,
                            editPolicy = uiState.editPolicy,
                            visibleSettings = matchingSettings,
                            expanded = expanded,
                            onExpandedChange = onExpandedChange,
                        )
                    }
                })
            }
        }
    }

    layout(object : StreamingModule.Ui {
        override val sections: List<Section> = uiSections

        @Composable
        override fun CaptureActionButton(modifier: Modifier) {
            val action = uiState.action
            CaptureButton(action = action, modifier = modifier) {
                val currentAction = uiController.state.value.action
                if (currentAction != action) return@CaptureButton
                when (currentAction) {
                    is UiController.Action.Start -> if (currentAction.enabled) uiController.send(UiController.Command.Start)
                    UiController.Action.Busy -> Unit
                    is UiController.Action.Stop -> uiController.send(UiController.Command.Stop(currentAction.attempt))
                }
            }
        }
    })
}

@Composable
private fun CaptureButton(action: UiController.Action, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = when (action) {
            is UiController.Action.Start -> action.enabled
            UiController.Action.Busy -> false
            is UiController.Action.Stop -> true
        },
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        when (action) {
            is UiController.Action.Start -> {
                Icon(painterResource(R.drawable.mjpeg_icon_play_arrow), contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.mjpeg_stream_start_capture), fontSize = 18.sp)
            }

            UiController.Action.Busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)

            is UiController.Action.Stop -> {
                Icon(painterResource(R.drawable.mjpeg_icon_stop), contentDescription = null)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.mjpeg_stream_stop_capture), fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun SuspensionProblemMessage(problem: MjpegCaptureSession.State.SuspensionProblem) {
    Text(
        stringResource(
            when (problem) {
                MjpegCaptureSession.State.SuspensionProblem.InvalidRequest -> R.string.mjpeg_error_suspension_invalid_request
                MjpegCaptureSession.State.SuspensionProblem.CaptureUnavailable -> R.string.mjpeg_error_suspension_unavailable
                MjpegCaptureSession.State.SuspensionProblem.ResourceExhausted -> R.string.mjpeg_error_suspension_resources
            }
        ),
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}
