package info.dvkr.screenstream.ui

import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ads.AnchoredAdaptiveBanner
import info.dvkr.screenstream.ads.rememberAnchoredAdaptiveBannerState
import info.dvkr.screenstream.ui.main.AppUpdateBar
import info.dvkr.screenstream.ui.main.ModuleRecoveryCard
import info.dvkr.screenstream.ui.main.ModuleUnavailableContent
import info.dvkr.screenstream.ui.main.PrimaryPaneHeader
import info.dvkr.screenstream.update.AppUpdate
import io.screenstream.core.StreamingModuleManager
import io.screenstream.core.module.StreamingModule
import io.screenstream.foundation.logging.Logger
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun AppScreen(
    streamingModuleManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    moduleStateAtEntry: StreamingModuleManager.State,
) {
    val state = streamingModuleManager.state.collectAsStateWithLifecycle().value
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialMethodChooserPending = retain { mutableStateOf(false) }
    LaunchedEffect(streamingModuleManager, lifecycle) {
        if (moduleStateAtEntry != StreamingModuleManager.State.NoModule &&
            moduleStateAtEntry != StreamingModuleManager.State.Closed
        ) return@LaunchedEffect

        var completed = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (completed) return@repeatOnLifecycle
            if (activity?.isFinishing == false && streamingModuleManager.state.value == moduleStateAtEntry && streamingModuleManager.selectModule()) {
                initialMethodChooserPending.value = true
            }
            completed = true
        }
    }

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background, contentColor = colors.onBackground) {
        if (state == StreamingModuleManager.State.Closing || state == StreamingModuleManager.State.Closed) return@Surface

        // Keep the header and banner mounted when controller content is replaced.
        val movableLayout = remember(streamingModuleManager, appUpdate) {
            movableContentOf<StreamingModuleManager.State, StreamingModule.Ui?> { moduleState, moduleUi ->
                AdaptiveAppScreenLayout(streamingModuleManager, appUpdate, moduleState, moduleUi, initialMethodChooserPending)
            }
        }
        val instanceId = state.instanceId
        if (instanceId == null) {
            movableLayout(state, null)
        } else {
            // Compose the controller before the adaptive layout subcomposes.
            streamingModuleManager.InstanceContent(instanceId, fallback = { movableLayout(state, null) }) { moduleUi ->
                movableLayout(state, moduleUi)
            }
        }
    }
}

