package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.ui.ExpandableCard
import io.screenstream.core.module.StreamingModule

@Composable
internal fun ModuleSelector(
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModuleSelect: (StreamingModule.Id) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        headerContent = {
            Text(
                text = stringResource(id = R.string.app_tab_stream_select_mode),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        modifier = modifier,
        contentModifier = Modifier.selectableGroup(),
    ) {
        modules.forEach { module ->
            ModuleSelectorRow(
                module = module,
                selected = module.id == selectedModuleId,
                enabled = enabled,
                onModuleSelect = onModuleSelect,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ModuleSelectorRow(
    module: StreamingModule,
    selected: Boolean,
    enabled: Boolean,
    onModuleSelect: (StreamingModule.Id) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectable(
            selected = selected,
            enabled = enabled,
            onClick = { onModuleSelect.invoke(module.id) },
            role = Role.RadioButton
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        var isDescriptionDialogVisible by rememberSaveable { mutableStateOf(false) }

        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(start = 8.dp))

        Text(
            text = stringResource(id = module.nameResource),
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1F),
            style = MaterialTheme.typography.titleMedium
        )

        IconButton(onClick = { isDescriptionDialogVisible = true }) {
            Icon(
                painter = painterResource(R.drawable.help_24px),
                contentDescription = stringResource(id = module.descriptionResource),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (isDescriptionDialogVisible) {
            AlertDialog(
                onDismissRequest = { isDescriptionDialogVisible = false },
                confirmButton = {
                    TextButton(onClick = { isDescriptionDialogVisible = false }) {
                        Text(text = stringResource(id = android.R.string.ok))
                    }
                },
                title = {
                    Text(
                        text = stringResource(id = module.nameResource),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = stringResource(id = module.detailsResource),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                shape = MaterialTheme.shapes.large
            )
        }
    }
}
