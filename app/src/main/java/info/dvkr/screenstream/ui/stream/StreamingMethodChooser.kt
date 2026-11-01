package info.dvkr.screenstream.ui.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import io.screenstream.core.module.StreamingModule
import kotlinx.coroutines.launch

@Composable
internal fun StreamingMethodCard(
    selectedMethod: StreamingModule.Description?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.elevatedCardElevation(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedMethod != null) {
                Icon(
                    painter = painterResource(selectedMethod.iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1F), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedMethod != null) {
                    Text(
                        text = stringResource(R.string.app_mode_selected_method),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = if (selectedMethod == null) {
                        stringResource(R.string.app_mode_choose_method)
                    } else {
                        stringResource(selectedMethod.titleRes)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selectedMethod == null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (selectedMethod != null) {
                    Text(
                        text = stringResource(R.string.app_mode_choose_another_method),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.arrow_drop_down_24px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun StreamingMethodChooser(
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    selectionEnabled: Boolean,
    onMethodSelect: (StreamingModule.Id) -> Unit,
    onDismiss: () -> Unit,
) {
    var detailsModuleId by rememberSaveable { mutableStateOf<String?>(null) }
    val detailsModule = modules.firstOrNull { it.id.value == detailsModuleId }
    val chooserScrollState = rememberScrollState()
    val detailsScrollState = key(detailsModuleId) { rememberScrollState() }
    val returnToChooser = { detailsModuleId = null }
    val onDismissRequest = {
        if (detailsModule == null) {
            onDismiss()
        } else {
            returnToChooser()
        }
    }

    val content: @Composable () -> Unit = {
        BackHandler(enabled = detailsModule != null, onBack = returnToChooser)
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(detailsModule?.description?.titleRes ?: R.string.app_mode_choose_method),
                    modifier = Modifier
                        .weight(1F)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.close_24px),
                        contentDescription = stringResource(R.string.app_mode_close_chooser),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(if (detailsModule == null) chooserScrollState else detailsScrollState),
            ) {
                if (detailsModule == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                    ) {
                        modules.forEachIndexed { index, module ->
                            if (index > 0) HorizontalDivider()
                            StreamingMethodChoice(
                                description = module.description,
                                selected = module.id == selectedModuleId,
                                selectionEnabled = selectionEnabled,
                                onSelect = {
                                    onMethodSelect(module.id)
                                    onDismiss()
                                },
                                onDetails = { detailsModuleId = module.id.value },
                            )
                        }
                    }
                } else {
                    StreamingMethodDetails(description = detailsModule.description)
                }
            }
        }
    }

    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (windowWidth < WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp) {
        StreamingMethodBottomSheet(
            isShowingDetails = detailsModule != null,
            onDismissRequest = onDismissRequest,
            content = content,
        )
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Box(modifier = Modifier.padding(bottom = if (detailsModule == null) 16.dp else 0.dp)) {
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamingMethodBottomSheet(
    isShowingDetails: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = {
            onDismissRequest()
            // ModalBottomSheet hides before dismissal; Details returns to the open chooser.
            if (isShowingDetails) {
                scope.launch { sheetState.show() }
            }
        },
        dragHandle = null,
        sheetMaxWidth = 480.dp,
        sheetState = sheetState,
    ) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            content()
        }
    }
}

@Composable
private fun StreamingMethodChoice(
    description: StreamingModule.Description,
    selected: Boolean,
    selectionEnabled: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    val title = stringResource(description.titleRes)
    val detailsDescription = stringResource(R.string.app_mode_details_description, title)
    Row(
        modifier = Modifier
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
                    Text(text = stringResource(R.string.app_mode_details))
                }
            }
        }
    }
}

@Composable
private fun StreamingMethodDetails(
    description: StreamingModule.Description,
) {
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
            title = stringResource(R.string.app_mode_benefits),
            items = stringArrayResource(description.benefitsRes),
        )
        StreamingMethodDetailsSection(
            title = stringResource(R.string.app_mode_things_to_know),
            items = stringArrayResource(description.thingsToKnowRes),
        )
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
