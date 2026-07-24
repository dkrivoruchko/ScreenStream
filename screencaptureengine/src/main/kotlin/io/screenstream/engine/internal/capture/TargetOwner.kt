package io.screenstream.engine.internal.capture

import android.annotation.TargetApi
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import io.screenstream.engine.ScreenCaptureProblem

internal fun interface CaptureSourceSink {
    /** Bounded ingress only; implementations may not run Control policy inline. */
    fun onSourceAvailable(fact: SourceAvailable)
}

internal class TargetReleaseOutcome internal constructor(
    internal val cleanupFailure: Throwable?,
    internal val residue: Throwable?,
)

internal class TargetOwner internal constructor(
    private val captureHandler: Handler,
    private val eglOwner: EglOwner,
    private val sourceSink: CaptureSourceSink,
    private val clock: CaptureClock,
    private val platform: TargetPlatform = AndroidTargetPlatform,
) {
    private enum class ProducerState {
        AwaitingEvidence,
        Attached,
        NoProducer,
        Detached,
    }

    private enum class ListenerRetirement {
        NeverAttempted,
        InstallationAttempted,
        InstallationAmbiguous,
        Installed,
        RemovalAttempted,
        RemovalAmbiguous,
        RemovalReturned,
        MarkerPosted,
        MarkerPassed,
        MarkerRejected,
    }

    internal val source: CaptureSourceToken = CaptureSourceToken.create()
    private val textureNames = IntArray(1)
    private var oesTextureName = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var mode: CaptureTargetMode? = null
    private var widthPx = 0
    private var heightPx = 0
    private var listenerFenced = false
    private var listenerRetirement = ListenerRetirement.NeverAttempted
    private var listenerRetirementFailure: Throwable? = null
    private var pendingSource = false
    private var sourceNotificationOutstanding = false
    private var producerState = ProducerState.AwaitingEvidence
    private var surfaceReleaseAttempted = false
    private var surfaceTextureReleaseAttempted = false
    private var oesDeleteAttempted = false

    private val frameListener = SurfaceTexture.OnFrameAvailableListener { callbackTexture ->
        if (listenerFenced || !listenerMayBeInstalled || callbackTexture !== surfaceTexture) {
            return@OnFrameAvailableListener
        }
        pendingSource = true
        if (!sourceNotificationOutstanding) {
            sourceNotificationOutstanding = true
            sourceSink.onSourceAvailable(SourceAvailable(source))
        }
    }

    internal val producerSurface: Surface
        get() = checkNotNull(surface)

    internal val targetWidthPx: Int
        get() = widthPx

    internal val targetHeightPx: Int
        get() = heightPx

    internal val targetMode: CaptureTargetMode
        get() = checkNotNull(mode)

    internal val isProducerAttached: Boolean
        get() = producerState == ProducerState.Attached

    internal val canFenceListener: Boolean
        get() = listenerMayBeInstalled && !listenerFenced

    private val listenerMayBeInstalled: Boolean
        get() = when (listenerRetirement) {
            ListenerRetirement.InstallationAttempted,
            ListenerRetirement.InstallationAmbiguous,
            ListenerRetirement.Installed,
            ListenerRetirement.RemovalAttempted,
            ListenerRetirement.RemovalAmbiguous,
                -> true

            ListenerRetirement.NeverAttempted,
            ListenerRetirement.RemovalReturned,
            ListenerRetirement.MarkerPosted,
            ListenerRetirement.MarkerPassed,
            ListenerRetirement.MarkerRejected,
                -> false
        }

    internal val blocksEglTeardown: Boolean
        get() = (listenerRetirement != ListenerRetirement.NeverAttempted &&
                listenerRetirement != ListenerRetirement.MarkerPassed) || surface != null || surfaceTexture != null

    internal val blocksProducerRetirement: Boolean
        get() = listenerRetirement != ListenerRetirement.NeverAttempted &&
                listenerRetirement != ListenerRetirement.MarkerPassed

    internal fun construct(plan: CapturePlan) {
        check(
            oesTextureName == 0 && surfaceTexture == null && surface == null &&
                    mode == null && widthPx == 0 && heightPx == 0,
        )
        eglOwner.validateTargetAndOutput(plan)
        val constructionDeadline = CaptureOperationDeadline.start(clock, glEnteredOperationSafetyNanos)
        eglOwner.runGlesGroup { gl ->
            gl.genTextures(textureNames)
            val generatedName = textureNames[0]
            if (generatedName == 0) return@runGlesGroup false
            oesTextureName = generatedName
            gl.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, generatedName)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            gl.texParameter(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            gl.texParameter(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE,
            )
            true
        }
        val createdTexture = try {
            platform.createSurfaceTexture(oesTextureName)
        } catch (failure: Surface.OutOfResourcesException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        surfaceTexture = createdTexture
        try {
            platform.setDefaultBufferSize(createdTexture, plan.targetWidthPx, plan.targetHeightPx)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        val createdSurface = try {
            platform.createSurface(createdTexture)
        } catch (failure: Surface.OutOfResourcesException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        surface = createdSurface
        mode = plan.targetMode
        widthPx = plan.targetWidthPx
        heightPx = plan.targetHeightPx
        if (!constructionDeadline.returnedTimely(clock)) {
            eglOwner.markUnknown()
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("Target construction returned after its safety interval"),
            )
        }
    }

    internal fun installListener() {
        val texture = checkNotNull(surfaceTexture)
        check(!listenerMayBeInstalled && listenerRetirement == ListenerRetirement.NeverAttempted && !listenerFenced)
        val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
        listenerRetirement = ListenerRetirement.InstallationAttempted
        try {
            platform.setFrameListener(texture, frameListener, captureHandler)
        } catch (failure: Exception) {
            listenerRetirement = ListenerRetirement.InstallationAmbiguous
            listenerRetirementFailure = failure
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        listenerRetirement = ListenerRetirement.Installed
        if (!deadline.returnedTimely(clock)) {
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                CapturePhysicalException("SurfaceTexture listener installation returned after its safety interval"),
            )
        }
    }

    internal fun recordDisplayCreation(returnedNonNull: Boolean) {
        check(producerState == ProducerState.AwaitingEvidence)
        producerState = if (returnedNonNull) ProducerState.Attached else ProducerState.NoProducer
    }

    internal fun recordSurfaceAttached() {
        producerState = ProducerState.Attached
    }

    internal fun recordSurfaceDetached() {
        producerState = ProducerState.Detached
    }

    internal fun consumePendingSource(): Boolean {
        if (listenerFenced || !listenerMayBeInstalled || producerState != ProducerState.Attached || !pendingSource) {
            return false
        }
        pendingSource = false
        sourceNotificationOutstanding = false
        return true
    }

    internal fun updateAndCopyTransform(destination: FloatArray): Int {
        check(destination.size >= 16)
        val texture = checkNotNull(surfaceTexture)
        try {
            platform.updateTexImage(texture)
            platform.getTransformMatrix(texture, destination)
            for (index in 0 until 16) {
                if (!destination[index].isFinite()) {
                    throw CaptureBoundaryFailure(
                        ScreenCaptureProblem.InternalFailure,
                        CapturePhysicalException("SurfaceTexture transform contains a nonfinite value"),
                    )
                }
            }
            return if (platform.sdkInt >= android.os.Build.VERSION_CODES.TIRAMISU) {
                platform.dataSpace(texture)
            } else {
                0
            }
        } catch (failure: CaptureBoundaryFailure) {
            throw failure
        } catch (failure: Exception) {
            eglOwner.markUnknown()
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun oesTextureName(): Int = oesTextureName.also { check(it != 0) }

    /**
     * Fences the target, removes its listener once, and posts the required same-Handler ordering marker.
     * Cleanup may continue only from [afterMarker].
     */
    internal fun fenceAndRemoveListener(afterMarker: (Throwable?) -> Unit) {
        check(!listenerFenced) { "Target listener has already been fenced" }
        listenerFenced = true
        pendingSource = false
        sourceNotificationOutstanding = false
        val texture = surfaceTexture
        if (listenerMayBeInstalled && texture != null) {
            val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
            listenerRetirement = ListenerRetirement.RemovalAttempted
            try {
                platform.clearFrameListener(texture, captureHandler)
            } catch (failure: Exception) {
                listenerRetirement = ListenerRetirement.RemovalAmbiguous
                listenerRetirementFailure = failure
                throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
            }
            listenerRetirement = ListenerRetirement.RemovalReturned
            if (!deadline.returnedTimely(clock)) {
                listenerRetirementFailure =
                    CapturePhysicalException("SurfaceTexture listener removal returned after its safety interval")
            }
        }
        val marker = Runnable {
            listenerRetirement = ListenerRetirement.MarkerPassed
            afterMarker(listenerRetirementFailure)
        }
        if (!captureHandler.post(marker)) {
            listenerRetirement = ListenerRetirement.MarkerRejected
            listenerRetirementFailure =
                CapturePhysicalException("Capture Handler rejected the listener ordering marker")
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                listenerRetirementFailure,
            )
        }
        listenerRetirement = ListenerRetirement.MarkerPosted
    }

    /** Display detach/release must already be proven before this dependent suffix is entered. */
    internal fun releaseAndroidAndOes(): TargetReleaseOutcome {
        if (listenerRetirement != ListenerRetirement.NeverAttempted &&
            listenerRetirement != ListenerRetirement.MarkerPassed
        ) {
            val failure = listenerRetirementFailure
                ?: CapturePhysicalException("Target listener ordering is not proven retired")
            return TargetReleaseOutcome(failure, failure)
        }
        var firstFailure: Throwable? = null
        val ownedSurface = surface
        if (ownedSurface != null && !surfaceReleaseAttempted) {
            surfaceReleaseAttempted = true
            val deadline = CaptureOperationDeadline.start(clock, androidEnteredOperationSafetyNanos)
            try {
                platform.releaseSurface(ownedSurface)
            } catch (failure: Exception) {
                return TargetReleaseOutcome(failure, failure)
            }
            surface = null
            if (!deadline.returnedTimely(clock)) {
                firstFailure = CapturePhysicalException("Surface.release returned after its safety interval")
            }
        }

        val ownedTexture = surfaceTexture
        if (ownedTexture != null && !surfaceTextureReleaseAttempted) {
            surfaceTextureReleaseAttempted = true
            try {
                platform.releaseSurfaceTexture(ownedTexture)
            } catch (failure: Exception) {
                return TargetReleaseOutcome(firstFailure.mergeFailure(failure), failure)
            }
            surfaceTexture = null
        }

        if (oesTextureName != 0 && !oesDeleteAttempted && eglOwner.isHealthy) {
            oesDeleteAttempted = true
            textureNames[0] = oesTextureName
            try {
                eglOwner.runGlesGroup { gl ->
                    gl.deleteTextures(textureNames)
                    true
                }
            } catch (failure: CaptureBoundaryFailure) {
                val residue = failure.physicalCause ?: failure
                return TargetReleaseOutcome(firstFailure.mergeFailure(residue), residue)
            }
            oesTextureName = 0
        }
        return TargetReleaseOutcome(firstFailure, null)
    }

    private fun Throwable?.mergeFailure(next: Throwable): Throwable {
        val first = this ?: return next
        if (next !== first && first.suppressed.none { it === next }) first.addSuppressed(next)
        return first
    }
}

internal interface TargetPlatform {
    val sdkInt: Int
    fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture
    fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int)
    fun createSurface(surfaceTexture: SurfaceTexture): Surface
    fun setFrameListener(
        surfaceTexture: SurfaceTexture,
        listener: SurfaceTexture.OnFrameAvailableListener,
        handler: Handler,
    )

    fun clearFrameListener(surfaceTexture: SurfaceTexture, handler: Handler)
    fun updateTexImage(surfaceTexture: SurfaceTexture)
    fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray)
    fun dataSpace(surfaceTexture: SurfaceTexture): Int
    fun releaseSurface(surface: Surface)
    fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture)
}

