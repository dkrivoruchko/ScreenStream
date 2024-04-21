package info.dvkr.screenstream.ui

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.calculatePosture
import androidx.compose.material3.adaptive.collectFoldingFeaturesAsState
import androidx.compose.material3.adaptive.currentWindowSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toSize

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Immutable
internal data class WindowAdaptiveInfo(val windowSizeClass: WindowSizeClass, val windowPosture: Posture)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun currentWindowAdaptiveInfo(): WindowAdaptiveInfo = WindowAdaptiveInfo(
    WindowSizeClass.calculateFromSize(with(LocalDensity.current) { currentWindowSize().toSize().toDpSize() }),
    calculatePosture(collectFoldingFeaturesAsState().value)
)