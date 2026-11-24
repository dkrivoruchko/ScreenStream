package info.dvkr.screenstream.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AnchoredAdaptiveBanner
import info.dvkr.screenstream.ads.rememberAnchoredAdaptiveBannerState
import info.dvkr.screenstream.ui.stream.ModuleRecoveryCard
import info.dvkr.screenstream.ui.stream.ModuleUnavailableContent
import info.dvkr.screenstream.ui.stream.StreamHeader
import info.dvkr.screenstream.ui.stream.instanceIdOrNull
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.core.module.StreamingModule.Ui.Section.Role
import io.screenstream.foundation.logging.Logger
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun StreamScreen(streamingModulesManager: StreamingModuleManager) {
    val state = streamingModulesManager.state.collectAsStateWithLifecycle().value
    val isExiting = state == StreamingModuleManager.State.Exiting
    BackHandler(enabled = isExiting) {}

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        if (isExiting) return@Surface

        // Keep the header and banner mounted when controller content is replaced.
        val renderLayout = remember(streamingModulesManager) {
            movableContentOf<StreamingModuleManager.State, StreamingModule.Ui?> { moduleState, moduleUi ->
                AdaptiveStreamLayout(streamingModulesManager, moduleState, moduleUi)
            }
        }
        val instanceId = state.instanceIdOrNull
        if (instanceId == null) {
            renderLayout(state, null)
        } else {
            // Compose the controller before the adaptive layout subcomposes.
            streamingModulesManager.InstanceContent(instanceId, fallback = { renderLayout(state, null) }) { moduleUi ->
                renderLayout(state, moduleUi)
            }
        }
    }
}

