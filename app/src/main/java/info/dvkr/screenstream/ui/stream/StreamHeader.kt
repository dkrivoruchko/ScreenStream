package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
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
import info.dvkr.screenstream.ads.AnchoredAdaptiveBanner
import info.dvkr.screenstream.ui.AppDialog
import info.dvkr.screenstream.ui.ModalPane
import info.dvkr.screenstream.ui.about.AboutModal
import info.dvkr.screenstream.ui.settings.SettingsModal
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule

private enum class AppAction { Settings, About, MethodChooser, Exit }

@Composable
internal fun StreamHeader(
    state: StreamingModuleManager.State,
    manager: StreamingModuleManager,
    isTabletop: Boolean,
    primaryModalPane: ModalPane?,
    tabletopModalPane: ModalPane?,
    modifier: Modifier = Modifier,
) {
    val modules = manager.modules
    val selectedModuleId = state.instanceIdOrNull?.moduleId
    val selectionEnabled = state != StreamingModuleManager.State.Switching
    var action by retain { mutableStateOf<AppAction?>(null) }
    val settingsOpener = remember { FocusRequester() }
    val aboutOpener = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    var returnFocusTo by remember { mutableStateOf<FocusRequester?>(null) }
    LaunchedEffect(returnFocusTo) {
        returnFocusTo?.let { opener ->
            if (inputModeManager.inputMode == InputMode.Keyboard) opener.requestFocus()
            returnFocusTo = null
        }
    }

    fun openAction(requested: AppAction) {
        if (action == null && manager.state.value != StreamingModuleManager.State.Exiting) {
            returnFocusTo = null
            action = requested
        }
    }

    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            IconButton(onClick = dropUnlessResumed { openAction(AppAction.Exit) }) {
                Icon(painterResource(R.drawable.app_icon_power_settings_new), stringResource(R.string.app_action_exit))
            }
            IconButton(
                onClick = dropUnlessResumed { openAction(AppAction.Settings) },
                modifier = Modifier.focusRequester(settingsOpener),
            ) {
                Icon(painterResource(R.drawable.app_icon_settings), stringResource(R.string.app_settings_title))
            }
            IconButton(
                onClick = dropUnlessResumed { openAction(AppAction.About) },
                modifier = Modifier.focusRequester(aboutOpener),
            ) {
                Icon(painterResource(R.drawable.app_icon_info), stringResource(R.string.app_about_title))
            }
        }
        StreamingMethodSelector(
            selectedMethod = modules.firstOrNull { it.id == selectedModuleId }?.description,
            enabled = selectionEnabled,
            onOpenChooser = dropUnlessResumed { openAction(AppAction.MethodChooser) },
        )
        AnchoredAdaptiveBanner(
            minimumRemainingHeight = if (isTabletop) 56.dp else 112.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val current = action ?: return
    val onDismiss = {
        if (action == current) {
            action = null
            returnFocusTo = when (current) {
                AppAction.Settings -> settingsOpener
                AppAction.About -> aboutOpener
                AppAction.MethodChooser, AppAction.Exit -> null
            }
        }
    }
    val settingsAndAboutPane = tabletopModalPane ?: primaryModalPane
    when (current) {
        AppAction.Settings -> SettingsModal(onDismiss = onDismiss, pane = settingsAndAboutPane)
        AppAction.About -> AboutModal(onDismiss = onDismiss, pane = settingsAndAboutPane)
        AppAction.MethodChooser -> StreamingMethodChooserModal(
            modules = modules,
            selectedModuleId = selectedModuleId,
            selectionEnabled = selectionEnabled,
            onMethodSelect = manager::selectModule,
            onDismiss = onDismiss,
            pane = primaryModalPane,
        )

        AppAction.Exit -> ExitConfirmationDialog(
            onDismiss = onDismiss,
            onConfirm = dropUnlessResumed {
                if (action == AppAction.Exit && manager.state.value != StreamingModuleManager.State.Exiting) {
                    action = null
                    manager.exit()
                }
            },
            pane = primaryModalPane,
        )
    }
}

@Composable
private fun ExitConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit, pane: ModalPane?) {
    val title = stringResource(R.string.app_exit_dialog_title)
    val message = stringResource(R.string.app_exit_dialog_message)
    val cancel: @Composable () -> Unit = {
        TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    }
    val confirm: @Composable () -> Unit = {
        Button(onClick = onConfirm) { Text(stringResource(R.string.app_action_exit)) }
    }
    val scroll = retain { ScrollState(0) }
    if (pane == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(message) },
            dismissButton = cancel,
            confirmButton = confirm,
        )
    } else {
        AppDialog(onDismiss = onDismiss, title = title, pane = pane) {
            Column(
                modifier = Modifier
                    .verticalScroll(scroll)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(message)
                FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cancel()
                    confirm()
                }
            }
        }
    }
}

@Composable
private fun StreamingMethodSelector(
    selectedMethod: StreamingModule.Description?,
    enabled: Boolean,
    onOpenChooser: () -> Unit,
) {
    val chooseLabel = stringResource(R.string.app_method_choose)

    OutlinedCard(
        onClick = onOpenChooser,
        enabled = enabled,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = chooseLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val primary = if (enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
        val secondary = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedMethod != null) {
                Icon(
                    painter = painterResource(selectedMethod.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = primary,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selectedMethod != null) {
                    Text(
                        text = stringResource(R.string.app_method_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = secondary,
                    )
                }
                Text(
                    text = selectedMethod?.let { stringResource(it.titleRes) } ?: chooseLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selectedMethod != null) primary else LocalContentColor.current,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(painterResource(R.drawable.app_icon_chevron), contentDescription = null, tint = secondary)
        }
    }
}
