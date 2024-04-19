package info.dvkr.screenstream.ui.tabs.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import info.dvkr.screenstream.R
import info.dvkr.screenstream.ui.tabs.ScreenStreamTab

internal object SettingsTab : ScreenStreamTab {
    override val icon: ImageVector = Icons.Outlined.Settings
    override val iconSelected: ImageVector = Icons.Filled.Settings
    override val labelResId: Int = R.string.app_tab_settings

    @Composable
    override fun Content(boundsInWindow: Rect, modifier: Modifier) = SettingsTabContent(boundsInWindow, modifier)
}