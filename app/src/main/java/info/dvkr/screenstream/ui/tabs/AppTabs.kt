package info.dvkr.screenstream.ui.tabs

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stream
import androidx.compose.ui.graphics.vector.ImageVector
import info.dvkr.screenstream.R

internal enum class AppTabs(
    internal val icon: ImageVector,
    internal val iconSelected: ImageVector,
    @StringRes internal val label: Int
) {
    STREAM(Icons.Outlined.Stream, Icons.Filled.Stream, R.string.app_tab_stream),
    SETTINGS(Icons.Outlined.Settings, Icons.Filled.Settings, R.string.app_tab_settings),
    ABOUT(Icons.Outlined.Info, Icons.Filled.Info, R.string.app_tab_about),
}