package info.dvkr.screenstream.ui.tabs.stream

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.ScreenStreamTab

internal object StreamTab : ScreenStreamTab {
    override val icon: ImageVector = Icons.Outlined.Stream
    override val iconSelected: ImageVector = Icons.Filled.Stream
    override val labelResId: Int = R.string.app_tab_stream

    @Composable
    override fun Content(boundsInWindow: Rect, modifier: Modifier) = StreamTabContent(boundsInWindow, modifier)
}