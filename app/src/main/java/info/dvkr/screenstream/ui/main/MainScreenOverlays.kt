package info.dvkr.screenstream.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.modal.AppDialog
import info.dvkr.screenstream.ui.modal.ModalPane
import info.dvkr.screenstream.ui.settings.SettingsModal
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule

private enum class MainScreenOverlay { MethodChooser, Settings, About, ExitConfirmation }

@Composable
internal fun MainScreenHeader(
    state: StreamingModuleManager.State,
    manager: StreamingModuleManager,
    inlineMethodSelector: Boolean,
    reduceVerticalSpacing: Boolean,
    primaryModalPane: ModalPane?,
    contentModalPane: ModalPane?,
) {
    // The movable header gives retained state a stable position across pane changes.
    var activeOverlay by retain { mutableStateOf<MainScreenOverlay?>(null) }

    fun openOverlay(overlay: MainScreenOverlay) {
        if (activeOverlay != null) return
        when (manager.state.value) {
            StreamingModuleManager.State.Exiting -> return
            StreamingModuleManager.State.Switching -> if (overlay == MainScreenOverlay.MethodChooser) return
            else -> Unit
        }
        activeOverlay = overlay
    }

    val selectedModuleId = state.instanceId?.moduleId
    val isSwitching = state == StreamingModuleManager.State.Switching
    var lastRenderedModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    val displayedModuleId = if (isSwitching) lastRenderedModuleId else selectedModuleId
    val displayedMethod = manager.modules.firstOrNull { it.id == displayedModuleId }?.description
    if (!isSwitching) SideEffect { lastRenderedModuleId = selectedModuleId }
    val methodSelectionEnabled = !isSwitching

    StreamHeader(
        displayedMethod = displayedMethod,
        methodSelectionEnabled = methodSelectionEnabled,
        inlineMethodSelector = inlineMethodSelector,
        reduceVerticalSpacing = reduceVerticalSpacing,
        onChooseMethod = dropUnlessResumed { openOverlay(MainScreenOverlay.MethodChooser) },
        onOpenSettings = dropUnlessResumed { openOverlay(MainScreenOverlay.Settings) },
        onOpenAbout = dropUnlessResumed { openOverlay(MainScreenOverlay.About) },
        onRequestExit = dropUnlessResumed { openOverlay(MainScreenOverlay.ExitConfirmation) },
    )

    val displayedOverlay = activeOverlay ?: return
    val dismissOverlay = {
        if (activeOverlay == displayedOverlay) activeOverlay = null
    }
    when (displayedOverlay) {
        MainScreenOverlay.MethodChooser -> StreamingMethodChooserModal(
            modules = manager.modules,
            selectedModuleId = selectedModuleId,
            selectionEnabled = methodSelectionEnabled,
            onMethodSelect = manager::selectModule,
            onDismiss = dismissOverlay,
            pane = primaryModalPane,
        )

        MainScreenOverlay.Settings -> SettingsModal(onDismiss = dismissOverlay, pane = contentModalPane)
        MainScreenOverlay.About -> AboutModal(onDismiss = dismissOverlay, pane = contentModalPane)
        MainScreenOverlay.ExitConfirmation -> ExitConfirmationDialog(
            onDismiss = dismissOverlay,
            onConfirm = dropUnlessResumed {
                if (activeOverlay == MainScreenOverlay.ExitConfirmation && manager.state.value != StreamingModuleManager.State.Exiting) {
                    activeOverlay = null
                    manager.exit()
                }
            },
            pane = primaryModalPane,
        )
    }
}

@Composable
private fun ExitConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, pane: ModalPane?) {
    if (pane == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.app_exit_dialog_title), Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.app_exit_dialog_message)) },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            confirmButton = { Button(onClick = onConfirm) { Text(stringResource(R.string.app_action_exit)) } },
        )
    } else {
        AppDialog(onDismiss = onDismiss, title = stringResource(R.string.app_exit_dialog_title), pane = pane) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.app_exit_dialog_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.app_exit_dialog_message))
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                    Button(onClick = onConfirm) { Text(stringResource(R.string.app_action_exit)) }
                }
            }
        }
    }
}
