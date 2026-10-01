package info.dvkr.screenstream.ui.tabs.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.app.AnchoredAdaptiveBanner
import info.dvkr.screenstream.R
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
    val moduleState = streamingModulesManager.state.collectAsStateWithLifecycle()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass

    Column(modifier = modifier) {
        val width = with(LocalDensity.current) { boundsInWindow.width.toDp() }
        if (width >= 800.dp) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1F), verticalArrangement = Arrangement.Center) {
                    StreamingModuleSelector(
                        streamingModulesManager = streamingModulesManager,
                        enabled = moduleState.value != StreamingModuleManager.State.Switching,
                        modifier = Modifier
                            .padding(top = 8.dp, start = 16.dp, end = 8.dp, bottom = 8.dp)
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
                    enabled = moduleState.value != StreamingModuleManager.State.Switching,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .fillMaxWidth()
                )
            }
        }

        //TODO Update UI to properly manage new StreamingModuleManager.States.
        when (val state = moduleState.value) {
            is StreamingModuleManager.State.Running -> key(state.instanceId) {
                streamingModulesManager.InstanceContent(state.instanceId, windowSizeClass, Modifier.fillMaxSize()) {
                    InstanceContentUnavailable()
                }
            }

            is StreamingModuleManager.State.Failed -> key(state.instanceId) {
                streamingModulesManager.InstanceContent(state.instanceId, windowSizeClass, Modifier.fillMaxSize()) {
                    InstanceContentUnavailable()
                }
            }

            is StreamingModuleManager.State.Unresponsive -> key(state.instanceId) {
                streamingModulesManager.InstanceContent(state.instanceId, windowSizeClass, Modifier.fillMaxSize()) {
                    InstanceContentUnavailable()
                }
            }

            StreamingModuleManager.State.Switching -> Box(
                modifier = Modifier.fillMaxWidth().weight(1F),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = stringResource(R.string.app_tab_stream_switching_modules))
            }

            StreamingModuleManager.State.NoModule, StreamingModuleManager.State.Exiting -> Unit
        }
    }
}

@Composable
private fun InstanceContentUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = stringResource(R.string.app_error_title))
    }
}

//TODO Update UI to properly manage new StreamingModuleManager.States.
@Composable
private fun StreamingModuleSelector(
    streamingModulesManager: StreamingModuleManager,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val selectedModuleId = streamingModulesManager.currentInstanceId.collectAsStateWithLifecycle().value?.moduleId

    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    val expanded = rememberSaveable {
        mutableStateOf(adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND))
    }

    ExpandableCard(
        expanded = expanded.value,
        onExpandedChange = { expanded.value = it },
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
