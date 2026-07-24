package io.screenstream.engine.internal.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import io.screenstream.engine.ScreenCaptureProblem
import java.nio.Buffer

internal const val glEnteredOperationSafetyNanos: Long = 10_000_000_000L

internal fun interface CaptureClock {
    fun elapsedRealtimeNanos(): Long
}

internal class CaptureOperationDeadline private constructor(
    private val deadlineNanos: Long,
) {
    internal fun returnedTimely(clock: CaptureClock): Boolean = clock.elapsedRealtimeNanos() < deadlineNanos

    internal companion object {
        internal fun start(clock: CaptureClock, durationNanos: Long): CaptureOperationDeadline {
            require(durationNanos > 0L)
            return CaptureOperationDeadline(Math.addExact(clock.elapsedRealtimeNanos(), durationNanos))
        }
    }
}

internal class CapturePhysicalException internal constructor(message: String) : Exception(message)

internal class EglErrorException internal constructor(
    operation: String,
    internal val errorCode: Int,
) : Exception("$operation failed with EGL error 0x${errorCode.toString(16)}")

internal class GlErrorException internal constructor(
    phase: String,
    internal val errorCode: Int,
) : Exception("$phase observed GL error 0x${errorCode.toString(16)}")

internal class CaptureBoundaryFailure internal constructor(
    internal val problem: ScreenCaptureProblem,
    internal val physicalCause: Throwable?,
) : Exception(physicalCause)

internal enum class FragmentPrecision {
    High,
    Medium,
}

internal class GlCapabilities internal constructor(
    internal val maxTextureSize: Int,
    internal val maxViewportWidth: Int,
    internal val maxViewportHeight: Int,
    internal val fragmentPrecision: FragmentPrecision,
)

