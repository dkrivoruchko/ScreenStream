package io.screenstream.engine.internal.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.view.Surface
import io.screenstream.engine.ScreenCaptureProblem
import java.util.concurrent.atomic.AtomicBoolean

internal const val androidEnteredOperationSafetyNanos: Long = 5_000_000_000L
internal const val initialResizeSafetyNanos: Long = 5_000_000_000L

/** Resource-local accepted-projection identity; never used as a Session generation. */
internal class ProjectionToken private constructor() {
    internal companion object {
        internal fun create(): ProjectionToken = ProjectionToken()
    }
}

internal interface ProjectionCallbackSink {
    /** These calls are bounded callback ingress only; implementations may not run Control policy inline. */
    fun onProjectionStopped(token: ProjectionToken)

    fun onCapturedContentResize(
        token: ProjectionToken,
        widthPx: Int,
        heightPx: Int,
        arrivalElapsedRealtimeNanos: Long,
    )

    fun onCapturedContentVisibilityChanged(token: ProjectionToken, isVisible: Boolean)
}

internal sealed interface ProjectionOperationResult {
    data object Success : ProjectionOperationResult

    class Failure internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable?,
    ) : ProjectionOperationResult
}

internal sealed interface VirtualDisplayCreationResult {
    class Created internal constructor(
        internal val display: VirtualDisplay,
    ) : VirtualDisplayCreationResult

    data object ReturnedNull : VirtualDisplayCreationResult

    class Failed internal constructor(
        internal val problem: ScreenCaptureProblem,
        internal val cause: Throwable,
    ) : VirtualDisplayCreationResult
}

