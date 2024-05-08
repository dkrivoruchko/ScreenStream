package info.dvkr.screenstream.ui.tabs.about

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.ScreenStreamTab

internal object AboutTab : ScreenStreamTab {
    override val icon: ImageVector = Icons.Outlined.Info
    override val iconSelected: ImageVector = Icons.Filled.Info
    override val labelResId: Int = R.string.app_tab_about

    @Composable
    override fun Content(modifier: Modifier) = AboutTabContent(modifier)
}