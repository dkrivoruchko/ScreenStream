package io.screenstream.capture.internal.capture

import android.graphics.SurfaceTexture
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.view.Surface
import androidx.annotation.RequiresApi

internal interface TargetPlatform {
    companion object {
        internal const val TRANSFORM_MATRIX_FLOAT_COUNT: Int = 16

        internal fun supportsDataSpace(platformSdkInt: Int): Boolean =
            platformSdkInt >= VERSION_CODES.TIRAMISU
    }

    fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture

    fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int)

    fun createSurface(surfaceTexture: SurfaceTexture): Surface

    fun setFrameListener(surfaceTexture: SurfaceTexture, listener: SurfaceTexture.OnFrameAvailableListener, handler: Handler)

    fun clearFrameListener(surfaceTexture: SurfaceTexture)

    fun updateTexImage(surfaceTexture: SurfaceTexture)

    fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray)

    fun dataSpace(surfaceTexture: SurfaceTexture): Int

    fun releaseSurface(surface: Surface)

    fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture)
}

internal object AndroidTargetPlatform : TargetPlatform {
    override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture = SurfaceTexture(oesTextureName, false)
    override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) {
        surfaceTexture.setDefaultBufferSize(widthPx, heightPx)
    }

    override fun createSurface(surfaceTexture: SurfaceTexture): Surface = Surface(surfaceTexture)
    override fun setFrameListener(surfaceTexture: SurfaceTexture, listener: SurfaceTexture.OnFrameAvailableListener, handler: Handler) {
        surfaceTexture.setOnFrameAvailableListener(listener, handler)
    }

    override fun clearFrameListener(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener(null)
    }

    override fun updateTexImage(surfaceTexture: SurfaceTexture) {
        surfaceTexture.updateTexImage()
    }

    override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) {
        surfaceTexture.getTransformMatrix(destination)
    }

    @RequiresApi(VERSION_CODES.TIRAMISU)
    override fun dataSpace(surfaceTexture: SurfaceTexture): Int = surfaceTexture.dataSpace

    override fun releaseSurface(surface: Surface) {
        surface.release()
    }

    override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.release()
    }
}
