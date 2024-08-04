package info.dvkr.screenstream.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// Based on androidx.activity.ComponentActivity.enableEdgeToEdge
@Suppress("DEPRECATION")
internal fun ComponentActivity.enableEdgeToEdge(statusBarColor: Color, navigationBarColor: Color) {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Transparent on Android 15+
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        window.statusBarColor = statusBarColor.toArgb()
    }

    // No navigationBarColor for API < 26 as it works bad
    // Transparent on Android 15+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}