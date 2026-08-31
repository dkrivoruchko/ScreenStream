package io.screenstream.capture.internal.metrics

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import androidx.annotation.RequiresApi

internal interface BuiltInCaptureMetricsPlatform {
    val sdkInt: Int

    fun mainHandler(): Handler

    fun displayId(display: Display): Int

    fun isValid(display: Display): Boolean

    fun getDisplay(displayManager: DisplayManager, displayId: Int): Display?

    fun registerDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
        handler: Handler,
    )

    fun unregisterDisplayListener(displayManager: DisplayManager, listener: DisplayManager.DisplayListener)

    fun createDisplayContext(applicationContext: Context, display: Display): Context

    fun createApi30WindowContext(displayContext: Context): Context

    fun createApi31WindowContext(applicationContext: Context, display: Display): Context

    fun windowManager(windowContext: Context): WindowManager

    fun maximumWindowBounds(windowManager: WindowManager): Rect

    fun getRealSize(display: Display, point: Point)
}

internal object AndroidBuiltInCaptureMetricsPlatform : BuiltInCaptureMetricsPlatform {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun mainHandler(): Handler = Handler(Looper.getMainLooper())

    override fun displayId(display: Display): Int = display.displayId

    override fun isValid(display: Display): Boolean = display.isValid

    override fun getDisplay(displayManager: DisplayManager, displayId: Int): Display? =
        displayManager.getDisplay(displayId)

    override fun registerDisplayListener(
        displayManager: DisplayManager,
        listener: DisplayManager.DisplayListener,
        handler: Handler,
    ) {
        displayManager.registerDisplayListener(listener, handler)
    }

    override fun unregisterDisplayListener(displayManager: DisplayManager, listener: DisplayManager.DisplayListener) =
        displayManager.unregisterDisplayListener(listener)

    override fun createDisplayContext(applicationContext: Context, display: Display): Context =
        applicationContext.createDisplayContext(display)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun createApi30WindowContext(displayContext: Context): Context =
        displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun createApi31WindowContext(applicationContext: Context, display: Display): Context =
        applicationContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION, null)

    override fun windowManager(windowContext: Context): WindowManager =
        requireNotNull(windowContext.getSystemService(WindowManager::class.java)) {
            "WindowManager must be available for the selected display"
        }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun maximumWindowBounds(windowManager: WindowManager): Rect =
        windowManager.maximumWindowMetrics.bounds

    @Suppress("DEPRECATION")
    override fun getRealSize(display: Display, point: Point) {
        display.getRealSize(point)
    }
}