internal class EglOwner internal constructor(
    private val clock: CaptureClock,
    private val egl: EglPlatform = AndroidEglPlatform,
    internal val gl: GlesPlatform = AndroidGlesPlatform,
) {
    private enum class Integrity {
        Healthy,
        PoisonedByOutOfMemory,
        Unknown,
        ContextDestroyed,
    }

    private val eglVersion = IntArray(2)
    private val chosenConfigs = arrayOfNulls<EGLConfig>(1)
    private val chosenConfigCount = IntArray(1)
    private val maxTextureSize = IntArray(1)
    private val maxViewportDimensions = IntArray(2)
    private val highFloatRange = IntArray(2)
    private val highFloatPrecision = IntArray(1)

    private var display: EGLDisplay? = null
    private var config: EGLConfig? = null
    private var context: EGLContext? = null
    private var pbuffer: EGLSurface? = null
    private var integrity = Integrity.Unknown
    private var unbindAttempted = false
    private var contextDestroyAttempted = false
    private var pbufferDestroyAttempted = false
    private var releaseThreadAttempted = false

    internal val isHealthy: Boolean
        get() = integrity == Integrity.Healthy

    internal fun open(): GlCapabilities {
        check(display == null && context == null && pbuffer == null)
        val deadline = CaptureOperationDeadline.start(clock, glEnteredOperationSafetyNanos)
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
        if (chosenConfigCount[0] != 1 || selectedConfig == null) {
            failInternal("eglChooseConfig returned malformed success")
        }
        config = selectedConfig

        val createdContext = egl.createContext(
            createdDisplay,
            selectedConfig,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
        )
        if (createdContext === EGL14.EGL_NO_CONTEXT) failEgl("eglCreateContext")
        context = createdContext

        val createdPbuffer = egl.createPbufferSurface(
            createdDisplay,
            selectedConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
        )
        if (createdPbuffer === EGL14.EGL_NO_SURFACE) failEgl("eglCreatePbufferSurface")
        pbuffer = createdPbuffer

        if (!egl.makeCurrent(createdDisplay, createdPbuffer, createdContext)) failEgl("eglMakeCurrent")
        integrity = Integrity.Healthy
        requireCurrent()
        val capabilities = queryCapabilities()
        if (!deadline.returnedTimely(clock)) {
            integrity = Integrity.Unknown
            failInternal("EGL construction returned after its safety interval")
        }
        return capabilities
    }

    internal fun requireCurrent() {
        if (integrity != Integrity.Healthy) failInternal("EGL context integrity is not healthy")
        val expected = context ?: failInternal("EGL context is absent")
        if (egl.currentContext() !== expected) {
            val error = egl.getError()
            integrity = Integrity.Unknown
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.InternalFailure,
                EglErrorException("eglGetCurrentContext identity check", error),
            )
        }
    }

    internal fun runGlesGroup(commands: (GlesPlatform) -> Boolean) {
        val api = beginGlesGroup()
        val commandsSucceeded = try {
            commands(api)
        } catch (failure: Exception) {
            failExternalGlCall(failure)
        }
        endGlesGroup(commandsSucceeded)
    }

    /** Allocation-free frame groups use these two calls directly instead of a capturing lambda. */
    internal fun beginGlesGroup(): GlesPlatform {
        requireCurrent()
        val preprobe = gl.getError()
        if (preprobe != GLES20.GL_NO_ERROR) failGl("GLES preprobe", preprobe)
        return gl
    }

    internal fun endGlesGroup(commandsSucceeded: Boolean) {
        val postprobe = gl.getError()
        if (postprobe != GLES20.GL_NO_ERROR) failGl("GLES postprobe", postprobe)
        if (!commandsSucceeded) failInternal("GLES group returned malformed or unsuccessful evidence")
    }

    internal fun failExternalGlCall(failure: Exception): Nothing {
        integrity = Integrity.Unknown
        throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, failure)
    }

    internal fun markUnknown() {
        if (integrity == Integrity.Healthy) integrity = Integrity.Unknown
    }

    internal fun validateTargetAndOutput(plan: CapturePlan) {
        val capabilities = capabilities ?: failInternal("GL capabilities are unavailable")
        val targetWidth = plan.targetWidthPx
        val targetHeight = plan.targetHeightPx
        if (targetWidth > capabilities.maxTextureSize || targetHeight > capabilities.maxTextureSize ||
            targetWidth > capabilities.maxViewportWidth || targetHeight > capabilities.maxViewportHeight ||
            plan.outputWidthPx > capabilities.maxTextureSize || plan.outputHeightPx > capabilities.maxTextureSize ||
            plan.outputWidthPx > capabilities.maxViewportWidth ||
            plan.outputHeightPx > capabilities.maxViewportHeight
        ) {
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.ResourceExhausted,
                CapturePhysicalException("Target or output dimensions exceed GL capacity"),
            )
        }
    }

    private var capabilities: GlCapabilities? = null

    private fun queryCapabilities(): GlCapabilities {
        var selectedPrecision: FragmentPrecision? = null
        runGlesGroup { api ->
            api.getInteger(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize)
            api.getInteger(GLES20.GL_MAX_VIEWPORT_DIMS, maxViewportDimensions)
            api.getShaderPrecisionFormat(highFloatRange, highFloatPrecision)
            selectedPrecision = when {
                highFloatRange[0] > 0 && highFloatRange[1] > 0 && highFloatPrecision[0] > 0 ->
                    FragmentPrecision.High

                highFloatRange[0] == 0 && highFloatRange[1] == 0 && highFloatPrecision[0] == 0 ->
                    FragmentPrecision.Medium

                else -> null
            }
            maxTextureSize[0] > 0 && maxViewportDimensions[0] > 0 &&
                    maxViewportDimensions[1] > 0 && selectedPrecision != null
        }
        return GlCapabilities(
            maxTextureSize = maxTextureSize[0],
            maxViewportWidth = maxViewportDimensions[0],
            maxViewportHeight = maxViewportDimensions[1],
            fragmentPrecision = checkNotNull(selectedPrecision),
        ).also { capabilities = it }
    }

    internal fun close(): Throwable? {
        var firstFailure: Throwable? = null
        val ownedDisplay = display
        val ownedContext = context
        val ownedPbuffer = pbuffer
        if (ownedDisplay == null) return null

        if (ownedContext != null && ownedContext !== EGL14.EGL_NO_CONTEXT && !unbindAttempted) {
            unbindAttempted = true
            val unbound = try {
                egl.makeCurrent(ownedDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            } catch (failure: Exception) {
                firstFailure = failure
                false
            }
            if (!unbound) {
                if (firstFailure == null) firstFailure = EglErrorException("eglMakeCurrent teardown", egl.getError())
                integrity = Integrity.Unknown
                return firstFailure
            }
            if (egl.currentContext() !== EGL14.EGL_NO_CONTEXT) {
                firstFailure = EglErrorException("eglGetCurrentContext inverse check", egl.getError())
                integrity = Integrity.Unknown
                return firstFailure
            }
        }

        if (ownedContext != null && ownedContext !== EGL14.EGL_NO_CONTEXT && !contextDestroyAttempted) {
            contextDestroyAttempted = true
            try {
                if (!egl.destroyContext(ownedDisplay, ownedContext)) {
                    firstFailure = firstFailure ?: EglErrorException("eglDestroyContext", egl.getError())
                } else {
                    context = null
                    integrity = Integrity.ContextDestroyed
                }
            } catch (failure: Exception) {
                firstFailure = firstFailure ?: failure
            }
        }

        if (ownedPbuffer != null && ownedPbuffer !== EGL14.EGL_NO_SURFACE && !pbufferDestroyAttempted) {
            pbufferDestroyAttempted = true
            try {
                if (!egl.destroySurface(ownedDisplay, ownedPbuffer)) {
                    firstFailure = firstFailure ?: EglErrorException("eglDestroySurface", egl.getError())
                } else {
                    pbuffer = null
                }
            } catch (failure: Exception) {
                firstFailure = firstFailure ?: failure
            }
        }

        if (!releaseThreadAttempted) {
            releaseThreadAttempted = true
            try {
                if (!egl.releaseThread()) {
                    firstFailure = firstFailure ?: EglErrorException("eglReleaseThread", egl.getError())
                }
            } catch (failure: Exception) {
                firstFailure = firstFailure ?: failure
            }
        }
        return firstFailure
    }

    private fun failEgl(operation: String): Nothing {
        val error = egl.getError()
        val problem = if (error == EGL14.EGL_BAD_ALLOC && safePartialOwnership()) {
            ScreenCaptureProblem.ResourceExhausted
        } else {
            ScreenCaptureProblem.InternalFailure
        }
        throw CaptureBoundaryFailure(problem, EglErrorException(operation, error))
    }

    private fun safePartialOwnership(): Boolean =
        display !== EGL14.EGL_NO_DISPLAY &&
                (context == null || context !== EGL14.EGL_NO_CONTEXT) &&
                (pbuffer == null || pbuffer !== EGL14.EGL_NO_SURFACE)

    private fun failGl(phase: String, error: Int): Nothing {
        if (error == GLES20.GL_OUT_OF_MEMORY) {
            integrity = Integrity.PoisonedByOutOfMemory
            throw CaptureBoundaryFailure(
                ScreenCaptureProblem.ResourceExhausted,
                GlErrorException(phase, error),
            )
        }
        integrity = Integrity.Unknown
        throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, GlErrorException(phase, error))
    }

    private fun failInternal(message: String): Nothing {
        if (integrity == Integrity.Healthy) integrity = Integrity.Unknown
        throw CaptureBoundaryFailure(ScreenCaptureProblem.InternalFailure, CapturePhysicalException(message))
    }
}

