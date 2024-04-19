package info.dvkr.screenstream.ui.tabs

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector

public interface ScreenStreamTab {
    public val icon: ImageVector

    public val iconSelected: ImageVector

    @get:StringRes
    public val labelResId: Int

    @Composable
    public fun Content(boundsInWindow: Rect, modifier: Modifier)
}