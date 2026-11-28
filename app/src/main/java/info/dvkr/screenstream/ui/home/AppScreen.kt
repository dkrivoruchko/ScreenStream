package info.dvkr.screenstream.ui.home

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowSizeClass
import info.dvkr.screenstream.ads.AnchoredAdaptiveBanner
import info.dvkr.screenstream.ads.rememberAnchoredAdaptiveBannerState
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
        AdaptiveAppScreenLayout(streamingModuleManager, appUpdate, state, initialMethodChooserPending)
    }
}

@Composable
private fun AdaptiveAppScreenLayout(
    streamingModuleManager: StreamingModuleManager,
    appUpdate: AppUpdate,
    state: StreamingModuleManager.State,
    initialMethodChooserPending: MutableState<Boolean>,
) {
    val instanceId = state.instanceId
    val (combinedScrollState, overviewScrollState, settingsScrollState) = key(instanceId) {
        Triple(rememberScrollState(), rememberScrollState(), rememberScrollState())
    }
    val movableHeader = remember { movableContentOf<@Composable () -> Unit> { it() } }

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

        val primaryBounds = placement.primaryBounds
        val lowerBounds = placement.lowerBounds
        val secondaryBounds = placement.secondaryBounds
        val headerDialogBounds = primaryBounds.takeIf { placement.usesHingeBounds }
        val contentDialogBounds = lowerBounds ?: headerDialogBounds
        val shortPrimary = primaryBounds.height < WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND.dp
        val sharePrimaryScroll = shortPrimary && lowerBounds == null
        val bannerState = rememberAnchoredAdaptiveBannerState(primaryBounds.width)?.takeIf { banner ->
            !sharePrimaryScroll && (lowerBounds == null || banner.reservedHeight <= primaryBounds.height / 2)
        }
        val availableHeaderHeight = (primaryBounds.height - (bannerState?.reservedHeight ?: 0.dp)).coerceAtLeast(0.dp)
        val maxHeaderHeight = if (lowerBounds == null) availableHeaderHeight / 2 else availableHeaderHeight
        val fixedHeaderModifier = Modifier
            .heightIn(max = maxHeaderHeight)
            .verticalScroll(rememberScrollState())

        // The overlays keep one composition while the header moves between Scaffold slots.
        AppHeaderWithOverlays(
            streamingModuleManager = streamingModuleManager,
            streamingModuleManagerState = state,
            inlineMethodSelector = primaryBounds.width >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp,
            headerDialogBounds = headerDialogBounds,
            contentDialogBounds = contentDialogBounds,
            initialMethodChooserPending = initialMethodChooserPending,
        ) { appHeader ->
            val header: @Composable () -> Unit = {
                movableHeader {
                    Column {
                        AppUpdateBar(appUpdate, contentDialogBounds)
                        appHeader()
                    }
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = LocalContentColor.current,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (!sharePrimaryScroll) {
                        Column(
                            Modifier
                                .absolutePadding(left = primaryBounds.left, top = primaryBounds.top)
                                .width(primaryBounds.width)
                                .then(if (lowerBounds == null) Modifier else Modifier.height(primaryBounds.height)),
                        ) {
                            Box(fixedHeaderModifier) {
                                header()
                            }
                            if (bannerState != null) AnchoredAdaptiveBanner(bannerState)
                        }
                    }
                },
            ) { innerPadding ->
                val bodyBounds = lowerBounds ?: primaryBounds
                val primaryModifier = if (lowerBounds != null || sharePrimaryScroll) {
                    Modifier
                        .absolutePadding(left = bodyBounds.left, top = bodyBounds.top)
                        .size(bodyBounds.width, bodyBounds.height)
                } else {
                    // The top bar padding already includes the primary pane's physical top.
                    Modifier
                        .absolutePadding(left = primaryBounds.left)
                        .size(primaryBounds.width, primaryBounds.bottom)
                        .padding(innerPadding)
                }

                Box(Modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
                    val modulePanes: @Composable (StreamingModule.Ui?) -> Unit = { moduleUi ->
                        val sections = key(instanceId) {
                            moduleUi?.sections.orEmpty().map { section ->
                                val sectionId = section.id
                                key(sectionId) {
                                    val movableSection = remember {
                                        movableContentOf<@Composable () -> Unit> { content -> key(instanceId, sectionId) { content() } }
                                    }
                                    section.copy(content = { movableSection(section.content) })
                                }
                            }
                        }

                        PrimaryModulePane(
                            streamingModuleManager = streamingModuleManager,
                            streamingModuleManagerState = state,
                            streamingModuleUi = moduleUi,
                            streamingModuleUiSections = sections,
                            scrollState = if (secondaryBounds == null) combinedScrollState else overviewScrollState,
                            dialogBounds = contentDialogBounds,
                            showSettings = secondaryBounds == null,
                            shortHeader = header.takeIf { sharePrimaryScroll },
                            fixedHeaderModifier = fixedHeaderModifier,
                            modifier = primaryModifier,
                        )

                        if (secondaryBounds != null && moduleUi != null) {
                            ModuleSettingsPane(
                                streamingModuleUiSections = sections,
                                scrollState = settingsScrollState,
                                dialogBounds = secondaryBounds.takeIf { placement.usesHingeBounds },
                                modifier = Modifier
                                    .absolutePadding(left = secondaryBounds.left, top = secondaryBounds.top)
                                    .size(secondaryBounds.width, secondaryBounds.height),
                            )
                        }
                    }

                    if (instanceId == null) {
                        modulePanes(null)
                    } else {
                        streamingModuleManager.InstanceContent(instanceId, fallback = { modulePanes(null) }, layout = modulePanes)
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
        }
    }
}
