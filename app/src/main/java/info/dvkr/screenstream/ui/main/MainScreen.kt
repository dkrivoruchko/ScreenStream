package info.dvkr.screenstream.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.nonInteractiveScrollbar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AnchoredAdaptiveBanner
import info.dvkr.screenstream.ads.rememberAnchoredAdaptiveBannerState
import info.dvkr.screenstream.ui.modal.AdaptiveAppModal
import info.dvkr.screenstream.ui.modal.AppDialog
import info.dvkr.screenstream.ui.modal.ModalPane
import info.dvkr.screenstream.update.AppUpdate
import info.dvkr.screenstream.update.AppUpdateBar
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.core.module.StreamingModule.Ui.ModalHost
import io.screenstream.core.module.StreamingModule.Ui.Section
import io.screenstream.core.module.StreamingModule.Ui.Section.Role
import io.screenstream.foundation.logging.Logger
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun MainScreen(streamingModulesManager: StreamingModuleManager, appUpdate: AppUpdate) {
    val state = streamingModulesManager.state.collectAsStateWithLifecycle().value
    val isExiting = state == StreamingModuleManager.State.Exiting
    BackHandler(enabled = isExiting) {}

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        if (isExiting) return@Surface

        // Keep the header and banner mounted when controller content is replaced.
        val renderLayout = remember(streamingModulesManager, appUpdate) {
            movableContentOf<StreamingModuleManager.State, StreamingModule.Ui?> { moduleState, moduleUi ->
                AdaptiveMainScreenLayout(streamingModulesManager, appUpdate, moduleState, moduleUi)
            }
        }
        val instanceId = state.instanceId
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
private fun AdaptiveMainScreenLayout(
    streamingModulesManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    state: StreamingModuleManager.State,
    moduleUi: StreamingModule.Ui?,
) {
    val instanceId = state.instanceId
    val sections = key(instanceId) {
        moduleUi?.sections.orEmpty().map { section ->
            val sectionId = section.id
            key(sectionId) {
                val movableSectionContent = remember {
                    movableContentOf<@Composable () -> Unit> { content ->
                        // A movable lambda starts a new positional scope for saved and retained state.
                        key(instanceId, sectionId) { content() }
                    }
                }
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
        val placement = density.calculateMainPanePlacement(
            viewportSizePx = IntSize(constraints.maxWidth, constraints.maxHeight),
            viewportOriginPx = viewportOriginPx,
            hinges = hinges,
            splitFlatWindow = windowInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND),
        )

        // Keep the primary pane at this call site as the layout changes.
        PrimaryStreamPane(
            streamingModulesManager = streamingModulesManager,
            appUpdate = appUpdate,
            state = state,
            moduleUi = moduleUi,
            sections = sections,
            scrollState = if (placement.settings == null) combinedScrollState else overviewScrollState,
            placement = placement,
        )
        val settingsBounds = placement.settings
        if (settingsBounds != null && moduleUi != null) {
            SettingsStreamPane(
                sections = sections,
                scrollState = settingsScrollState,
                pane = if (placement.isFolded) ModalPane(settingsBounds) else null,
                modifier = Modifier.inPane(settingsBounds),
            )
        }
        placement.divider?.let { VerticalDivider(Modifier.inPane(it)) }
    }
}

@Composable
private fun PrimaryStreamPane(
    streamingModulesManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    state: StreamingModuleManager.State,
    moduleUi: StreamingModule.Ui?,
    sections: List<Section>,
    scrollState: ScrollState,
    placement: MainPanePlacement,
) {
    val instanceId = state.instanceId
    val primaryWidth = placement.primary.width
    val primaryHeight = placement.primary.height
    val tabletopGap = placement.tabletopGap
    val primaryModalPane = if (placement.isFolded) ModalPane(placement.primary) else null
    val contentModalPane = placement.lower?.let { ModalPane(it, imeFallbackBounds = placement.primary) } ?: primaryModalPane
    val shortPrimary = primaryHeight < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp
    val scrollHeaderWithBody = shortPrimary && tabletopGap == null && instanceId != null && moduleUi != null
    val bannerState = rememberAnchoredAdaptiveBannerState(primaryWidth)?.takeUnless { shortPrimary }
    val availableHeaderHeight = primaryHeight - (bannerState?.reservedHeight ?: 0.dp)
    val maxHeaderHeight = if (tabletopGap == null) availableHeaderHeight / 2 else availableHeaderHeight

    val movableHeader = remember { movableContentOf<@Composable () -> Unit> { it() } }
    val header: @Composable () -> Unit = {
        Column {
            AppUpdateBar(appUpdate, contentModalPane)
            MainScreenHeader(
                state = state,
                manager = streamingModulesManager,
                inlineMethodSelector = primaryWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp,
                reduceVerticalSpacing = shortPrimary,
                primaryModalPane = primaryModalPane,
                contentModalPane = contentModalPane,
            )
        }
    }

    Column(Modifier.inPane(placement.primaryFrame)) {
        if (!scrollHeaderWithBody) {
            Column(if (tabletopGap == null) Modifier else Modifier.height(primaryHeight)) {
                Box(
                    Modifier
                        .heightIn(max = maxHeaderHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    movableHeader(header)
                }
                if (bannerState != null) AnchoredAdaptiveBanner(bannerState)
            }
        }
        if (tabletopGap != null) {
            Spacer(Modifier.height(tabletopGap))
        }

        when {
            instanceId == null -> if (state == StreamingModuleManager.State.Switching) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
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
                val scrollbar = scrollState.scrollIndicatorState?.let { indicator ->
                    Modifier.nonInteractiveScrollbar(
                        state = indicator,
                        orientation = Orientation.Vertical,
                        thumbMaxLengthFraction = 1f,
                        crossAxisTrackInset = 4.dp,
                    )
                } ?: Modifier
                ModalHost.Provide(PaneModalHost(contentModalPane)) {
                    Scaffold(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(scrollbar),
                        containerColor = Color.Transparent,
                        contentColor = LocalContentColor.current,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            val background = MaterialTheme.colorScheme.background
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(background.copy(alpha = 0f), background.copy(alpha = 0.9f)),
                                        ),
                                    )
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                moduleUi.CaptureActionButton(Modifier.padding(horizontal = 12.dp))
                            }
                        },
                    ) { capturePadding ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(capturePadding),
                        ) {
                            if (scrollHeaderWithBody) movableHeader(header)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 12.dp,
                                        top = if (scrollHeaderWithBody) 12.dp else 4.dp,
                                        end = 12.dp,
                                        bottom = 4.dp,
                                    ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (state is StreamingModuleManager.State.Unresponsive || streamError != null) {
                                    ModuleRecoveryCard(
                                        { streamingModulesManager.restartModule(instanceId) },
                                        streamError?.message,
                                    )
                                }
                                sections.forEach { if (placement.settings == null || it.role == Role.Overview) it.content() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsStreamPane(
    sections: List<Section>,
    scrollState: ScrollState,
    pane: ModalPane?,
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
    ModalHost.Provide(PaneModalHost(pane)) {
        Column(
            modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sections.forEach { if (it.role == Role.SettingsSearch) it.content() }
            Column(
                Modifier
                    .fillMaxSize()
                    .then(scrollbar)
                    .verticalScroll(scrollState)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEach { if (it.role == Role.Settings) it.content() }
            }
        }
    }
}

private data class PaneModalHost(val pane: ModalPane?) : ModalHost {
    @Composable
    override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        if (title == null) {
            AppDialog(onDismiss = onDismissRequest, title = null, pane = pane, content = content)
        } else {
            AdaptiveAppModal(title = title, onDismiss = onDismissRequest, pane = pane) {
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}

private fun Modifier.inPane(bounds: DpRect): Modifier =
    absolutePadding(left = bounds.left, top = bounds.top).size(bounds.width, bounds.height)
