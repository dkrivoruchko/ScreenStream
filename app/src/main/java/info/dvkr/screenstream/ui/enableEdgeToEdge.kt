package info.dvkr.screenstream.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// Based on androidx.activity.ComponentActivity.enableEdgeToEdge
internal fun ComponentActivity.enableEdgeToEdge(statusBarColor: Color, navigationBarColor: Color) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    window.statusBarColor = statusBarColor.toArgb()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        window.navigationBarColor = navigationBarColor.toArgb()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = statusBarColor.luminance() > 0.5f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isAppearanceLightNavigationBars = navigationBarColor.luminance() > 0.5f
        }
    }
}