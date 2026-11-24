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
    var detailsModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    var focusSelectionOnEntry by retain { mutableStateOf(true) }
    val chooserScrollState = retain { ScrollState(0) }
    val details = detailsModuleId?.let { id -> modules.firstOrNull { it.id == id }?.description }
    val scrollState = if (details == null) {
        chooserScrollState
    } else {
        retain(detailsModuleId) { ScrollState(0) }
    }
    val focusRequester = remember { FocusRequester() }
    val focusSelectedMethod = focusSelectionOnEntry && selectionEnabled && modules.any { it.id == selectedModuleId }

    AdaptiveAppModal(
        title = stringResource(details?.titleRes ?: R.string.app_method_choose),
        onDismiss = onDismiss,
        pane = pane,
        onBack = if (details == null) null else ({ detailsModuleId = null }),
        actionModifier = if (focusSelectedMethod) Modifier else Modifier.focusRequester(focusRequester),
    ) {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(detailsModuleId, focusSelectedMethod) {
            if (inputMode.inputMode == InputMode.Keyboard) focusRequester.requestFocus()
        }
        val contentModifier = Modifier.fillMaxWidth()
        if (details != null) {
            Column(
                modifier = contentModifier
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(details.introRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StreamingMethodDetailsSection(
                    title = stringResource(R.string.app_method_benefits),
                    items = stringArrayResource(details.benefitsRes),
                )
                StreamingMethodDetailsSection(
                    title = stringResource(R.string.app_method_things_to_know),
                    items = stringArrayResource(details.thingsToKnowRes),
                )
            }
            return@AdaptiveAppModal
        }

        Column(
            modifier = contentModifier
                .selectableGroup()
                .verticalScroll(scrollState)
        ) {
            modules.forEachIndexed { index, module ->
                if (index > 0) HorizontalDivider()
                val selected = module.id == selectedModuleId
                StreamingMethodChoiceRow(
                    description = module.description,
                    selected = selected,
                    selectionEnabled = selectionEnabled,
                    onSelect = {
                        onMethodSelect(module.id)
                        onDismiss()
                    },
                    onShowDetails = {
                        // Focusing a list item on return could move the restored scroll position.
                        focusSelectionOnEntry = false
                        detailsModuleId = module.id
                    },
                    modifier = if (focusSelectedMethod && selected) Modifier.focusRequester(focusRequester) else Modifier,
                )
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
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(description.titleRes)
    val detailsContentDescription = stringResource(R.string.app_method_details_description, title)
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(description.summaryRes), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(description.conditionsRes),
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
                    onClick = onShowDetails,
                    modifier = Modifier
                        .widthIn(min = 64.dp, max = 112.dp)
                        .semantics { contentDescription = detailsContentDescription },
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
        for (item in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "\u2022", style = MaterialTheme.typography.bodyMedium)
                Text(text = item, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
