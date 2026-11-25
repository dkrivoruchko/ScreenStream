package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AppDialog
import info.dvkr.screenstream.ui.ModalPane
import info.dvkr.screenstream.ui.about.AboutModal
import info.dvkr.screenstream.ui.settings.SettingsModal
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule

private enum class HeaderModal { MethodChooser, Settings, About, ExitConfirmation }

@Composable
internal fun StreamHeader(
    state: StreamingModuleManager.State,
    manager: StreamingModuleManager,
    inlineMethodSelector: Boolean,
    reduceVerticalSpacing: Boolean,
    primaryModalPane: ModalPane?,
    contentModalPane: ModalPane?,
    modifier: Modifier = Modifier,
) {
    var activeModal by retain { mutableStateOf<HeaderModal?>(null) }

    fun openModal(modal: HeaderModal) {
        if (activeModal != null) return
        when (manager.state.value) {
            StreamingModuleManager.State.Exiting -> return
            StreamingModuleManager.State.Switching -> if (modal == HeaderModal.MethodChooser) return
            else -> Unit
        }
        activeModal = modal
    }

    val selectedModuleId = state.instanceId?.moduleId
    val isSwitching = state == StreamingModuleManager.State.Switching
    var lastRenderedModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    val displayedModuleId = if (isSwitching) lastRenderedModuleId else selectedModuleId
    val displayedMethod = manager.modules.firstOrNull { it.id == displayedModuleId }?.description
    if (!isSwitching) SideEffect { lastRenderedModuleId = selectedModuleId }
    val methodSelectionEnabled = !isSwitching
    val openMethodChooser = dropUnlessResumed { openModal(HeaderModal.MethodChooser) }

    Column(modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (inlineMethodSelector) {
                MethodChooserCard(
                    selectedMethod = displayedMethod,
                    onClick = openMethodChooser,
                    enabled = methodSelectionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    inlineWithActions = true,
                    reduceVerticalSpacing = reduceVerticalSpacing,
                )
            } else {
                Text(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = dropUnlessResumed { openModal(HeaderModal.Settings) }) {
                Icon(painterResource(R.drawable.app_icon_settings), stringResource(R.string.app_settings_title))
            }
            IconButton(onClick = dropUnlessResumed { openModal(HeaderModal.About) }) {
                Icon(painterResource(R.drawable.app_icon_info), stringResource(R.string.app_about_title))
            }
            IconButton(onClick = dropUnlessResumed { openModal(HeaderModal.ExitConfirmation) }) {
                Icon(painterResource(R.drawable.app_icon_power_settings_new), stringResource(R.string.app_action_exit))
            }
        }
        if (!inlineMethodSelector) {
            MethodChooserCard(
                selectedMethod = displayedMethod,
                onClick = openMethodChooser,
                enabled = methodSelectionEnabled,
            )
        }
    }

    val displayedModal = activeModal ?: return
    val dismissModal = {
        if (activeModal == displayedModal) activeModal = null
    }
    when (displayedModal) {
        HeaderModal.MethodChooser -> StreamingMethodChooserModal(
            modules = manager.modules,
            selectedModuleId = selectedModuleId,
            selectionEnabled = methodSelectionEnabled,
            onMethodSelect = manager::selectModule,
            onDismiss = dismissModal,
            pane = primaryModalPane,
        )

        HeaderModal.Settings -> SettingsModal(onDismiss = dismissModal, pane = contentModalPane)
        HeaderModal.About -> AboutModal(onDismiss = dismissModal, pane = contentModalPane)
        HeaderModal.ExitConfirmation -> ExitConfirmationDialog(
            onDismiss = dismissModal,
            onConfirm = dropUnlessResumed {
                if (activeModal == HeaderModal.ExitConfirmation && manager.state.value != StreamingModuleManager.State.Exiting) {
                    activeModal = null
                    manager.exit()
                }
            },
            pane = primaryModalPane,
        )
    }
}

@Composable
private fun MethodChooserCard(
    selectedMethod: StreamingModule.Description?,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    inlineWithActions: Boolean = false,
    reduceVerticalSpacing: Boolean = false,
) {
    val chooseLabel = stringResource(R.string.app_method_choose)

    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                if (inlineWithActions) heading()
                this.onClick(label = chooseLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(
                    horizontal = if (inlineWithActions) 8.dp else 16.dp,
                    vertical = if (reduceVerticalSpacing) 4.dp else 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val methodColor = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
            if (selectedMethod != null) {
                Icon(
                    painter = painterResource(selectedMethod.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = methodColor,
                )
            }

            val supportingColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (reduceVerticalSpacing) 0.dp else 4.dp),
            ) {
                if (inlineWithActions || selectedMethod != null) {
                    Text(
                        text = stringResource(R.string.app_method_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = supportingColor,
                        maxLines = if (inlineWithActions) 1 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = selectedMethod?.let { stringResource(it.titleRes) } ?: chooseLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selectedMethod != null) methodColor else LocalContentColor.current,
                    maxLines = if (inlineWithActions) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.app_icon_chevron), contentDescription = null, tint = supportingColor)
        }
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
