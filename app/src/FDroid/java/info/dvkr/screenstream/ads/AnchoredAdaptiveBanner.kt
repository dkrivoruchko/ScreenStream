package info.dvkr.screenstream.ads

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

internal interface AnchoredAdaptiveBannerState {
    val reservedHeight: Dp
}

@Composable
internal fun rememberAnchoredAdaptiveBannerState(paneWidth: Dp): AnchoredAdaptiveBannerState? = null

@Composable
internal fun AnchoredAdaptiveBanner(state: AnchoredAdaptiveBannerState) = Unit