@Composable
private fun AdaptiveAppScreenLayout(
    streamingModuleManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    state: StreamingModuleManager.State,
    moduleUi: StreamingModule.Ui?,
    initialMethodChooserPending: MutableState<Boolean>,
) {
    val instanceId = state.instanceId
    val sections = key(instanceId) {
        moduleUi?.sections.orEmpty().map { section ->
            val sectionId = section.id
            key(sectionId) {
                val movableSectionContent = remember {
                    movableContentOf<@Composable () -> Unit> { content -> key(instanceId, sectionId) { content() } }
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
        val placement = density.calculateAppPanePlacement(
            viewportSizePx = IntSize(constraints.maxWidth, constraints.maxHeight),
            viewportOriginPx = viewportOriginPx,
            hinges = hinges,
            allowWidthBasedSplit = windowInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND),
        )

        // Keep the primary pane at this call site as the layout changes.
        PrimaryPane(
            streamingModuleManager = streamingModuleManager,
            appUpdate = appUpdate,
            streamingModuleManagerState = state,
            streamingModuleUi = moduleUi,
            streamingModuleUiSections = sections,
            scrollState = if (placement.secondaryBounds == null) combinedScrollState else overviewScrollState,
            placement = placement,
            initialMethodChooserPending = initialMethodChooserPending,
        )

        val secondaryBounds = placement.secondaryBounds
        if (secondaryBounds != null && moduleUi != null) {
            SecondaryPane(
                streamingModuleUiSections = sections,
                scrollState = settingsScrollState,
                dialogBounds = if (placement.usesHingeBounds) secondaryBounds else null,
                modifier = Modifier
                    .absolutePadding(left = secondaryBounds.left, top = secondaryBounds.top)
                    .size(secondaryBounds.width, secondaryBounds.height),
            )
        }

        placement.dividerBounds?.let {
            VerticalDivider(
                Modifier
                    .absolutePadding(left = it.left, top = it.top)
                    .size(it.width, it.height)
            )
        }
    }
}

@Composable
private fun PrimaryPane(
    streamingModuleManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    streamingModuleManagerState: StreamingModuleManager.State,
    streamingModuleUi: StreamingModule.Ui?,
    streamingModuleUiSections: List<StreamingModule.Ui.Section>,
    scrollState: ScrollState,
    placement: AppPanePlacement,
    initialMethodChooserPending: MutableState<Boolean>,
) {
    val instanceId = streamingModuleManagerState.instanceId
    val primaryWidth = placement.primaryBounds.width
    val primaryHeight = placement.primaryBounds.height
    val tabletopGap = placement.tabletopGap
    val headerDialogBounds = if (placement.usesHingeBounds) placement.primaryBounds else null
    val contentDialogBounds = placement.lowerBounds ?: headerDialogBounds
    val shortPrimary = primaryHeight < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp
    val scrollHeaderWithBody = shortPrimary && tabletopGap == null && instanceId != null && streamingModuleUi != null
    val bannerState = rememberAnchoredAdaptiveBannerState(primaryWidth)?.takeUnless { shortPrimary }
    val availableHeaderHeight = primaryHeight - (bannerState?.reservedHeight ?: 0.dp)
    val maxHeaderHeight = if (tabletopGap == null) availableHeaderHeight / 2 else availableHeaderHeight

    val movableHeader = remember { movableContentOf<@Composable () -> Unit> { it() } }
    val header: @Composable () -> Unit = {
        Column {
            AppUpdateBar(appUpdate, contentDialogBounds)
            PrimaryPaneHeader(
                streamingModuleManager = streamingModuleManager,
                streamingModuleManagerState = streamingModuleManagerState,
                inlineMethodSelector = primaryWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp,
                headerDialogBounds = headerDialogBounds,
                contentDialogBounds = contentDialogBounds,
                initialMethodChooserPending = initialMethodChooserPending,
            )
        }
    }

    Column(
        Modifier
            .absolutePadding(left = placement.primaryLayoutBounds.left, top = placement.primaryLayoutBounds.top)
            .size(placement.primaryLayoutBounds.width, placement.primaryLayoutBounds.height)
    ) {
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
                StreamingModule.Ui.ModalHost.Provide(PaneModalHost(contentDialogBounds)) {
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
                                    .background(Brush.verticalGradient(colors = listOf(background.copy(alpha = 0f), background.copy(alpha = 0.9f))))
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                streamingModuleUi.CaptureActionButton(Modifier.padding(horizontal = 12.dp))
                            }
                        },
                    ) { innerPadding ->
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(innerPadding),
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
                                if (streamingModuleManagerState is StreamingModuleManager.State.Unresponsive || streamError != null) {
                                    ModuleRecoveryCard(
                                        { streamingModuleManager.restartModule(instanceId) },
                                        streamError?.message,
                                    )
                                }
                                streamingModuleUiSections.forEach {
                                    if (placement.secondaryBounds == null || it.role == StreamingModule.Ui.Section.Role.Overview) it.content()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryPane(
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
    override fun Modal(title: String?, onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
        if (title == null) {
            AppDialog(title = null, onDismissRequest = onDismissRequest, dialogBounds = dialogBounds, content = content)
        } else {
            AdaptiveAppModal(title = title, onDismissRequest = onDismissRequest, dialogBounds = dialogBounds) {
                Box(Modifier.fillMaxWidth()) { content() }
            }
        }
    }
}