package info.dvkr.screenstream.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
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
            .verticalScroll(scrollState),
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
    ListItem(
        selected = selected,
        onClick = onSelect,
        shapes = ListItemShapes(
            shape = RectangleShape,
            selectedShape = RectangleShape,
            pressedShape = RectangleShape,
            focusedShape = RectangleShape,
            hoveredShape = RectangleShape,
            draggedShape = RectangleShape,
        ),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(description.iconRes), contentDescription = null) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(description.summaryRes))
                Text(
                    text = stringResource(description.conditionsRes),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            TextButton(
                onClick = onShowDetails,
                modifier = Modifier.semantics { contentDescription = detailsContentDescription },
            ) {
                Icon(
                    painter = painterResource(R.drawable.app_icon_info),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(text = stringResource(R.string.app_method_details))
            }
        },
        content = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
    )
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