@Composable
private fun AdaptiveStreamLayout(
    streamingModulesManager: StreamingModuleManager,
    state: StreamingModuleManager.State,
    moduleUi: StreamingModule.Ui?,
) {
    val instanceId = state.instanceIdOrNull
    val sections = key(instanceId) {
        moduleUi?.sections.orEmpty().map { section ->
            key(section.id) {
                val movableSectionContent = remember { movableContentOf<@Composable () -> Unit> { it() } }
                section.copy(content = { movableSectionContent(section.content) })
            }
        }
    }
    val (combinedScrollState, overviewScrollState, settingsScrollState) = key(instanceId) {
        Triple(rememberScrollState(), rememberScrollState(), rememberScrollState())
    }

    val windowInfo = currentWindowAdaptiveInfoV2()
    val hinges = windowInfo.windowPosture.hingeList
    if (hinges.size > 1) {
        val context = LocalContext.current
        val log: Logger = koinInject(parametersHolder = remember(context) { parametersOf(context) })
        LaunchedEffect(Unit) {
            log.i(
                "AdaptiveWindow",
                "System folds (window px): ${hinges.joinToString()}. Layout considers the first eligible fold.",
                IllegalStateException("Multiple folds reported by the window system"),
            )
        }
    }
    val density = LocalDensity.current
    var viewportOriginPx by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .fillMaxSize()
            .onGloballyPositioned { viewportOriginPx = it.positionInWindow() },
        contentAlignment = AbsoluteAlignment.TopLeft,
    ) {
        val panes = density.calculateWindowPanes(DpSize(maxWidth, maxHeight), viewportOriginPx, hinges, windowInfo.windowSizeClass)
        val primaryBounds = panes.primary
        val lowerBounds = panes.lower
        val sideBounds = panes.side
        val primaryModalPane = panes.modalPane(primaryBounds)
        val contentModalPane = lowerBounds?.let { ModalPane(it, imeFallbackBounds = primaryBounds) } ?: primaryModalPane
        val bannerState = rememberAnchoredAdaptiveBannerState(primaryBounds.width)
        val bannerHeight = bannerState?.reservedHeight ?: 0.dp
        val minimumControlsHeight = if (lowerBounds == null) 96.dp else 48.dp
        val upperBannerHeight = if (primaryBounds.height >= bannerHeight + minimumControlsHeight) bannerHeight else 0.dp
        val lowerBannerHeight = if (upperBannerHeight == 0.dp && lowerBounds != null && lowerBounds.height >= bannerHeight + 48.dp) {
            bannerHeight
        } else 0.dp
        val availableHeaderHeight = primaryBounds.height - upperBannerHeight
        // Leave a usable scrolling body even when a tall header must scroll on its own.
        val maxHeaderHeight = if (lowerBounds == null) {
            availableHeaderHeight - minOf(72.dp, availableHeaderHeight / 2)
        } else availableHeaderHeight
        val primaryColumnBounds = if (lowerBounds == null) primaryBounds else primaryBounds.copy(bottom = lowerBounds.bottom)

        Column(Modifier.inPane(primaryColumnBounds)) {
            Column(if (lowerBounds == null) Modifier else Modifier.height(lowerBounds.top + lowerBannerHeight)) {
                StreamHeader(
                    state = state,
                    manager = streamingModulesManager,
                    inlineMethodSelector = primaryBounds.width >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp,
                    primaryModalPane = primaryModalPane,
                    contentModalPane = contentModalPane,
                    reduceVerticalSpacing = maxHeaderHeight < 68.dp,
                    modifier = Modifier
                        .heightIn(max = maxHeaderHeight)
                        .verticalScroll(rememberScrollState()),
                )
                if (lowerBannerHeight > 0.dp) Spacer(Modifier.weight(1f))
                if (bannerState != null && (upperBannerHeight > 0.dp || lowerBannerHeight > 0.dp)) {
                    AnchoredAdaptiveBanner(bannerState)
                }
            }

            when {
                instanceId == null -> if (state == StreamingModuleManager.State.Switching) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(), contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.app_stream_switching_modes))
                    }
                }

                moduleUi == null -> ModuleUnavailableContent(
                    onRestart = { streamingModulesManager.restartModule(instanceId) },
                    message = (state as? StreamingModuleManager.State.Failed)?.messageResource?.let { stringResource(it) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )

                else -> key(instanceId) {
                    val streamError = (state as? StreamingModuleManager.State.Running)?.status?.error
                    val scrollState = if (sideBounds == null) combinedScrollState else overviewScrollState
                    var captureActionHeightPx by remember { mutableIntStateOf(0) }
                    ModalHost.Provide(PaneModalHost(contentModalPane)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            val captureActionHeight = with(density) { captureActionHeightPx.toDp() }
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp + captureActionHeight),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state is StreamingModuleManager.State.Unresponsive || streamError != null) {
                                    ModuleRecoveryCard(
                                        { streamingModulesManager.restartModule(instanceId) },
                                        streamError?.message,
                                    )
                                }
                                sections.forEach { if (sideBounds == null || it.role == Role.Overview) it.content() }
                            }
                            moduleUi.CaptureActionButton(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onSizeChanged { captureActionHeightPx = it.height }
                                    .padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        if (sideBounds != null && moduleUi != null) ModalHost.Provide(PaneModalHost(panes.modalPane(sideBounds))) {
            Column(
                Modifier
                    .inPane(sideBounds)
                    .padding(start = 12.dp, top = 4.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { if (it.role == Role.SettingsSearch) it.content() }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(settingsScrollState)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { if (it.role == Role.Settings) it.content() }
                }
            }
        }

        panes.dividerX?.let { dividerX ->
            VerticalDivider(Modifier.absoluteOffset(x = dividerX))
        }
    }
}

private data class PaneModalHost(val pane: ModalPane?) : ModalHost {
    @Composable
    override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        if (title == null) {
            AppDialog(onDismiss = onDismissRequest, pane = pane, content = content)
        } else {
            AdaptiveAppModal(title = title, onDismiss = onDismissRequest, pane = pane) {
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}