internal object AndroidTargetPlatform : TargetPlatform {
    override val sdkInt: Int
        get() = android.os.Build.VERSION.SDK_INT

    override fun createSurfaceTexture(oesTextureName: Int): SurfaceTexture = SurfaceTexture(oesTextureName, false)
    override fun setDefaultBufferSize(surfaceTexture: SurfaceTexture, widthPx: Int, heightPx: Int) {
        surfaceTexture.setDefaultBufferSize(widthPx, heightPx)
    }

    override fun createSurface(surfaceTexture: SurfaceTexture): Surface = Surface(surfaceTexture)
    override fun setFrameListener(
        surfaceTexture: SurfaceTexture,
        listener: SurfaceTexture.OnFrameAvailableListener,
        handler: Handler,
    ) {
        surfaceTexture.setOnFrameAvailableListener(listener, handler)
    }

    override fun clearFrameListener(surfaceTexture: SurfaceTexture, handler: Handler) {
        surfaceTexture.setOnFrameAvailableListener(null, handler)
    }

    override fun updateTexImage(surfaceTexture: SurfaceTexture) {
        surfaceTexture.updateTexImage()
    }

    override fun getTransformMatrix(surfaceTexture: SurfaceTexture, destination: FloatArray) {
        surfaceTexture.getTransformMatrix(destination)
    }

    @TargetApi(android.os.Build.VERSION_CODES.TIRAMISU)
    override fun dataSpace(surfaceTexture: SurfaceTexture): Int = surfaceTexture.dataSpace

    override fun releaseSurface(surface: Surface) {
        surface.release()
    }

    override fun releaseSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.release()
    }
}
