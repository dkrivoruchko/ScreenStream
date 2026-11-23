package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AdaptiveAppModal
import info.dvkr.screenstream.ui.ModalPane
import io.screenstream.core.module.StreamingModule

@Composable
internal fun StreamingMethodChooserModal(
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    selectionEnabled: Boolean,
    onMethodSelect: (StreamingModule.Id) -> Unit,
    onDismiss: () -> Unit,
    pane: ModalPane?,
) {
    var detailsModuleId by retain { mutableStateOf<String?>(null) }
    val detailsModule = modules.firstOrNull { it.id.value == detailsModuleId }
    val chooserScrollState = retain { ScrollState(0) }
    val detailsScrollState = retain(detailsModuleId) { ScrollState(0) }
    var focusSelectedOnEntry by retain { mutableStateOf(true) }
    val returnToChooser = { detailsModuleId = null }
    val onBack = returnToChooser.takeIf { detailsModule != null }
    val title = stringResource(detailsModule?.description?.titleRes ?: R.string.app_method_choose)
    val focus = remember { FocusRequester() }
    val focusCurrentMethod = focusSelectedOnEntry && selectionEnabled && modules.any { it.id == selectedModuleId }

    AdaptiveAppModal(
        title = title,
        onDismiss = onDismiss,
        pane = pane,
        onBack = onBack,
        actionFocusRequester = focus.takeUnless { focusCurrentMethod },
    ) {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(detailsModuleId, focusCurrentMethod) {
            if (inputMode.inputMode == InputMode.Keyboard) focus.requestFocus()
        }
        val group = if (detailsModule == null) Modifier.selectableGroup() else Modifier
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .then(group)
                .verticalScroll(if (detailsModule == null) chooserScrollState else detailsScrollState),
        ) {
            if (detailsModule == null) {
                modules.forEachIndexed { index, module ->
                    if (index > 0) HorizontalDivider()
                    val current = module.id == selectedModuleId
                    StreamingMethodChoiceRow(
                        description = module.description,
                        selected = current,
                        selectionEnabled = selectionEnabled,
                        modifier = if (focusCurrentMethod && current) Modifier.focusRequester(focus) else Modifier,
                        onSelect = {
                            onMethodSelect(module.id)
                            onDismiss()
                        },
                        onDetails = {
                            // Focusing a list item on return could move the restored scroll position.
                            focusSelectedOnEntry = false
                            detailsModuleId = module.id.value
                        },
                    )
                }
            } else {
                val description = detailsModule.description
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(description.introRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StreamingMethodDetailsSection(
                        title = stringResource(R.string.app_method_benefits),
                        items = stringArrayResource(description.benefitsRes),
                    )
                    StreamingMethodDetailsSection(
                        title = stringResource(R.string.app_method_things_to_know),
                        items = stringArrayResource(description.thingsToKnowRes),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingMethodChoiceRow(
    description: StreamingModule.Description,
    selected: Boolean,
    selectionEnabled: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(description.titleRes)
    val detailsDescription = stringResource(R.string.app_method_details_description, title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = selectionEnabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = selectionEnabled)
        Column(modifier = Modifier.weight(1F)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(description.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = title, modifier = Modifier.weight(1F), style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1F)) {
                    Text(text = stringResource(description.summaryRes), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(description.conditionsRes),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                )
                TextButton(
                    onClick = onDetails,
                    modifier = Modifier
                        .widthIn(min = 64.dp, max = 112.dp)
                        .semantics { contentDescription = detailsDescription },
                ) {
                    Text(text = stringResource(R.string.app_method_details))
                }
            }
        }
    }
}

@Composable
private fun StreamingMethodDetailsSection(title: String, items: Array<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "\u2022", style = MaterialTheme.typography.bodyMedium)
                Text(text = item, modifier = Modifier.weight(1F), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