internal interface EglPlatform {
    fun getDisplay(): EGLDisplay
    fun initialize(display: EGLDisplay, version: IntArray): Boolean
    fun chooseConfig(
        display: EGLDisplay,
        attributes: IntArray,
        configs: Array<EGLConfig?>,
        count: IntArray,
    ): Boolean

    fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext
    fun createPbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLSurface
    fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean
    fun currentContext(): EGLContext
    fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean
    fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean
    fun releaseThread(): Boolean
    fun getError(): Int
}

internal object AndroidEglPlatform : EglPlatform {
    override fun getDisplay(): EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

    override fun initialize(display: EGLDisplay, version: IntArray): Boolean =
        EGL14.eglInitialize(display, version, 0, version, 1)

    override fun chooseConfig(
        display: EGLDisplay,
        attributes: IntArray,
        configs: Array<EGLConfig?>,
        count: IntArray,
    ): Boolean = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)

    override fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext =
        EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attributes, 0)

    override fun createPbufferSurface(
        display: EGLDisplay,
        config: EGLConfig,
        attributes: IntArray,
    ): EGLSurface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)

    override fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    override fun currentContext(): EGLContext = EGL14.eglGetCurrentContext()
    override fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean =
        EGL14.eglDestroyContext(display, context)

    override fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean =
        EGL14.eglDestroySurface(display, surface)

    override fun releaseThread(): Boolean = EGL14.eglReleaseThread()
    override fun getError(): Int = EGL14.eglGetError()
}

