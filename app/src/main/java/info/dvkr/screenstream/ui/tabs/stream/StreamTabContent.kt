package info.dvkr.screenstream.ui.tabs.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.AdaptiveBanner
import info.dvkr.screenstream.R
import info.dvkr.screenstream.common.module.StreamingModule
import info.dvkr.screenstream.common.module.StreamingModuleManager
import info.dvkr.screenstream.common.settings.AppSettings
import info.dvkr.screenstream.common.ui.ExpandableCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun StreamTabContent( //TODO Add foldable support
    boundsInWindow: Rect,
    modifier: Modifier = Modifier,
    streamingModulesManager: StreamingModuleManager = koinInject()
) {
    val activeModule = streamingModulesManager.activeModuleStateFlow.collectAsStateWithLifecycle()

    Column(modifier = modifier) {
        val with = with(LocalDensity.current) { boundsInWindow.width.toDp() }
        if (with >= 800.dp) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1F), verticalArrangement = Arrangement.Center) {
                    StreamingModuleSelector(
                        streamingModulesManager = streamingModulesManager,
                        modifier = Modifier
                            .padding(top = 8.dp, start = 16.dp, end = 8.dp, bottom = 8.dp)
                            .fillMaxWidth()
                    )
                }
                Column(modifier = Modifier.weight(1F)) {
                    AdaptiveBanner(modifier = Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                StreamingModuleSelector(
                    streamingModulesManager = streamingModulesManager,
                    modifier = Modifier
                        .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
                        .fillMaxWidth()
                )
                AdaptiveBanner(modifier = Modifier.fillMaxWidth())
            }
        }
        activeModule.value?.StreamUIContent(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun StreamingModuleSelector(
    streamingModulesManager: StreamingModuleManager,
    modifier: Modifier = Modifier,
    scope: CoroutineScope = rememberCoroutineScope(),
) {
    val selectedModuleId = streamingModulesManager.selectedModuleIdFlow
        .collectAsStateWithLifecycle(initialValue = AppSettings.Default.STREAMING_MODULE)

    val adaptiveInfo = currentWindowAdaptiveInfo()

    ExpandableCard(
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
        initiallyExpanded = adaptiveInfo.windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    ) {
        streamingModulesManager.modules.forEach { module ->
            ModuleSelectorRow(
                module = module,
                selectedModuleId = selectedModuleId.value,
                onModuleSelect = { moduleId -> scope.launch { streamingModulesManager.selectStreamingModule(moduleId) } },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ModuleSelectorRow(
    module: StreamingModule,
    selectedModuleId: StreamingModule.Id,
    onModuleSelect: (StreamingModule.Id) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.selectable(
            selected = module.id == selectedModuleId,
            onClick = { onModuleSelect.invoke(module.id) },
            role = Role.RadioButton
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val openDescriptionDialog = rememberSaveable { mutableStateOf(false) }

        RadioButton(selected = module.id == selectedModuleId, onClick = null, modifier = Modifier.padding(start = 8.dp))

        Text(
            text = stringResource(id = module.nameResource),
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1F),
            style = MaterialTheme.typography.titleMedium
        )

        IconButton(onClick = { openDescriptionDialog.value = true }) {
            Icon(
                imageVector = Icon_HelpOutline,
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

private val Icon_HelpOutline: ImageVector = materialIcon(name = "AutoMirrored.Outlined.HelpOutline", autoMirror = true) {
    materialPath {
        moveTo(11.0f, 18.0f)
        horizontalLineToRelative(2.0f)
        verticalLineToRelative(-2.0f)
        horizontalLineToRelative(-2.0f)
        verticalLineToRelative(2.0f)
        close()
        moveTo(12.0f, 2.0f)
        curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
        reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
        reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
        reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
        close()
        moveTo(12.0f, 20.0f)
        curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
        reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
        reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
        reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
        close()
        moveTo(12.0f, 6.0f)
        curveToRelative(-2.21f, 0.0f, -4.0f, 1.79f, -4.0f, 4.0f)
        horizontalLineToRelative(2.0f)
        curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
        reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
        curveToRelative(0.0f, 2.0f, -3.0f, 1.75f, -3.0f, 5.0f)
        horizontalLineToRelative(2.0f)
        curveToRelative(0.0f, -2.25f, 3.0f, -2.5f, 3.0f, -5.0f)
        curveToRelative(0.0f, -2.21f, -1.79f, -4.0f, -4.0f, -4.0f)
        close()
    }
}