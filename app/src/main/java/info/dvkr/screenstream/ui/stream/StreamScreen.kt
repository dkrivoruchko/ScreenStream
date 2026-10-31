package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.app.AnchoredAdaptiveBanner
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import org.koin.compose.koinInject

@Composable
internal fun StreamScreen( //TODO Add foldable support
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    streamingModulesManager: StreamingModuleManager = koinInject()
) {
    val managerState = streamingModulesManager.state.collectAsStateWithLifecycle().value
    val selectedModuleId = when (managerState) {
        is StreamingModuleManager.State.Running -> managerState.instanceId.moduleId
        is StreamingModuleManager.State.Unresponsive -> managerState.instanceId.moduleId
        is StreamingModuleManager.State.Failed -> managerState.instanceId.moduleId
        StreamingModuleManager.State.NoModule,
        StreamingModuleManager.State.Switching,
        StreamingModuleManager.State.Exiting -> null
    }
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val selectorExpanded = rememberSaveable {
        mutableStateOf(windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND))
    }

    Column(modifier = modifier) {
        StreamHeader(
            boundsInWindow = boundsInWindow,
            modules = streamingModulesManager.modules,
            selectedModuleId = selectedModuleId,
            enabled = managerState != StreamingModuleManager.State.Switching,
            expanded = selectorExpanded.value,
            onExpandedChange = { selectorExpanded.value = it },
            onModuleSelect = streamingModulesManager::selectModule,
        )

        ModuleContent(
            state = managerState,
            onRestart = streamingModulesManager::restartModule,
            instanceContent = { instanceId, contentModifier, fallback ->
                streamingModulesManager.InstanceContent(instanceId, windowSizeClass, contentModifier, fallback)
            },
        )
    }
}

@Composable
private fun StreamHeader(
    boundsInWindow: Rect,
    modules: List<StreamingModule>,
    selectedModuleId: StreamingModule.Id?,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModuleSelect: (StreamingModule.Id) -> Unit,
) {
    val width = with(LocalDensity.current) { boundsInWindow.width.toDp() }
    if (width >= 800.dp) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1F), verticalArrangement = Arrangement.Center) {
                ModuleSelector(
                    modules = modules,
                    selectedModuleId = selectedModuleId,
                    onModuleSelect = onModuleSelect,
                    enabled = enabled,
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
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
            ModuleSelector(
                modules = modules,
                selectedModuleId = selectedModuleId,
                onModuleSelect = onModuleSelect,
                enabled = enabled,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
            )
        }
    }
}