internal interface GlesPlatform {
    fun getError(): Int
    fun getInteger(name: Int, values: IntArray)
    fun getShaderPrecisionFormat(range: IntArray, precision: IntArray)
    fun genTextures(names: IntArray)
    fun bindTexture(target: Int, texture: Int)
    fun texParameter(target: Int, name: Int, value: Int)
    fun texImage2d(width: Int, height: Int)
    fun deleteTextures(names: IntArray)
    fun genFramebuffers(names: IntArray)
    fun bindFramebuffer(framebuffer: Int)
    fun framebufferTexture2d(texture: Int)
    fun checkFramebufferStatus(): Int
    fun deleteFramebuffers(names: IntArray)
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun getShaderStatus(shader: Int, status: IntArray)
    fun deleteShader(shader: Int)
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun getProgramStatus(program: Int, status: IntArray)
    fun getUniformLocation(program: Int, name: String): Int
    fun detachShader(program: Int, shader: Int)
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    fun viewport(width: Int, height: Int)
    fun activeTexture(texture: Int)
    fun uniform1i(location: Int, value: Int)
    fun uniform1f(location: Int, value: Float)
    fun uniformMatrix4fv(location: Int, values: FloatArray)
    fun vertexAttribPointer(index: Int, values: Buffer)
    fun enableVertexAttribArray(index: Int)
    fun colorMask()
    fun packAlignmentOne()
    fun disable(capability: Int)
    fun drawTriangleStrip()
    fun readPixels(width: Int, height: Int, carrier: Buffer)
}

internal object AndroidGlesPlatform : GlesPlatform {
    override fun getError(): Int = GLES20.glGetError()
    override fun getInteger(name: Int, values: IntArray) = GLES20.glGetIntegerv(name, values, 0)
    override fun getShaderPrecisionFormat(range: IntArray, precision: IntArray) =
        GLES20.glGetShaderPrecisionFormat(
            GLES20.GL_FRAGMENT_SHADER,
            GLES20.GL_HIGH_FLOAT,
            range,
            0,
            precision,
            0,
        )

    override fun genTextures(names: IntArray) = GLES20.glGenTextures(1, names, 0)
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun texParameter(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun texImage2d(width: Int, height: Int) = GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D,
        0,
        GLES20.GL_RGBA,
        width,
        height,
        0,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        null,
    )

    override fun deleteTextures(names: IntArray) = GLES20.glDeleteTextures(1, names, 0)
    override fun genFramebuffers(names: IntArray) = GLES20.glGenFramebuffers(1, names, 0)
    override fun bindFramebuffer(framebuffer: Int) = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    override fun framebufferTexture2d(texture: Int) = GLES20.glFramebufferTexture2D(
        GLES20.GL_FRAMEBUFFER,
        GLES20.GL_COLOR_ATTACHMENT0,
        GLES20.GL_TEXTURE_2D,
        texture,
        0,
    )

    override fun checkFramebufferStatus(): Int = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
    override fun deleteFramebuffers(names: IntArray) = GLES20.glDeleteFramebuffers(1, names, 0)
    override fun createShader(type: Int): Int = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun getShaderStatus(shader: Int, status: IntArray) =
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)

    override fun deleteShader(shader: Int) = GLES20.glDeleteShader(shader)
    override fun createProgram(): Int = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) =
        GLES20.glBindAttribLocation(program, index, name)

    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun getProgramStatus(program: Int, status: IntArray) =
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)

    override fun getUniformLocation(program: Int, name: String): Int = GLES20.glGetUniformLocation(program, name)
    override fun detachShader(program: Int, shader: Int) = GLES20.glDetachShader(program, shader)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun viewport(width: Int, height: Int) = GLES20.glViewport(0, 0, width, height)
    override fun activeTexture(texture: Int) = GLES20.glActiveTexture(texture)
    override fun uniform1i(location: Int, value: Int) = GLES20.glUniform1i(location, value)
    override fun uniform1f(location: Int, value: Float) = GLES20.glUniform1f(location, value)
    override fun uniformMatrix4fv(location: Int, values: FloatArray) =
        GLES20.glUniformMatrix4fv(location, 1, false, values, 0)

    override fun vertexAttribPointer(index: Int, values: Buffer) =
        GLES20.glVertexAttribPointer(index, 2, GLES20.GL_FLOAT, false, 0, values)

    override fun enableVertexAttribArray(index: Int) = GLES20.glEnableVertexAttribArray(index)
    override fun colorMask() = GLES20.glColorMask(true, true, true, true)
    override fun packAlignmentOne() = GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
    override fun disable(capability: Int) = GLES20.glDisable(capability)
    override fun drawTriangleStrip() = GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    override fun readPixels(width: Int, height: Int, carrier: Buffer) = GLES20.glReadPixels(
        0,
        0,
        width,
        height,
        GLES20.GL_RGBA,
        GLES20.GL_UNSIGNED_BYTE,
        carrier,
    )
}
