package info.dvkr.screenstream.ui.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.AdaptiveAppModal
import info.dvkr.screenstream.ui.AppDialog
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule

@Composable
internal fun PrimaryModulePane(
    streamingModuleManager: StreamingModuleManager,
    streamingModuleManagerState: StreamingModuleManager.State,
    streamingModuleUi: StreamingModule.Ui?,
    streamingModuleUiSections: List<StreamingModule.Ui.Section>,
    scrollState: ScrollState,
    dialogBounds: DpRect?,
    showSettings: Boolean,
    shortHeader: (@Composable () -> Unit)?,
    fixedHeaderModifier: Modifier,
    modifier: Modifier,
) {
    val instanceId = streamingModuleManagerState.instanceId
    Column(modifier.clipToBounds()) {
        if (streamingModuleUi == null && shortHeader != null) {
            Box(fixedHeaderModifier) {
                shortHeader()
            }
        }

        when {
            instanceId == null ->
                if (streamingModuleManagerState == StreamingModuleManager.State.Opening) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.app_stream_switching_modes))
                    }
                }

            streamingModuleUi == null -> ModuleUnavailableContent(
                onRestart = { streamingModuleManager.restartModule(instanceId) },
                message = (streamingModuleManagerState as? StreamingModuleManager.State.Failed)?.messageResource?.let { stringResource(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            else -> key(instanceId) {
                val streamError = (streamingModuleManagerState as? StreamingModuleManager.State.Running)?.status?.error
                val scrollbar = scrollState.scrollIndicatorState?.let { indicator ->
                    Modifier.nonInteractiveScrollbar(
                        state = indicator,
                        orientation = Orientation.Vertical,
                        thumbMaxLengthFraction = 1f,
                        crossAxisTrackInset = 4.dp,
                    )
                } ?: Modifier

                val contentPadding = remember {
                    object : PaddingValues by PaddingValues(0.dp) {
                        var bottom by mutableStateOf(0.dp)
                        override fun calculateBottomPadding() = bottom
                    }
                }
                val background = MaterialTheme.colorScheme.background
                StreamingModule.Ui.ModalHost.Provide(PaneModalHost(dialogBounds)) {
                    Layout(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(scrollbar),
                        content = {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(contentPadding),
                            ) {
                                shortHeader?.invoke()
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 12.dp,
                                            top = if (shortHeader != null) 12.dp else 4.dp,
                                            end = 12.dp,
                                            bottom = 4.dp,
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (streamingModuleManagerState is StreamingModuleManager.State.Unresponsive || streamError != null) {
                                        ModuleRecoveryCard(
                                            { streamingModuleManager.restartModule(instanceId) },
                                            streamError?.message,
                                        )
                                    }
                                    streamingModuleUiSections.forEach {
                                        if (showSettings || it.role == StreamingModule.Ui.Section.Role.Overview) it.content()
                                    }
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(listOf(background.copy(alpha = 0f), background.copy(alpha = 0.9f)))
                                    )
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                streamingModuleUi.CaptureActionButton(Modifier.padding(horizontal = 12.dp))
                            }
                        },
                    ) { (scrollContent, actionContent), constraints ->
                        val action = actionContent.measure(constraints.copy(minHeight = 0))
                        // Include the overlay in the scroll range from the first measure.
                        contentPadding.bottom = action.height.toDp()
                        val scroll = scrollContent.measure(constraints)
                        layout(scroll.width, scroll.height) {
                            scroll.place(0, 0)
                            action.place(0, scroll.height - action.height)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModuleSettingsPane(
    streamingModuleUiSections: List<StreamingModule.Ui.Section>,
    scrollState: ScrollState,
    dialogBounds: DpRect?,
    modifier: Modifier,
) {
    val scrollbar = scrollState.scrollIndicatorState?.let { indicator ->
        Modifier.nonInteractiveScrollbar(
            state = indicator,
            orientation = Orientation.Vertical,
            thumbMaxLengthFraction = 1f,
            crossAxisTrackInset = 4.dp,
        )
    } ?: Modifier

    StreamingModule.Ui.ModalHost.Provide(PaneModalHost(dialogBounds)) {
        Column(
            modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            streamingModuleUiSections.forEach { if (it.role == StreamingModule.Ui.Section.Role.SettingsSearch) it.content() }
            Column(
                Modifier
                    .fillMaxSize()
                    .then(scrollbar)
                    .verticalScroll(scrollState)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                streamingModuleUiSections.forEach { if (it.role == StreamingModule.Ui.Section.Role.Settings) it.content() }
            }
        }
    }
}

private data class PaneModalHost(val dialogBounds: DpRect?) : StreamingModule.Ui.ModalHost {
    @Composable
    override fun Modal(title: String, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        AdaptiveAppModal(title = title, onDismissRequest = onDismissRequest, dialogBounds = dialogBounds, content = content)
    }

    @Composable
    override fun ContentDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        AppDialog(title = null, onDismissRequest = onDismissRequest, dialogBounds = dialogBounds, content = content)
    }
}
