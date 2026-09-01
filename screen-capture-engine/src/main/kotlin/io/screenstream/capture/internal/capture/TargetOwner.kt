package io.screenstream.capture.internal.capture

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import io.screenstream.capture.ScreenCaptureProblem

internal class TargetOwner(
    private val captureHandler: Handler,
    private val eglOwner: EglOwner,
    private val sourceSink: SourceSink,
    private val callbackBoundary: CaptureCallbackBoundary,
    private val platformSdkInt: Int,
    private val platform: TargetPlatform = AndroidTargetPlatform,
) {
    internal fun interface SourceSink {
        fun onSourceAvailable(candidate: SourceCandidate)
    }

    internal class ReleaseOutcome(
        internal val cleanupFailure: Throwable?,
        internal val residue: Throwable?,
        internal val glNameResidue: EglOwner.GLNameResidue? = null,
    )

    internal class ListenerRemovalProof(private val owner: TargetOwner) {
        internal fun matches(target: TargetOwner): Boolean = owner === target
    }

    internal class ListenerRemovalOutcome(internal val proof: ListenerRemovalProof?, internal val failure: Throwable?)

    private enum class ListenerRetirement { NeverAttempted, InstallationAttempted, InstallationAmbiguous, Installed, RemovalAttempted, RemovalAmbiguous, RemovalReturned, }

    private enum class ReleaseState { Eligible, Attempted, Released, }

    internal val sourceCandidate: SourceCandidate = SourceCandidate()
    private val callbackIdentity = CaptureCallbackIdentity.Target(sourceCandidate.token)
    private val textureNames = IntArray(1)
    private val successfulListenerRemovalProof = ListenerRemovalProof(this)
    private var oesTextureName = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var mode: CaptureTargetMode? = null
    private var widthPx = 0
    private var heightPx = 0
    private var listenerFenced = false
    private var listenerRetirement = ListenerRetirement.NeverAttempted
    private var listenerRetirementFailure: Throwable? = null
    private var listenerRemovalProof: ListenerRemovalProof? = null
    private var surfaceReleaseState = ReleaseState.Eligible
    private var surfaceReleaseFailure: Throwable? = null
    private var surfaceTextureReleaseState = ReleaseState.Eligible
    private var surfaceTextureReleaseFailure: Throwable? = null
    private var oesTextureDeletionState = ReleaseState.Eligible
    private var oesTextureDeletionFailure: Throwable? = null

    private val frameListener = SurfaceTexture.OnFrameAvailableListener { callbackTexture ->
        runCaptureCallback(callbackBoundary, callbackIdentity) {
            if (listenerFenced || (!listenerMayBeInstalled) || (callbackTexture !== surfaceTexture)) {
                return@runCaptureCallback
            }
            sourceSink.onSourceAvailable(sourceCandidate)
        }
    }

    internal val producerSurface: Surface
        get() = checkNotNull(surface)

    internal val retirementSurface: Surface?
        get() = surface

    internal val targetWidthPx: Int
        get() = widthPx

    internal val targetHeightPx: Int
        get() = heightPx

    internal val targetMode: CaptureTargetMode
        get() = checkNotNull(mode)

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
                -> false
        }

    internal val blocksEglTeardown: Boolean
        get() = ((listenerRetirement != ListenerRetirement.NeverAttempted) && (listenerRetirement != ListenerRetirement.RemovalReturned))
                || (surface != null) || (surfaceTexture != null)

    internal fun open(plan: CapturePlan) {
        check(oesTextureName == 0)
        check(surfaceTexture == null)
        check(surface == null)
        check(mode == null)
        check(widthPx == 0)
        check(heightPx == 0)
        eglOwner.validateTargetAndOutput(plan)
        eglOwner.runGlesGroup { gl ->
            gl.genTextures(textureNames)
            val generatedTextureName = textureNames[0]
            if (generatedTextureName == 0) return@runGlesGroup false
            oesTextureName = generatedTextureName
            gl.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, generatedTextureName)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            gl.texParameter(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            true
        }
        val createdSurfaceTexture = try {
            platform.createSurfaceTexture(oesTextureName)
        } catch (failure: Surface.OutOfResourcesException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        surfaceTexture = createdSurfaceTexture
        try {
            platform.setDefaultBufferSize(createdSurfaceTexture, plan.targetWidthPx, plan.targetHeightPx)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        val createdSurface = try {
            platform.createSurface(createdSurfaceTexture)
        } catch (failure: Surface.OutOfResourcesException) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.ResourceExhausted, failure)
        } catch (failure: Exception) {
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
        surface = createdSurface
        mode = plan.targetMode
        widthPx = plan.targetWidthPx
        heightPx = plan.targetHeightPx
    }

    internal fun installListener() {
        val texture = checkNotNull(surfaceTexture)
        check(!listenerMayBeInstalled)
        check(listenerRetirement == ListenerRetirement.NeverAttempted)
        check(!listenerFenced)
        try {
            listenerRetirement = ListenerRetirement.InstallationAttempted
            platform.setFrameListener(texture, frameListener, captureHandler)
            listenerRetirement = ListenerRetirement.Installed
        } catch (failure: Exception) {
            if (listenerRetirement != ListenerRetirement.Installed) {
                listenerRetirement = ListenerRetirement.InstallationAmbiguous
                listenerRetirementFailure = failure
            }
            throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
        }
    }

    internal fun requireSurfaceTexture(): SurfaceTexture = checkNotNull(surfaceTexture)

    @SuppressLint("NewApi")
    internal fun updateFrameAndReadDataSpace(surfaceTexture: SurfaceTexture, transformMatrix: FloatArray): Int {
        try {
            platform.updateTexImage(surfaceTexture)
        } catch (failure: Exception) {
            eglOwner.markUnusable()
            throw CaptureBoundaryFailure(problem = ScreenCaptureProblem.InternalFailure, physicalCause = failure)
        }
        try {
            platform.getTransformMatrix(surfaceTexture, transformMatrix)
            for (index in 0..<TargetPlatform.TRANSFORM_MATRIX_FLOAT_COUNT) {
                if (!transformMatrix[index].isFinite()) {
                    throw CaptureBoundaryFailure(
                        problem = ScreenCaptureProblem.InternalFailure,
                        physicalCause = CapturePhysicalException("SurfaceTexture transform contains a nonfinite value"),
                    )
                }
            }
            return if (TargetPlatform.supportsDataSpace(platformSdkInt)) {
                platform.dataSpace(surfaceTexture)
            } else {
                0
            }
        } catch (failure: CaptureBoundaryFailure) {
            throw failure
        } catch (failure: Exception) {
            eglOwner.markUnusable()
            throw CaptureBoundaryFailure(problem = ScreenCaptureProblem.InternalFailure, physicalCause = failure)
        }
    }

    internal fun requireOesTextureName(): Int = oesTextureName.also { check(it != 0) }

    internal fun retireOesTextureNameAfterContextDestroyed(proof: EglOwner.GLNamespaceDestroyedProof): Boolean {
        if (!proof.matches(eglOwner)) return false
        oesTextureName = 0
        oesTextureDeletionState = ReleaseState.Released
        return true
    }

    internal fun fenceAndRemoveListener(): ListenerRemovalOutcome {
        if (listenerFenced) {
            val proof = listenerRemovalProof
            val failure = listenerRetirementFailure
                ?: if (proof == null) CapturePhysicalException("Target listener removal remains unproved") else null
            return ListenerRemovalOutcome(proof = proof, failure = failure)
        }
        listenerFenced = true
        val texture = surfaceTexture
        if ((listenerMayBeInstalled) && (texture != null)) {
            try {
                listenerRetirement = ListenerRetirement.RemovalAttempted
                platform.clearFrameListener(texture)
                listenerRetirement = ListenerRetirement.RemovalReturned
                listenerRetirementFailure = null
                listenerRemovalProof = successfulListenerRemovalProof
            } catch (failure: Exception) {
                if (listenerRetirement == ListenerRetirement.RemovalReturned) {
                    return ListenerRemovalOutcome(proof = checkNotNull(listenerRemovalProof), failure = failure)
                }
                listenerRetirement = ListenerRetirement.RemovalAmbiguous
                listenerRetirementFailure = failure
                return ListenerRemovalOutcome(proof = null, failure = failure)
            }
        } else {
            listenerRemovalProof = successfulListenerRemovalProof
        }
        return ListenerRemovalOutcome(proof = checkNotNull(listenerRemovalProof), failure = null)
    }

    internal fun releaseAndroidAndOes(
        listenerProof: ListenerRemovalProof?,
        detachProof: ProjectionOwner.VirtualDisplayDetachProof?,
    ): ReleaseOutcome {
        val ownedSurface = surface
        if ((listenerProof?.matches(this) != true) || (detachProof?.matches(ownedSurface) != true)) {
            val failure = CapturePhysicalException("Target release prerequisites are not proven")
            return ReleaseOutcome(cleanupFailure = failure, residue = failure)
        }
        return releaseOwnedAndroidAndOes()
    }

    internal fun releaseAfterReplacement(
        listenerProof: ListenerRemovalProof?,
        replacementProof: ProjectionOwner.SurfaceReplacementProof?,
    ): ReleaseOutcome {
        val ownedSurface = surface
        if ((listenerProof?.matches(this) != true) || (replacementProof?.namesOld(ownedSurface) != true)) {
            val failure = CapturePhysicalException("Replaced Target release prerequisites are not proven")
            return ReleaseOutcome(cleanupFailure = failure, residue = failure)
        }
        return releaseOwnedAndroidAndOes()
    }

    internal fun rollbackUnattached(): ReleaseOutcome {
        if (listenerRetirement != ListenerRetirement.NeverAttempted) {
            val failure = CapturePhysicalException("Target candidate is not unattached")
            return ReleaseOutcome(cleanupFailure = failure, residue = failure)
        }
        return releaseOwnedAndroidAndOes()
    }

    internal fun releaseAfterDisplayRelease(
        listenerProof: ListenerRemovalProof?,
        displayReleaseProof: ProjectionOwner.VirtualDisplayReleaseProof?,
    ): ReleaseOutcome {
        if ((listenerProof?.matches(this) != true) || (displayReleaseProof == null)) {
            val failure = CapturePhysicalException("Target display-release prerequisites are not proven")
            return ReleaseOutcome(cleanupFailure = failure, residue = failure)
        }
        return releaseOwnedAndroidAndOes()
    }

    internal fun releaseKnownUnattached(listenerProof: ListenerRemovalProof?): ReleaseOutcome {
        if (listenerProof?.matches(this) != true) {
            val failure = CapturePhysicalException("Unattached Target listener-removal prerequisite is not proven")
            return ReleaseOutcome(cleanupFailure = failure, residue = failure)
        }
        return releaseOwnedAndroidAndOes()
    }

    private fun releaseOwnedAndroidAndOes(): ReleaseOutcome {
        val ownedSurface = surface
        var cleanupFailure: Throwable? = null

        if (ownedSurface != null) {
            if (surfaceReleaseState == ReleaseState.Attempted) {
                val failure = surfaceReleaseFailure
                    ?: CapturePhysicalException("Surface release returned without retiring its root")
                return ReleaseOutcome(cleanupFailure = failure, residue = failure)
            }
            try {
                surfaceReleaseState = ReleaseState.Attempted
                platform.releaseSurface(ownedSurface)
                surface = null
                surfaceReleaseState = ReleaseState.Released
            } catch (failure: Exception) {
                if (surfaceReleaseState != ReleaseState.Released) surfaceReleaseState = ReleaseState.Attempted
                surfaceReleaseFailure = failure
                if (surfaceReleaseState != ReleaseState.Released) {
                    return ReleaseOutcome(cleanupFailure = failure, residue = failure)
                }
                cleanupFailure = failure
            }
        }

        val ownedSurfaceTexture = surfaceTexture
        if (ownedSurfaceTexture != null) {
            if (surfaceTextureReleaseState == ReleaseState.Attempted) {
                val failure = surfaceTextureReleaseFailure
                    ?: CapturePhysicalException("SurfaceTexture release returned without retiring its root")
                return ReleaseOutcome(cleanupFailure = failure, residue = failure)
            }
            try {
                surfaceTextureReleaseState = ReleaseState.Attempted
                platform.releaseSurfaceTexture(ownedSurfaceTexture)
                surfaceTexture = null
                surfaceTextureReleaseState = ReleaseState.Released
            } catch (failure: Exception) {
                if (surfaceTextureReleaseState != ReleaseState.Released) {
                    surfaceTextureReleaseState = ReleaseState.Attempted
                }
                surfaceTextureReleaseFailure = failure
                if (surfaceTextureReleaseState != ReleaseState.Released) {
                    return ReleaseOutcome(cleanupFailure = cleanupFailure ?: failure, residue = failure)
                }
                cleanupFailure = cleanupFailure ?: failure
            }
        }

        if ((oesTextureName != 0) && eglOwner.isHealthy && (oesTextureDeletionState == ReleaseState.Eligible)) {
            textureNames[0] = oesTextureName
            try {
                oesTextureDeletionState = ReleaseState.Attempted
                eglOwner.runGlesGroup { gl ->
                    gl.deleteTextures(textureNames)
                    true
                }
                oesTextureName = 0
                oesTextureDeletionState = ReleaseState.Released
            } catch (failure: Exception) {
                if (oesTextureDeletionState != ReleaseState.Released) oesTextureDeletionState = ReleaseState.Attempted
                val physicalFailure = (failure as? CaptureBoundaryFailure)?.physicalCause ?: failure
                oesTextureDeletionFailure = physicalFailure
                cleanupFailure = cleanupFailure ?: physicalFailure
            }
        }
        cleanupFailure = cleanupFailure ?: oesTextureDeletionFailure
        val ownsAndroidResources = (surface != null) || (surfaceTexture != null)
        val residue = if (ownsAndroidResources || (oesTextureName != 0)) {
            cleanupFailure ?: CapturePhysicalException("Target retirement left an owned resource")
        } else {
            null
        }
        val glNameResidue = if (!ownsAndroidResources && (oesTextureName != 0)) {
            EglOwner.GLNameResidue(eglOwner, checkNotNull(residue))
        } else {
            null
        }
        return ReleaseOutcome(cleanupFailure = cleanupFailure, residue = residue, glNameResidue = glNameResidue)
    }
}
