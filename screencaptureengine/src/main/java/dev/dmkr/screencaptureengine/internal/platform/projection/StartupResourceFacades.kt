package dev.dmkr.screencaptureengine.internal.platform.projection

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.Surface
import dev.dmkr.screencaptureengine.internal.target.StartupRenderingGlAccess

internal interface ProjectionHandle {
    fun registerCallback(callback: MediaProjection.Callback, callbackHandler: ProjectionCallbackHandlerHandle?)

    fun unregisterCallback(callback: MediaProjection.Callback)

    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: ProjectionSurfaceHandle,
        callback: VirtualDisplay.Callback?,
        callbackHandler: ProjectionCallbackHandlerHandle?,
    ): VirtualDisplay?

    fun stop()
}

internal class MediaProjectionHandle internal constructor(
    private val mediaProjection: MediaProjection,
) : ProjectionHandle {
    override fun registerCallback(callback: MediaProjection.Callback, callbackHandler: ProjectionCallbackHandlerHandle?) {
        mediaProjection.registerCallback(callback, callbackHandler.androidHandlerOrNull())
    }

    override fun unregisterCallback(callback: MediaProjection.Callback) {
        mediaProjection.unregisterCallback(callback)
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: ProjectionSurfaceHandle,
        callback: VirtualDisplay.Callback?,
        callbackHandler: ProjectionCallbackHandlerHandle?,
    ): VirtualDisplay? =
        mediaProjection.createVirtualDisplay(
            name,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface.androidSurface(),
            callback,
            callbackHandler.androidHandlerOrNull(),
        )

    override fun stop() {
        mediaProjection.stop()
    }
}

internal interface ProjectionCallbackRegistration : AutoCloseable {
    val callbackHandler: ProjectionCallbackHandlerHandle?

    val projectionStopArbiter: ProjectionStopArbiter

    val projectionStopObserved: Boolean

    fun register(projection: ProjectionHandle)
}

internal interface ProjectionTargetOwnerHandle : AutoCloseable {
    fun targetSizeLimits(): ProjectionTargetSizeLimits

    fun createTarget(width: Int, height: Int, densityDpi: Int): ProjectionTargetHandle

    fun startupRenderingGlAccess(): StartupRenderingGlAccess
}

internal data class ProjectionTargetSizeLimits(
    val maxWidth: Int,
    val maxHeight: Int,
) {
    init {
        require(maxWidth > 0) { "maxWidth must be positive, was $maxWidth" }
        require(maxHeight > 0) { "maxHeight must be positive, was $maxHeight" }
    }
}

internal interface ProjectionTargetHandle : AutoCloseable {
    val generation: Long
    val width: Int
    val height: Int
    val densityDpi: Int
    val surface: ProjectionSurfaceHandle
}

internal interface ProjectionVirtualDisplayOwner : AutoCloseable {
    val isClosed: Boolean

    fun currentTargetSnapshot(): ProjectionTargetSnapshot?

    fun bindTarget(target: ProjectionTargetHandle): ProjectionTargetHandle?
}

/**
 * Non-owning view of the active projection target. The resource bag or virtual display owner
 * remains responsible for closing the target and releasing the surface.
 */
internal data class ProjectionTargetSnapshot(
    val generation: Long,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val surface: ProjectionSurfaceHandle,
)

internal interface ProjectionCallbackHandlerHandle

internal class AndroidProjectionCallbackHandlerHandle internal constructor(
    internal val handler: Handler,
) : ProjectionCallbackHandlerHandle

internal interface ProjectionSurfaceHandle

internal class AndroidProjectionSurfaceHandle internal constructor(
    internal val surface: Surface,
) : ProjectionSurfaceHandle

internal fun ProjectionCallbackHandlerHandle?.androidHandlerOrNull(): Handler? =
    (this as? AndroidProjectionCallbackHandlerHandle)?.handler

internal fun ProjectionSurfaceHandle.androidSurface(): Surface =
    (this as? AndroidProjectionSurfaceHandle)?.surface
        ?: error("Projection target surface is not backed by an Android Surface.")
