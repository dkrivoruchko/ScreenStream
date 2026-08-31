package io.screenstream.capture.internal.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface

internal interface ProjectionPlatform {
    fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler)

    fun createVirtualDisplay(projection: MediaProjection, widthPx: Int, heightPx: Int, densityDpi: Int, surface: Surface): VirtualDisplay?

    fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int)

    fun setSurface(display: VirtualDisplay, surface: Surface?)

    fun release(display: VirtualDisplay)

    fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback)

    fun stop(projection: MediaProjection)
}

internal object AndroidProjectionPlatform : ProjectionPlatform {
    private const val VIRTUAL_DISPLAY_NAME: String = "ScreenCaptureEngine"

    override fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler) {
        projection.registerCallback(callback, handler)
    }

    override fun createVirtualDisplay(
        projection: MediaProjection,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        surface: Surface,
    ): VirtualDisplay? = projection.createVirtualDisplay(
        /* name = */ VIRTUAL_DISPLAY_NAME,
        /* width = */ widthPx,
        /* height = */ heightPx,
        /* dpi = */ densityDpi,
        /* flags = */ DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        /* surface = */ surface,
        /* callback = */ null,
        /* handler = */ null,
    )

    override fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int) {
        display.resize(widthPx, heightPx, densityDpi)
    }

    override fun setSurface(display: VirtualDisplay, surface: Surface?) {
        display.surface = surface
    }

    override fun release(display: VirtualDisplay) {
        display.release()
    }

    override fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback) {
        projection.unregisterCallback(callback)
    }

    override fun stop(projection: MediaProjection) {
        projection.stop()
    }
}
