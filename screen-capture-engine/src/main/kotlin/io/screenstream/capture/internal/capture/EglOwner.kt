package io.screenstream.capture.internal.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import io.screenstream.capture.ScreenCaptureProblem

internal class EglOwner(
    private val egl: EglPlatform = AndroidEglPlatform,
    private val gl: GlesPlatform = AndroidGlesPlatform,
) {
    internal class EglRetirementOutcome(
        internal val cleanupFailure: Throwable?,
        internal val residue: Throwable?,
        internal val namespaceDestroyedProof: GLNamespaceDestroyedProof?,
    )

    internal class GLNameResidue(private val owner: EglOwner, internal val cause: Throwable) {
        internal fun belongsTo(expected: EglOwner): Boolean = owner === expected
    }

    internal class GLNamespaceDestroyedProof(
        private val owner: EglOwner,
    ) {
        internal fun retires(residue: GLNameResidue): Boolean = residue.belongsTo(owner)

        internal fun matches(expected: EglOwner): Boolean = owner === expected
    }

    internal enum class FragmentPrecision { High, Medium, }

    private class GLCapabilities(
        val maxTextureSize: Int,
        val maxViewportWidth: Int,
        val maxViewportHeight: Int,
        val fragmentPrecision: FragmentPrecision,
    )

    private class EglErrorException(operation: String, errorCode: Int) :
        Exception("$operation failed with EGL error 0x${errorCode.toString(16)}")

    private class GLErrorException(phase: String, errorCode: Int) :
        Exception("$phase observed GL error 0x${errorCode.toString(16)}")

    private enum class Integrity { Healthy, Unusable, Destroyed, }

    private enum class BindingState { NeverAttempted, InitialBindEntered, Current, UnbindAttempted, Unbound, }

    private enum class OwnedRetirement { Absent, Owned, Attempted, Retired, }

    private enum class ThreadRetirement { Blocked, Eligible, Attempted, Released, }

    private val eglVersion = IntArray(2)
    private val chosenConfigs = arrayOfNulls<EGLConfig>(1)
    private val chosenConfigCount = IntArray(1)
    private val maxTextureSize = IntArray(1)
    private val maxViewportDimensions = IntArray(2)
    private val highFloatRange = IntArray(2)
    private val highFloatPrecision = IntArray(1)
    private val namespaceDestroyedProof = GLNamespaceDestroyedProof(this)
    private var capabilities: GLCapabilities? = null
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var pbuffer: EGLSurface? = null
    private var integrity: Integrity? = null
    private var bindingState = BindingState.NeverAttempted
    private var contextRetirement = OwnedRetirement.Absent
    private var pbufferRetirement = OwnedRetirement.Absent
    private var threadRetirement = ThreadRetirement.Blocked
    private var bindingThread: Thread? = null
    private var unbindFailure: Throwable? = null
    private var contextDestroyFailure: Throwable? = null
    private var pbufferDestroyFailure: Throwable? = null
    private var releaseThreadFailure: Throwable? = null

    internal val isHealthy: Boolean
        get() = integrity == Integrity.Healthy

    internal fun open(): FragmentPrecision {
        check((display == null) && (context == null) && (pbuffer == null))
        val createdDisplay = egl.getDisplay()
        if (createdDisplay === EGL14.EGL_NO_DISPLAY) failEgl("eglGetDisplay")
        display = createdDisplay
        if (!egl.initialize(createdDisplay, eglVersion)) failEgl("eglInitialize")
        val attributes = intArrayOf(
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_CONFORMANT, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE,
        )
        if (!egl.chooseConfig(createdDisplay, attributes, chosenConfigs, chosenConfigCount)) {
            failEgl("eglChooseConfig")
        }
        val selectedConfig = chosenConfigs[0]
        if ((chosenConfigCount[0] != 1) || (selectedConfig == null)) {
            failInternal("eglChooseConfig returned malformed success")
        }

        val createdContext = egl.createContext(
            createdDisplay,
            selectedConfig,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        ).also { returnedContext ->
            if (returnedContext === EGL14.EGL_NO_CONTEXT) failEgl("eglCreateContext")
            context = returnedContext
            contextRetirement = OwnedRetirement.Owned
        }

        val createdPbuffer = egl.createPbufferSurface(
            createdDisplay,
            selectedConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        ).also { returnedPbuffer ->
            if (returnedPbuffer === EGL14.EGL_NO_SURFACE) failEgl("eglCreatePbufferSurface")
            pbuffer = returnedPbuffer
            pbufferRetirement = OwnedRetirement.Owned
        }

        bindingState = BindingState.InitialBindEntered
        if (!egl.makeCurrent(createdDisplay, createdPbuffer, createdContext)) failEgl("eglMakeCurrent")
        if ((egl.currentDisplay != createdDisplay) || (egl.currentContext != createdContext) ||
            (egl.currentReadSurface != createdPbuffer) || (egl.currentDrawSurface != createdPbuffer)
        ) {
            failInternal("eglMakeCurrent did not install the exact owned display/context/pbuffer tuple")
        }
        bindingThread = Thread.currentThread()
        bindingState = BindingState.Current
        integrity = Integrity.Healthy
        return queryCapabilities().fragmentPrecision
    }

    private fun requireCurrent() {
        if (integrity != Integrity.Healthy) failInternal("EGL context integrity is not healthy")
        if ((bindingState != BindingState.Current) || (bindingThread !== Thread.currentThread())) {
            failInternal("EGL binding is not validated on the Capture thread")
        }
    }

    internal inline fun runGlesGroup(crossinline commands: (GlesPlatform) -> Boolean) {
        requireCurrent()
        val api = gl
        var commandFailure: Exception? = null
        val commandsSucceeded = try {
            commands(api)
        } catch (failure: Exception) {
            commandFailure = failure
            false
        }
        endGlesGroup(commandsSucceeded, commandFailure)
    }

    internal fun endGlesGroup(commandsSucceeded: Boolean, commandFailure: Exception? = null) {
        var postprobeFailure: Exception? = null
        val postprobe = try {
            gl.getError()
        } catch (failure: Exception) {
            postprobeFailure = failure
            GLES20.GL_NO_ERROR
        }
        if ((commandFailure == null) && (postprobeFailure == null) && (postprobe == GLES20.GL_NO_ERROR) && commandsSucceeded) {
            return
        }

        integrity = Integrity.Unusable
        val primary = commandFailure ?: postprobeFailure
        when (primary) {
            is CaptureBoundaryFailure -> throw primary
            null -> Unit
            else -> throw CaptureBoundaryFailure(problem = ScreenCaptureProblem.InternalFailure, physicalCause = primary)
        }
        if (postprobe != GLES20.GL_NO_ERROR) {
            val problem = if (postprobe == GLES20.GL_OUT_OF_MEMORY) {
                ScreenCaptureProblem.ResourceExhausted
            } else {
                ScreenCaptureProblem.InternalFailure
            }
            throw CaptureBoundaryFailure(problem = problem, physicalCause = GLErrorException("GLES postprobe", postprobe))
        }
        failInternal("GLES group returned malformed or unsuccessful evidence")
    }

    internal fun markUnusable() {
        if (integrity == Integrity.Healthy) integrity = Integrity.Unusable
    }

    internal fun validateTargetAndOutput(plan: CapturePlan) {
        val capabilities = capabilities ?: failInternal("GL capabilities are unavailable")
        val targetWidth = plan.targetWidthPx
        val targetHeight = plan.targetHeightPx
        if ((targetWidth > capabilities.maxTextureSize) || (targetHeight > capabilities.maxTextureSize) ||
            (targetWidth > capabilities.maxViewportWidth) || (targetHeight > capabilities.maxViewportHeight) ||
            (plan.outputWidthPx > capabilities.maxTextureSize) || (plan.outputHeightPx > capabilities.maxTextureSize) ||
            (plan.outputWidthPx > capabilities.maxViewportWidth) || (plan.outputHeightPx > capabilities.maxViewportHeight)
        ) {
            throw CaptureBoundaryFailure(
                problem = ScreenCaptureProblem.ResourceExhausted,
                physicalCause = CapturePhysicalException("Target or output dimensions exceed GL capacity"),
            )
        }
    }

    private fun queryCapabilities(): GLCapabilities {
        var selectedPrecision: FragmentPrecision? = null
        runGlesGroup { api ->
            api.getInteger(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize)
            api.getInteger(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDimensions)
            api.getShaderPrecisionFormat(highFloatRange, highFloatPrecision)
            selectedPrecision = when {
                (highFloatRange[0] > 0) && (highFloatRange[1] > 0) && (highFloatPrecision[0] > 0) -> FragmentPrecision.High
                (highFloatRange[0] == 0) && (highFloatRange[1] == 0) && (highFloatPrecision[0] == 0) -> FragmentPrecision.Medium
                else -> null
            }
            (maxTextureSize[0] > 0) && (maxViewportDimensions[0] > 0) && (maxViewportDimensions[1] > 0) && (selectedPrecision != null)
        }
        return GLCapabilities(
            maxTextureSize = maxTextureSize[0],
            maxViewportWidth = maxViewportDimensions[0],
            maxViewportHeight = maxViewportDimensions[1],
            fragmentPrecision = checkNotNull(selectedPrecision),
        ).also { capabilities = it }
    }

    internal fun close(): EglRetirementOutcome {
        val ownedDisplay = display ?: return EglRetirementOutcome(cleanupFailure = null, residue = null, namespaceDestroyedProof = null)

        if (bindingState == BindingState.NeverAttempted) {
            val ownedContext = context
            val ownsExactContext = (contextRetirement == OwnedRetirement.Owned) && (ownedContext != null) && (ownedContext !== EGL14.EGL_NO_CONTEXT)
            val hasNoPbuffer = (pbufferRetirement == OwnedRetirement.Absent) && (pbuffer == null)
            val exactNeverBoundPrefix = ownsExactContext && hasNoPbuffer
            if (exactNeverBoundPrefix) {
                contextDestroyFailure = try {
                    contextRetirement = OwnedRetirement.Attempted
                    if (!egl.destroyContext(ownedDisplay, ownedContext)) {
                        throw EglErrorException("eglDestroyContext", egl.getError())
                    }
                    context = null
                    contextRetirement = OwnedRetirement.Retired
                    integrity = Integrity.Destroyed
                    null
                } catch (failure: Exception) {
                    failure
                }
                val residue = if (contextRetirement == OwnedRetirement.Retired) {
                    null
                } else {
                    contextDestroyFailure ?: CapturePhysicalException("EGL context remains owned")
                }
                if (residue == null) display = null
                return EglRetirementOutcome(
                    cleanupFailure = contextDestroyFailure,
                    residue = residue,
                    namespaceDestroyedProof = namespaceDestroyedProof.takeIf { integrity == Integrity.Destroyed },
                )
            }
            if ((contextRetirement == OwnedRetirement.Absent) && (pbufferRetirement == OwnedRetirement.Absent)) {
                display = null
                return EglRetirementOutcome(cleanupFailure = null, residue = null, namespaceDestroyedProof = null)
            }
            val residue = contextDestroyFailure ?: CapturePhysicalException("EGL never-bound ownership invariant was not satisfied")
            return EglRetirementOutcome(
                cleanupFailure = contextDestroyFailure,
                residue = residue,
                namespaceDestroyedProof = null,
            )
        }

        if (bindingState == BindingState.InitialBindEntered) {
            val residue = CapturePhysicalException("Exact EGL binding ownership was not proved")
            return EglRetirementOutcome(cleanupFailure = null, residue = residue, namespaceDestroyedProof = null)
        }

        if (bindingState == BindingState.Current) {
            if (bindingThread !== Thread.currentThread()) {
                val residue = CapturePhysicalException("EGL teardown did not run on its binding thread")
                return EglRetirementOutcome(cleanupFailure = null, residue = residue, namespaceDestroyedProof = null)
            }
            unbindFailure = try {
                bindingState = BindingState.UnbindAttempted
                bindingThread = null
                integrity = Integrity.Unusable
                if (!egl.makeCurrent(ownedDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)) {
                    throw EglErrorException("eglMakeCurrent teardown", egl.getError())
                }
                if (egl.currentContext !== EGL14.EGL_NO_CONTEXT) {
                    throw CapturePhysicalException("eglMakeCurrent teardown returned true without clearing the current context")
                }
                bindingState = BindingState.Unbound
                threadRetirement = ThreadRetirement.Eligible
                null
            } catch (failure: Exception) {
                failure
            }
        }
        var firstFailure = unbindFailure
        if (bindingState != BindingState.Unbound) {
            val residue = unbindFailure ?: CapturePhysicalException("EGL unbind returned without proving no current context")
            return EglRetirementOutcome(cleanupFailure = firstFailure, residue = residue, namespaceDestroyedProof = null)
        }

        val ownedContext = context
        if ((ownedContext != null) && (ownedContext !== EGL14.EGL_NO_CONTEXT) && (contextRetirement == OwnedRetirement.Owned)) {
            contextDestroyFailure = try {
                contextRetirement = OwnedRetirement.Attempted
                if (!egl.destroyContext(ownedDisplay, ownedContext)) {
                    throw EglErrorException("eglDestroyContext", egl.getError())
                }
                context = null
                contextRetirement = OwnedRetirement.Retired
                integrity = Integrity.Destroyed
                null
            } catch (failure: Exception) {
                failure
            }
        }
        firstFailure = firstFailure ?: contextDestroyFailure

        val ownedPbuffer = pbuffer
        val ownsExactPbuffer = (ownedPbuffer != null) && (ownedPbuffer !== EGL14.EGL_NO_SURFACE) && (pbufferRetirement == OwnedRetirement.Owned)
        if (ownsExactPbuffer) {
            pbufferDestroyFailure = try {
                pbufferRetirement = OwnedRetirement.Attempted
                if (!egl.destroySurface(ownedDisplay, ownedPbuffer)) {
                    throw EglErrorException("eglDestroySurface", egl.getError())
                }
                pbuffer = null
                pbufferRetirement = OwnedRetirement.Retired
                null
            } catch (failure: Exception) {
                failure
            }
        }
        firstFailure = firstFailure ?: pbufferDestroyFailure

        if (threadRetirement == ThreadRetirement.Eligible) {
            releaseThreadFailure = try {
                threadRetirement = ThreadRetirement.Attempted
                if (!egl.releaseThread()) {
                    throw EglErrorException("eglReleaseThread", egl.getError())
                }
                threadRetirement = ThreadRetirement.Released
                null
            } catch (failure: Exception) {
                failure
            }
        }
        firstFailure = firstFailure ?: releaseThreadFailure

        val residue = when {
            (contextRetirement == OwnedRetirement.Owned) || (contextRetirement == OwnedRetirement.Attempted) ->
                contextDestroyFailure ?: CapturePhysicalException("EGL context remains owned")

            (pbufferRetirement == OwnedRetirement.Owned) || (pbufferRetirement == OwnedRetirement.Attempted) ->
                pbufferDestroyFailure ?: CapturePhysicalException("EGL pbuffer remains owned")

            threadRetirement != ThreadRetirement.Released ->
                releaseThreadFailure ?: CapturePhysicalException("EGL thread release remains unproved")

            else -> null
        }
        if (residue == null) {
            display = null
        }
        return EglRetirementOutcome(
            cleanupFailure = firstFailure,
            residue = residue,
            namespaceDestroyedProof = namespaceDestroyedProof.takeIf { integrity == Integrity.Destroyed },
        )
    }

    private fun failEgl(operation: String): Nothing {
        val error = egl.getError()
        val problem = if (
            (error == EGL14.EGL_BAD_ALLOC) &&
            ((display == null) || (display !== EGL14.EGL_NO_DISPLAY)) &&
            ((context == null) || (context !== EGL14.EGL_NO_CONTEXT)) &&
            ((pbuffer == null) || (pbuffer !== EGL14.EGL_NO_SURFACE))
        ) {
            ScreenCaptureProblem.ResourceExhausted
        } else {
            ScreenCaptureProblem.InternalFailure
        }
        throw CaptureBoundaryFailure(problem = problem, physicalCause = EglErrorException(operation, error))
    }

    private fun failInternal(message: String): Nothing {
        if (integrity == Integrity.Healthy) integrity = Integrity.Unusable
        throw CaptureBoundaryFailure(problem = ScreenCaptureProblem.InternalFailure, physicalCause = CapturePhysicalException(message))
    }
}
