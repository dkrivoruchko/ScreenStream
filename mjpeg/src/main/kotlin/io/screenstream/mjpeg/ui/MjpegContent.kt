package io.screenstream.mjpeg.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenstream.mjpeg.MjpegCaptureSession
import io.screenstream.mjpeg.R
import io.screenstream.mjpeg.settings.MjpegSettings
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
import kotlinx.coroutines.launch

internal val LocalMjpegSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> {
    error("MJPEG SnackbarHostState is not provided")
}

@Composable
internal fun MjpegContent(
    uiController: UiController,
    mjpegSettings: MjpegSettings,
    modifier: Modifier = Modifier,
) {
    val uiState by uiController.state.collectAsStateWithLifecycle()
    // Compose retained editors in the first restored frame, before the settings flow emits.
    var retainedNonSecretSettings by retain { mutableStateOf<MjpegSettings.Data?>(null) }
    val settingsData by mjpegSettings.data.collectAsStateWithLifecycle(initialValue = retainedNonSecretSettings)
    SideEffect(settingsData) {
        retainedNonSecretSettings = settingsData?.let { it.copy(access = it.access.copy(pin = null)) }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val searchQuery = rememberTextFieldState()
    val matchingSettings = rememberMatchingSettings(searchQuery.text.toString())
    var addressesExpanded by retain { mutableStateOf(true) }
    var statisticsExpanded by retain { mutableStateOf(false) }
    var expandedGroups by retain { mutableStateOf(emptySet<SettingSearchGroup>()) }

    fun isGroupVisible(group: SettingSearchGroup): Boolean =
        matchingSettings == null || matchingSettings.any { it.group == group }

    fun setGroupExpanded(group: SettingSearchGroup, expanded: Boolean) {
        expandedGroups = if (expanded) expandedGroups + group else expandedGroups - group
    }

    fun showSnackbarMessage(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    CompositionLocalProvider(LocalMjpegSnackbarHostState provides snackbarHostState) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val action = uiState.action
                CaptureActionButton(
                    action = action,
                    onClick = {
                        // Ignore clicks from an outdated capture action.
                        val currentAction = uiController.state.value.action
                        if (currentAction != action) return@CaptureActionButton
                        when (currentAction) {
                            is UiController.Action.Start -> if (currentAction.enabled) uiController.send(UiController.Command.Start)
                            UiController.Action.Busy -> Unit
                            is UiController.Action.Stop -> uiController.send(UiController.Command.Stop(currentAction.attempt))
                        }
                    },
                )
            },
        ) { innerPadding ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(if (maxWidth >= 800.dp) 2 else 1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = 0.dp,
                        end = 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 8.dp,
                    ),
                    verticalItemSpacing = 4.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "interfaces", contentType = "interfaces") {
                        StreamAddressesContent(
                            addressServers = uiState.addressServers,
                            appliedPin = uiState.appliedPin,
                            hidePinOnCapture = uiState.hidePinOnCapture,
                            expanded = addressesExpanded,
                            onExpandedChange = { addressesExpanded = it },
                            onRetry = { serverId -> uiController.send(UiController.Command.RetryServer(serverId)) },
                            onShowMessage = ::showSnackbarMessage,
                        )
                    }
                    item(key = "statistics", contentType = "statistics") {
                        StreamStatisticsContent(
                            statistics = uiState.statistics,
                            expanded = statisticsExpanded,
                            onExpandedChange = { statisticsExpanded = it },
                        )
                    }
                    item(key = "settings-search", contentType = "settings-search") {
                        SearchSettingsField(query = searchQuery, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    uiState.suspensionProblem?.let { problem ->
                        item(key = "suspension", contentType = "suspension") {
                            SuspensionProblemMessage(problem)
                        }
                    }
                    settingsData?.let { settings ->
                        if (matchingSettings != null && matchingSettings.isEmpty()) {
                            item(key = "settings-search-empty", contentType = "settings-search-empty", span = StaggeredGridItemSpan.FullLine) {
                                Text(stringResource(R.string.mjpeg_pref_search_no_results), Modifier.padding(16.dp))
                            }
                        }
                        if (isGroupVisible(SettingSearchGroup.Image)) {
                            item(key = "settings-image", contentType = "settings-image") {
                                ImageSettingsGroup(
                                    imageSettings = settings.image,
                                    mjpegSettings = mjpegSettings,
                                    editPolicy = uiState.editPolicy,
                                    onSaveError = ::showSnackbarMessage,
                                    visibleSettings = matchingSettings,
                                    expanded = SettingSearchGroup.Image in expandedGroups,
                                    onExpandedChange = { setGroupExpanded(SettingSearchGroup.Image, it) },
                                )
                            }
                        }
                        if (isGroupVisible(SettingSearchGroup.Automation)) {
                            item(key = "settings-automation", contentType = "settings-automation") {
                                AutomationSettingsGroup(
                                    behaviorSettings = settings.behavior,
                                    mjpegSettings = mjpegSettings,
                                    onSaveError = ::showSnackbarMessage,
                                    visibleSettings = matchingSettings,
                                    expanded = SettingSearchGroup.Automation in expandedGroups,
                                    onExpandedChange = { setGroupExpanded(SettingSearchGroup.Automation, it) },
                                )
                            }
                        }
                        if (isGroupVisible(SettingSearchGroup.Connection)) {
                            item(key = "settings-connection", contentType = "settings-connection") {
                                ConnectionSettingsGroup(
                                    networkSettings = settings.network,
                                    mjpegSettings = mjpegSettings,
                                    editPolicy = uiState.editPolicy,
                                    onSaveError = ::showSnackbarMessage,
                                    visibleSettings = matchingSettings,
                                    expanded = SettingSearchGroup.Connection in expandedGroups,
                                    onExpandedChange = { setGroupExpanded(SettingSearchGroup.Connection, it) },
                                )
                            }
                        }
                        if (isGroupVisible(SettingSearchGroup.WebPage)) {
                            item(key = "settings-webpage", contentType = "settings-webpage") {
                                WebPageSettingsGroup(
                                    webPageSettings = settings.web,
                                    mjpegSettings = mjpegSettings,
                                    onSaveError = ::showSnackbarMessage,
                                    visibleSettings = matchingSettings,
                                    expanded = SettingSearchGroup.WebPage in expandedGroups,
                                    onExpandedChange = { setGroupExpanded(SettingSearchGroup.WebPage, it) },
                                )
                            }
                        }
                        if (isGroupVisible(SettingSearchGroup.Access)) {
                            item(key = "settings-access", contentType = "settings-access") {
                                AccessSettingsGroup(
                                    accessSettings = settings.access,
                                    mjpegSettings = mjpegSettings,
                                    editPolicy = uiState.editPolicy,
                                    onSaveError = ::showSnackbarMessage,
                                    visibleSettings = matchingSettings,
                                    expanded = SettingSearchGroup.Access in expandedGroups,
                                    onExpandedChange = { setGroupExpanded(SettingSearchGroup.Access, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureActionButton(action: UiController.Action, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Button(
            onClick = onClick,
            enabled = when (action) {
                is UiController.Action.Start -> action.enabled
                UiController.Action.Busy -> false
                is UiController.Action.Stop -> true
            },
            modifier = Modifier
                .padding(8.dp)
                .heightIn(min = 48.dp),
        ) {
            when (action) {
                is UiController.Action.Start -> Text(stringResource(R.string.mjpeg_stream_start_capture), fontSize = 18.sp)
                UiController.Action.Busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                is UiController.Action.Stop -> Text(stringResource(R.string.mjpeg_stream_stop_capture), fontSize = 18.sp)
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
