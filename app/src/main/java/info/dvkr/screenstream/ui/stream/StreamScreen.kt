package info.dvkr.screenstream.ui.stream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val modules = streamingModulesManager.modules
    val selectedMethod = selectedModuleId?.let { id -> modules.first { it.id == id }.description }
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val isMethodSelectionEnabled = managerState != StreamingModuleManager.State.Switching
    var isChooserVisible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier) {
        StreamHeader(
            boundsInWindow = boundsInWindow,
            selectedMethod = selectedMethod,
            enabled = isMethodSelectionEnabled,
            onChooserOpen = { isChooserVisible = true },
        )

        ModuleContent(
            state = managerState,
            onRestart = streamingModulesManager::restartModule,
            instanceContent = { instanceId, contentModifier, fallback ->
                streamingModulesManager.InstanceContent(instanceId, windowSizeClass, contentModifier, fallback)
            },
        )
    }

    if (isChooserVisible) {
        StreamingMethodChooser(
            modules = modules,
            selectedModuleId = selectedModuleId,
            selectionEnabled = isMethodSelectionEnabled,
            onMethodSelect = streamingModulesManager::selectModule,
            onDismiss = { isChooserVisible = false },
        )
    }
}

@Composable
private fun StreamHeader(
    boundsInWindow: Rect,
    selectedMethod: StreamingModule.Description?,
    enabled: Boolean,
    onChooserOpen: () -> Unit,
) {
    val width = with(LocalDensity.current) { boundsInWindow.width.toDp() }
    if (width >= 800.dp) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StreamingMethodCard(
                selectedMethod = selectedMethod,
                onClick = onChooserOpen,
                enabled = enabled,
                modifier = Modifier
                    .weight(1F)
                    .padding(top = 8.dp, start = 12.dp, end = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
            )
            Column(modifier = Modifier.weight(1F)) {
                AnchoredAdaptiveBanner(modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            AnchoredAdaptiveBanner(modifier = Modifier.fillMaxWidth())
            StreamingMethodCard(
                selectedMethod = selectedMethod,
                onClick = onChooserOpen,
                enabled = enabled,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth()
            )
        }
    }
}
