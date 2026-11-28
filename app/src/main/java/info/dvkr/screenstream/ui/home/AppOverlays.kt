package info.dvkr.screenstream.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.repeatOnLifecycle
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AppDialog
import info.dvkr.screenstream.ui.settings.SettingsModal
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.compose.LocalRuntimePermissions
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.permission.PermissionState
import io.screenstream.core.permission.RuntimePermission
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class PrimaryPaneOverlay { MethodChooser, Settings, About, ExitConfirmation }

private val StreamingModuleManager.State.allowsMethodSelection: Boolean
    get() = when (this) {
        is StreamingModuleManager.State.Running,
        is StreamingModuleManager.State.Unresponsive,
        is StreamingModuleManager.State.Failed -> true

        else -> false
    }

@Composable
internal fun AppHeaderWithOverlays(
    streamingModuleManager: StreamingModuleManager,
    streamingModuleManagerState: StreamingModuleManager.State,
    inlineMethodSelector: Boolean,
    headerDialogBounds: DpRect?,
    contentDialogBounds: DpRect?,
    initialMethodChooserPending: MutableState<Boolean>,
    content: @Composable (@Composable () -> Unit) -> Unit,
) {
    var activeOverlay by retain { mutableStateOf<PrimaryPaneOverlay?>(null) }
    val scope = rememberCoroutineScope()
    val methodSelectionEnabled = streamingModuleManagerState.allowsMethodSelection

    fun openOverlay(overlay: PrimaryPaneOverlay) {
        if (activeOverlay != null) return
        val state = streamingModuleManager.state.value
        val isMethodChooser = overlay == PrimaryPaneOverlay.MethodChooser
        if (isMethodChooser) {
            if (!methodSelectionEnabled || !state.allowsMethodSelection) return
        } else if (state == StreamingModuleManager.State.Closing || state == StreamingModuleManager.State.Closed) {
            return
        }
        activeOverlay = overlay
        if (isMethodChooser) initialMethodChooserPending.value = false
    }

    if (initialMethodChooserPending.value && activeOverlay == null && methodSelectionEnabled) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val notificationPermission = LocalRuntimePermissions.current.state(RuntimePermission.Notifications)
        LaunchedEffect(lifecycle, notificationPermission, streamingModuleManager) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(notificationPermission, streamingModuleManager.state) { permission, state ->
                    permission != PermissionState.Requesting && state.allowsMethodSelection
                }.first { ready ->
                    if (ready && initialMethodChooserPending.value && notificationPermission.value != PermissionState.Requesting) {
                        openOverlay(PrimaryPaneOverlay.MethodChooser)
                    }
                    // Keep waiting if either live check blocked the opening.
                    !initialMethodChooserPending.value
                }
            }
        }
    }

    val selectedModuleId = streamingModuleManagerState.instanceId?.moduleId
    val isOpening = streamingModuleManagerState == StreamingModuleManager.State.Opening
    var lastRenderedModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    val displayedModuleId = if (isOpening) lastRenderedModuleId else selectedModuleId
    val displayedMethodDescription = streamingModuleManager.modules.firstOrNull { it.id == displayedModuleId }?.description
    if (!isOpening) SideEffect { lastRenderedModuleId = selectedModuleId }

    content {
        AppHeader(
            streamingModuleDescription = displayedMethodDescription,
            methodSelectionEnabled = methodSelectionEnabled,
            inlineMethodSelector = inlineMethodSelector,
            onChooseMethod = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.MethodChooser) },
            onOpenSettings = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.Settings) },
            onOpenAbout = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.About) },
            onRequestExit = dropUnlessResumed { openOverlay(PrimaryPaneOverlay.ExitConfirmation) },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
    }

    val displayedOverlay = activeOverlay ?: return
    val dismissOverlay = {
        if (activeOverlay == displayedOverlay) activeOverlay = null
    }
    when (displayedOverlay) {
        PrimaryPaneOverlay.MethodChooser -> {
            if (!methodSelectionEnabled) {
                SideEffect { dismissOverlay() }
                return
            }

            StreamingMethodChooserModal(
                modules = streamingModuleManager.modules,
                selectedModuleId = selectedModuleId,
                onMethodSelect = { moduleId ->
                    scope.launch {
                        if (activeOverlay == PrimaryPaneOverlay.MethodChooser && streamingModuleManager.state.value.allowsMethodSelection) {
                            activeOverlay = null
                            streamingModuleManager.selectModule(moduleId)
                        }
                    }
                },
                onDismissRequest = dismissOverlay,
                dialogBounds = headerDialogBounds,
            )
        }

        PrimaryPaneOverlay.Settings -> SettingsModal(onDismissRequest = dismissOverlay, dialogBounds = contentDialogBounds)
        PrimaryPaneOverlay.About -> AboutModal(onDismissRequest = dismissOverlay, dialogBounds = contentDialogBounds)
        PrimaryPaneOverlay.ExitConfirmation -> ExitConfirmationModal(
            onDismissRequest = dismissOverlay,
            onConfirm = dropUnlessResumed {
                if (activeOverlay == PrimaryPaneOverlay.ExitConfirmation) {
                    activeOverlay = null
                    streamingModuleManager.exit()
                }
            },
            dialogBounds = headerDialogBounds,
        )
    }
}

@Composable
private fun ExitConfirmationModal(onDismissRequest: () -> Unit, onConfirm: () -> Unit, dialogBounds: DpRect?) {
    AppDialog(title = stringResource(R.string.app_exit_dialog_title), onDismissRequest = onDismissRequest, dialogBounds = dialogBounds) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_exit_dialog_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.app_exit_dialog_message), modifier = Modifier.weight(1f, fill = false))
            FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismissRequest) { Text(stringResource(android.R.string.cancel)) }
                Button(onClick = onConfirm) { Text(stringResource(R.string.app_action_exit)) }
            }
        }
    }
}