internal class ProjectionOwner internal constructor(
    private val projection: MediaProjection,
    private val controlHandler: Handler,
    private val callbackSink: ProjectionCallbackSink,
    private val clock: CaptureClock,
    private val platform: ProjectionPlatform = AndroidProjectionPlatform,
) {
    private enum class CallbackRegistration {
        Prepared,
        Attempted,
        Registered,
        UnregisterAttempted,
        Unregistered,
    }

    private enum class DisplayCreation {
        NotAttempted,
        Attempted,
        ReturnedNull,
        Owned,
        ReleaseAttempted,
        Released,
    }

    internal val token: ProjectionToken = ProjectionToken.create()
    private val callbackFence = AtomicBoolean(true)
    private var callbackRegistration = CallbackRegistration.Prepared
    private var displayCreation = DisplayCreation.NotAttempted
    private var virtualDisplay: VirtualDisplay? = null
    private var actualWidthPx = 0
    private var actualHeightPx = 0
    private var actualDensityDpi = 0
    private var attachedSurface: Surface? = null
    private var projectionStopAttempted = false
    private var projectionStopped = false
    internal var firstResizeDeadlineNanos: Long? = null
        private set

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (callbackFence.get()) callbackSink.onProjectionStopped(token)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            val arrivalElapsedRealtimeNanos = clock.elapsedRealtimeNanos()
            if (callbackFence.get()) {
                callbackSink.onCapturedContentResize(token, width, height, arrivalElapsedRealtimeNanos)
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (callbackFence.get()) callbackSink.onCapturedContentVisibilityChanged(token, isVisible)
        }
    }

    internal val hasOwnedDisplay: Boolean
        get() = virtualDisplay != null

    internal val currentSurface: Surface?
        get() = attachedSurface

    internal fun registerCallback(): ProjectionOperationResult {
        check(callbackRegistration == CallbackRegistration.Prepared)
        callbackRegistration = CallbackRegistration.Attempted
        val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
        return try {
            platform.registerCallback(projection, callback, controlHandler)
            callbackRegistration = CallbackRegistration.Registered
            if (deadline.returnedTimely(clock)) {
                ProjectionOperationResult.Success
            } else {
                ProjectionOperationResult.Failure(
                    ScreenCaptureProblem.InternalFailure,
                    CapturePhysicalException("MediaProjection.registerCallback returned after its safety interval"),
                )
            }
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun createVirtualDisplay(plan: CapturePlan, surface: Surface): VirtualDisplayCreationResult {
        check(displayCreation == DisplayCreation.NotAttempted)
        displayCreation = DisplayCreation.Attempted
        val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
        return try {
            val returned = platform.createVirtualDisplay(
                projection = projection,
                widthPx = plan.sourceWidthPx,
                heightPx = plan.sourceHeightPx,
                densityDpi = plan.densityDpi,
                surface = surface,
            )
            if (returned == null) {
                displayCreation = DisplayCreation.ReturnedNull
                if (deadline.returnedTimely(clock)) {
                    VirtualDisplayCreationResult.ReturnedNull
                } else {
                    VirtualDisplayCreationResult.Failed(
                        ScreenCaptureProblem.InternalFailure,
                        CapturePhysicalException("MediaProjection.createVirtualDisplay returned null after its safety interval"),
                    )
                }
            } else {
                virtualDisplay = returned
                displayCreation = DisplayCreation.Owned
                firstResizeDeadlineNanos = if (platform.sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Math.addExact(clock.elapsedRealtimeNanos(), initialResizeSafetyNanos)
                } else {
                    null
                }
                actualWidthPx = plan.sourceWidthPx
                actualHeightPx = plan.sourceHeightPx
                actualDensityDpi = plan.densityDpi
                attachedSurface = surface
                if (deadline.returnedTimely(clock)) {
                    VirtualDisplayCreationResult.Created(returned)
                } else {
                    VirtualDisplayCreationResult.Failed(
                        ScreenCaptureProblem.InternalFailure,
                        CapturePhysicalException("MediaProjection.createVirtualDisplay returned after its safety interval"),
                    )
                }
            }
        } catch (failure: SecurityException) {
            VirtualDisplayCreationResult.Failed(ScreenCaptureProblem.CaptureUnavailable, failure)
        } catch (failure: OutOfMemoryError) {
            VirtualDisplayCreationResult.Failed(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            VirtualDisplayCreationResult.Failed(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun resizeIfChanged(plan: CapturePlan): ProjectionOperationResult {
        val display = virtualDisplay
            ?: return ProjectionOperationResult.Failure(
                ScreenCaptureProblem.CaptureUnavailable,
                CapturePhysicalException("VirtualDisplay is unavailable"),
            )
        if (actualWidthPx == plan.sourceWidthPx && actualHeightPx == plan.sourceHeightPx &&
            actualDensityDpi == plan.densityDpi
        ) {
            return ProjectionOperationResult.Success
        }
        val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
        return try {
            platform.resize(display, plan.sourceWidthPx, plan.sourceHeightPx, plan.densityDpi)
            actualWidthPx = plan.sourceWidthPx
            actualHeightPx = plan.sourceHeightPx
            actualDensityDpi = plan.densityDpi
            if (!deadline.returnedTimely(clock)) {
                ProjectionOperationResult.Failure(
                    ScreenCaptureProblem.InternalFailure,
                    CapturePhysicalException("VirtualDisplay.resize returned after its safety interval"),
                )
            } else {
                ProjectionOperationResult.Success
            }
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun attachSurface(surface: Surface): ProjectionOperationResult = setSurface(surface)

    internal fun detachSurface(): ProjectionOperationResult = setSurface(null)

    private fun setSurface(surface: Surface?): ProjectionOperationResult {
        val display = virtualDisplay
            ?: return if (surface == null) {
                ProjectionOperationResult.Success
            } else {
                ProjectionOperationResult.Failure(
                    ScreenCaptureProblem.CaptureUnavailable,
                    CapturePhysicalException("VirtualDisplay is unavailable"),
                )
            }
        if (attachedSurface === surface) return ProjectionOperationResult.Success
        val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
        return try {
            platform.setSurface(display, surface)
            attachedSurface = surface
            if (!deadline.returnedTimely(clock)) {
                ProjectionOperationResult.Failure(
                    ScreenCaptureProblem.InternalFailure,
                    CapturePhysicalException("VirtualDisplay.setSurface returned after its safety interval"),
                )
            } else {
                ProjectionOperationResult.Success
            }
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    /** Capture close fences callback authority before any physical cleanup starts. */
    internal fun fenceCallbacks() {
        callbackFence.set(false)
    }

    internal fun releaseDisplay(): ProjectionOperationResult {
        val display = virtualDisplay ?: return ProjectionOperationResult.Success
        check(displayCreation == DisplayCreation.Owned)
        displayCreation = DisplayCreation.ReleaseAttempted
        return try {
            platform.release(display)
            virtualDisplay = null
            attachedSurface = null
            displayCreation = DisplayCreation.Released
            ProjectionOperationResult.Success
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun unregisterCallback(): ProjectionOperationResult {
        val mustUnregister = callbackRegistration == CallbackRegistration.Attempted ||
                callbackRegistration == CallbackRegistration.Registered
        if (!mustUnregister) return ProjectionOperationResult.Success
        callbackRegistration = CallbackRegistration.UnregisterAttempted
        return try {
            platform.unregisterCallback(projection, callback)
            callbackRegistration = CallbackRegistration.Unregistered
            ProjectionOperationResult.Success
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun stopProjection(): ProjectionOperationResult {
        if (projectionStopped) return ProjectionOperationResult.Success
        check(!projectionStopAttempted)
        projectionStopAttempted = true
        return try {
            platform.stop(projection)
            projectionStopped = true
            ProjectionOperationResult.Success
        } catch (failure: Exception) {
            ProjectionOperationResult.Failure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }
}

internal interface ProjectionPlatform {
    val sdkInt: Int

    fun registerCallback(projection: MediaProjection, callback: MediaProjection.Callback, handler: Handler)

    fun createVirtualDisplay(
        projection: MediaProjection,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        surface: Surface,
    ): VirtualDisplay?

    fun resize(display: VirtualDisplay, widthPx: Int, heightPx: Int, densityDpi: Int)

    fun setSurface(display: VirtualDisplay, surface: Surface?)

    fun release(display: VirtualDisplay)

    fun unregisterCallback(projection: MediaProjection, callback: MediaProjection.Callback)

    fun stop(projection: MediaProjection)
}

internal object AndroidProjectionPlatform : ProjectionPlatform {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun registerCallback(
        projection: MediaProjection,
        callback: MediaProjection.Callback,
        handler: Handler,
    ) {
        projection.registerCallback(callback, handler)
    }

    override fun createVirtualDisplay(
        projection: MediaProjection,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        surface: Surface,
    ): VirtualDisplay? = projection.createVirtualDisplay(
        "ScreenCaptureEngine",
        widthPx,
        heightPx,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        surface,
        null,
        null,
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
