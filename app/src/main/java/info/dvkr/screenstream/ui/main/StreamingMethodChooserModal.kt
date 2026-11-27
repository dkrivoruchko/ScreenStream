package info.dvkr.screenstream.ui.main

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AdaptiveAppModal
import io.screenstream.core.module.StreamingModule

@Composable
internal fun StreamingMethodChooserModal(
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    onMethodSelect: (StreamingModule.Id) -> Unit,
    onDismissRequest: () -> Unit,
    dialogBounds: DpRect?,
) {
    var viewedModuleId by retain { mutableStateOf<StreamingModule.Id?>(null) }
    val chooserScrollState = retain { ScrollState(0) }
    val viewedModuleDescription = viewedModuleId?.let { id -> modules.firstOrNull { it.id == id }?.description }
    val scrollState = if (viewedModuleDescription == null) {
        chooserScrollState
    } else {
        retain(viewedModuleId) { ScrollState(0) }
    }

    AdaptiveAppModal(
        title = stringResource(viewedModuleDescription?.titleRes ?: R.string.app_method_choose),
        onDismissRequest = onDismissRequest,
        onBack = if (viewedModuleDescription == null) null else ({ viewedModuleId = null }),
        dialogBounds = dialogBounds,
    ) {
        if (viewedModuleDescription == null) {
            StreamingMethodList(
                modules = modules,
                selectedModuleId = selectedModuleId,
                scrollState = scrollState,
                onMethodSelect = onMethodSelect,
                onShowDetails = { viewedModuleId = it },
            )
        } else {
            StreamingMethodDetails(description = viewedModuleDescription, scrollState = scrollState)
        }
    }
}

@Composable
private fun StreamingMethodList(
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    scrollState: ScrollState,
    onMethodSelect: (StreamingModule.Id) -> Unit,
    onShowDetails: (StreamingModule.Id) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .verticalScroll(scrollState)
    ) {
        modules.forEachIndexed { index, module ->
            if (index > 0) HorizontalDivider()
            StreamingMethodChoiceRow(
                description = module.description,
                selected = module.id == selectedModuleId,
                onSelect = { onMethodSelect(module.id) },
                onShowDetails = { onShowDetails(module.id) },
            )
        }
    }
}

@Composable
private fun StreamingMethodChoiceRow(
    description: StreamingModule.Description,
    selected: Boolean,
    onSelect: () -> Unit,
    onShowDetails: () -> Unit,
) {
    val title = stringResource(description.titleRes)
    val detailsContentDescription = stringResource(R.string.app_method_details_description, title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
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
private fun StreamingMethodDetails(description: StreamingModule.Description, scrollState: ScrollState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
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
