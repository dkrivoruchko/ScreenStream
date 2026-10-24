package info.dvkr.screenstream.ui.tabs.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.app.AnchoredAdaptiveBanner
import info.dvkr.screenstream.common.ui.ExpandableCard
import io.screenstream.streaming.StreamingModuleManager
import io.screenstream.streaming.module.StreamingModule
import org.koin.compose.koinInject

@Composable
internal fun StreamTabContent( //TODO Add foldable support
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    streamingModulesManager: StreamingModuleManager = koinInject()
) {
    val moduleState = streamingModulesManager.state.collectAsStateWithLifecycle().value
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val selectorExpanded = rememberSaveable {
        mutableStateOf(windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND))
    }

    Column(modifier = modifier) {
        val width = with(LocalDensity.current) { boundsInWindow.width.toDp() }
        if (width >= 800.dp) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1F), verticalArrangement = Arrangement.Center) {
                    StreamingModuleSelector(
                        streamingModulesManager = streamingModulesManager,
                        enabled = moduleState != StreamingModuleManager.State.Switching,
                        expanded = selectorExpanded.value,
                        onExpandedChange = { selectorExpanded.value = it },
                        modifier = Modifier
                            .padding(top = 8.dp, start = 12.dp, end = 8.dp, bottom = 8.dp)
                            .fillMaxWidth()
                    )
                }
                Column(modifier = Modifier.weight(1F)) {
                    AnchoredAdaptiveBanner(modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnchoredAdaptiveBanner(modifier = Modifier.fillMaxWidth())
                StreamingModuleSelector(
                    streamingModulesManager = streamingModulesManager,
                    enabled = moduleState != StreamingModuleManager.State.Switching,
                    expanded = selectorExpanded.value,
                    onExpandedChange = { selectorExpanded.value = it },
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                )
            }
        }

        when (val state = moduleState) {
            is StreamingModuleManager.State.Running -> key(state.instanceId) {
                if (state.status.errorMessage != null) {
                    ModuleRecoveryMessage(
                        detail = state.status.errorMessage,
                        onRestart = { streamingModulesManager.restartModule(state.instanceId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    )
                }
                streamingModulesManager.InstanceContent(state.instanceId, windowSizeClass, Modifier.fillMaxSize()) {
                    ModuleError(onRestart = { streamingModulesManager.restartModule(state.instanceId) })
                }
            }

            is StreamingModuleManager.State.Failed -> key(state.instanceId) {
                ModuleError(
                    onRestart = { streamingModulesManager.restartModule(state.instanceId) },
                )
            }

            is StreamingModuleManager.State.Unresponsive -> key(state.instanceId) {
                ModuleRecoveryMessage(
                    onRestart = { streamingModulesManager.restartModule(state.instanceId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                streamingModulesManager.InstanceContent(state.instanceId, windowSizeClass, Modifier.fillMaxSize()) {
                    ModuleError(onRestart = { streamingModulesManager.restartModule(state.instanceId) })
                }
            }

            StreamingModuleManager.State.Switching -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1F),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(R.string.app_tab_stream_switching_modules))
            }

            StreamingModuleManager.State.NoModule, StreamingModuleManager.State.Exiting -> Unit
        }
    }
}

@Composable
private fun ModuleRecoveryMessage(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val title = stringResource(R.string.app_module_error_title)
    val message = detail?.takeUnless { it.isBlank() || it == title }

    ElevatedCard(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.app_icon_error),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onRestart,
                modifier = Modifier.widthIn(max = 144.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = stringResource(R.string.app_module_error_restart))
            }
        }
    }
}

@Composable
private fun ModuleError(
    onRestart: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.app_icon_error),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.app_module_error_title),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRestart, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(text = stringResource(R.string.app_module_error_restart))
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StreamingModuleSelector(
    streamingModulesManager: StreamingModuleManager,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedModuleId = streamingModulesManager.currentInstanceId.collectAsStateWithLifecycle().value?.moduleId

    ExpandableCard(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        headerContent = {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.app_tab_stream_select_mode),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        modifier = modifier,
        contentModifier = Modifier.selectableGroup(),
    ) {
        streamingModulesManager.modules.forEach { module ->
            ModuleSelectorRow(
                module = module,
                selectedModuleId = selectedModuleId,
                enabled = enabled,
                onModuleSelect = streamingModulesManager::selectModule,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ModuleSelectorRow(
    module: StreamingModule,
    selectedModuleId: StreamingModule.Id?,
    enabled: Boolean,
    onModuleSelect: (StreamingModule.Id) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectable(
            selected = module.id == selectedModuleId,
            enabled = enabled,
            onClick = { onModuleSelect.invoke(module.id) },
            role = Role.RadioButton
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val openDescriptionDialog = rememberSaveable { mutableStateOf(false) }

        RadioButton(selected = module.id == selectedModuleId, onClick = null, enabled = enabled, modifier = Modifier.padding(start = 8.dp))

        Text(
            text = stringResource(id = module.nameResource),
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1F),
            style = MaterialTheme.typography.titleMedium
        )

        IconButton(onClick = { openDescriptionDialog.value = true }) {
            Icon(
                painter = painterResource(R.drawable.help_24px),
                contentDescription = stringResource(id = module.descriptionResource),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (openDescriptionDialog.value) {
            AlertDialog(
                onDismissRequest = { openDescriptionDialog.value = false },
                confirmButton = {
                    TextButton(onClick = { openDescriptionDialog.value = false }) {
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
