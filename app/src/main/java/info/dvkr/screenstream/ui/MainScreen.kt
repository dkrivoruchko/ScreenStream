package info.dvkr.screenstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import info.dvkr.screenstream.ui.stream.StreamBody
import info.dvkr.screenstream.ui.stream.StreamHeader
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.core.module.StreamingModule.Ui.Section.Role

@Composable
internal fun MainScreen(
    streamingModulesManager: StreamingModuleManager,
    modifier: Modifier = Modifier,
) {
    val managerState = streamingModulesManager.state.collectAsStateWithLifecycle().value
    val isExiting = managerState == StreamingModuleManager.State.Exiting
    BackHandler(enabled = isExiting) {}
    if (isExiting) return

    AdaptiveWindow(modifier) { panes ->
        Scaffold(
            modifier = Modifier.inWindowViewport(panes),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                StreamHeader(
                    state = managerState,
                    manager = streamingModulesManager,
                    isTabletop = panes.isTabletop,
                    primaryModalPane = panes.headerModalPane,
                    tabletopModalPane = panes.body.modalPane.takeIf { panes.isTabletop },
                    modifier = Modifier
                        .width(panes.headerBounds.width)
                        .heightIn(max = panes.headerBounds.height),
                )
            },
        ) { contentPadding ->
            panes.dividerBounds?.let { VerticalDivider(Modifier.inPaneBounds(it)) }

            val body = panes.body
            val bodyPlacement = if (body.sharesHeaderPane) body.copy(modifier = body.modifier.padding(contentPadding)) else body
            StreamBody(
                state = managerState,
                manager = streamingModulesManager,
                statusModifier = bodyPlacement.modifier,
            ) { module, recoveryNotice ->
                StreamPanes(bodyPlacement, panes.side, module, recoveryNotice)
            }
        }
    }
}

@Composable
private fun StreamPanes(
    body: ContentPane,
    side: ContentPane?,
    module: StreamingModule.Ui,
    recoveryNotice: @Composable () -> Unit,
) {
    val headerPaneScroll = rememberScrollState()
    val otherPaneScroll = rememberScrollState()
    val moduleSections = module.sections
    val sections = moduleSections.map { section ->
        key(section.id) {
            val movableContent = remember { movableContentOf<@Composable () -> Unit> { it() } }
            val render: @Composable () -> Unit = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)
                ) {
                    movableContent(section.content)
                }
            }
            render
        }
    }
    val sideStart = if (side == null) sections.size else {
        moduleSections.indexOfFirst { it.role != Role.Overview }.takeIf { it >= 0 } ?: sections.size
    }

    Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        StreamPane(
            pane = body,
            sections = sections.subList(0, sideStart),
            scroll = if (body.sharesHeaderPane) headerPaneScroll else otherPaneScroll,
            capture = { module.CaptureActionButton(it) },
            recoveryNotice = recoveryNotice,
        )
        if (side != null) {
            val pinSearch = moduleSections.getOrNull(sideStart)?.role == Role.SettingsSearch
            val firstScrollingSection = if (pinSearch) sideStart + 1 else sideStart
            StreamPane(
                pane = side,
                sections = sections.subList(firstScrollingSection, sections.size),
                scroll = otherPaneScroll,
                pinnedSearch = sections.getOrNull(sideStart).takeIf { pinSearch },
            )
        }
    }
}

@Composable
private fun StreamPane(
    pane: ContentPane,
    sections: List<@Composable () -> Unit>,
    scroll: ScrollState,
    capture: (@Composable (Modifier) -> Unit)? = null,
    recoveryNotice: (@Composable () -> Unit)? = null,
    pinnedSearch: (@Composable () -> Unit)? = null,
) {
    var captureHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val scrollBottomPadding = if (capture != null) maxOf(92.dp, with(density) { captureHeight.toDp() } + 8.dp) else 20.dp
    val host = remember(pane.modalPane) { ModuleModalHost(pane.modalPane) }

    ModalHost.Provide(host) {
        Box(pane.modifier, contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                recoveryNotice?.invoke()
                pinnedSearch?.invoke()
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                ) {
                    for (section in sections) section()
                    Spacer(Modifier.height(scrollBottomPadding))
                }
            }
            if (capture != null) {
                capture(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { captureHeight = it.height }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }
}
