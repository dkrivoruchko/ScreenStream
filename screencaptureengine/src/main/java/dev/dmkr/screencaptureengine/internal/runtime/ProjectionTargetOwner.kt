package dev.dmkr.screencaptureengine.internal.runtime

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * Owns generation-numbered projection targets backed by a GL external OES texture.
 *
 * Creation and GL teardown run on the owner GL thread. Each target owns its `SurfaceTexture`,
 * projection `Surface`, and OES texture until the target or owner is closed. The exposed surface is
 * the MediaProjection/VirtualDisplay input; GL rendering and readback remain separate runtime
 * ownership built on top of the target generation.
 */
internal class ProjectionTargetOwner internal constructor(
    threadName: String = "ScreenCaptureProjectionTarget",
) : ProjectionTargetOwnerHandle {
    private val stateLock = ReentrantLock()
    private val noActiveWork = stateLock.newCondition()
    private val handlerThread = HandlerThread(threadName, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val liveTargets = LinkedHashSet<ProjectionTarget>()
    private var eglState: EglState? = null
    private var isClosed = false
    private var activeCreations = 0
    private var activeReleases = 0
    private var nextGeneration = 1L

    override fun targetSizeLimits(): ProjectionTargetSizeLimits =
        runOnGlThread {
            check(!isClosedLocked()) { "ProjectionTargetOwner is closed." }
            ensureEglOnGlThread()
            queryTargetSizeLimitsOnGlThread()
        }

    override fun createTarget(width: Int, height: Int, densityDpi: Int): ProjectionTarget {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(densityDpi > 0) { "densityDpi must be positive, was $densityDpi" }

        val generation = stateLock.withLock {
            check(!isClosed) { "ProjectionTargetOwner is closed." }
            val value = nextGeneration
            nextGeneration = Math.addExact(value, 1L)
            activeCreations += 1
            value
        }

        try {
            val target = runOnGlThread {
                check(!isClosedLocked()) { "ProjectionTargetOwner is closed." }
                createTargetOnGlThread(generation = generation, width = width, height = height, densityDpi = densityDpi)
            }
            var shouldReleaseCreatedTarget: ProjectionTarget? = null
            stateLock.withLock {
                if (isClosed) {
                    shouldReleaseCreatedTarget = target
                    check(target.markClosedForOwner())
                } else {
                    liveTargets.add(target)
                }
            }
            shouldReleaseCreatedTarget?.let { createdTarget ->
                createdTarget.releaseOwnedResources()
                throw IllegalStateException("ProjectionTargetOwner is closed.")
            }
            return target
        } finally {
            stateLock.withLock {
                activeCreations -= 1
                noActiveWork.signalAll()
            }
        }
    }

    override fun close() {
        check(Thread.currentThread() != handlerThread.looper.thread) { "ProjectionTargetOwner.close must not be called from its GL thread." }
        val targetsToRelease = stateLock.withLock {
            if (isClosed) return
            isClosed = true
            while ((activeCreations > 0) || (activeReleases > 0)) {
                noActiveWork.awaitUninterruptibly()
            }
            liveTargets.filter(ProjectionTarget::markClosedForOwner).also { targets ->
                liveTargets.clear()
                activeReleases += targets.size
            }
        }
        val cleanupFailures = CleanupFailureCollector()
        try {
            targetsToRelease.forEach { target ->
                cleanupFailures.collect(target::releaseOwnedResources)
            }
        } finally {
            stateLock.withLock {
                activeReleases -= targetsToRelease.size
                noActiveWork.signalAll()
            }
        }
        cleanupFailures.collect { runOnGlThread { releaseEglOnGlThread() } }
        cleanupFailures.collect {
            handlerThread.quitSafely()
            if (Thread.currentThread() != handlerThread.looper.thread) {
                handlerThread.join()
            }
        }
        cleanupFailures.throwIfAny()
    }

    private fun createTargetOnGlThread(generation: Long, width: Int, height: Int, densityDpi: Int): ProjectionTarget {
        ensureEglOnGlThread()
        validateTargetSizeOnGlThread(width, height)
        val textureId = createExternalOesTexture()
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        try {
            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(width, height)
            }
            surface = Surface(surfaceTexture)
            check(surface.isValid) { "Projection target Surface is not valid." }
            return ProjectionTarget(
                generation = generation,
                width = width,
                height = height,
                densityDpi = densityDpi,
                androidSurface = surface,
                owner = this,
                surfaceTexture = surfaceTexture,
                textureId = textureId,
            )
        } catch (cause: Throwable) {
            surface?.release()
            surfaceTexture?.let { releaseSurfaceTextureAndTextureOnGlThread(it, textureId) } ?: deleteTextureOnGlThread(textureId)
            throw cause
        }
    }

    private fun ensureEglOnGlThread() {
        eglState?.let { state ->
            makeCurrent(state)
            return
        }

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay returned EGL_NO_DISPLAY." }
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throwEglFailure("eglInitialize")
            }
            initialized = true
            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                throwEglFailure("eglBindAPI")
            }

            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val eglConfigAvailable = EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, configs.size, configCount, 0)
            if (!eglConfigAvailable || (configCount[0] <= 0)) {
                throwEglFailure("eglChooseConfig")
            }
            val config = checkNotNull(configs[0]) { "eglChooseConfig returned a null config." }

            val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                throwEglFailure("eglCreateContext")
            }

            val surfaceAttributes = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                throwEglFailure("eglCreatePbufferSurface")
            }

            val state = EglState(display = display, context = context, surface = surface)
            makeCurrent(state)
            eglState = state
        } catch (cause: Throwable) {
            if (surface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, surface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            if (initialized) {
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            throw cause
        }
    }

    private fun createExternalOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        checkGl("glGenTextures")
        val textureId = textures[0]
        check(textureId != 0) { "glGenTextures returned 0." }

        try {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            checkGl("glBindTexture")
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            checkGl("configure external OES texture")
        } catch (cause: Throwable) {
            deleteTextureOnGlThread(textureId)
            throw cause
        }
        return textureId
    }

    private fun validateTargetSizeOnGlThread(width: Int, height: Int) {
        val limits = queryTargetSizeLimitsOnGlThread()
        check((width <= limits.maxWidth) && (height <= limits.maxHeight)) {
            "Projection target ${width}x$height exceeds GL target size limits ${limits.maxWidth}x${limits.maxHeight}."
        }
    }

    private fun queryTargetSizeLimitsOnGlThread(): ProjectionTargetSizeLimits {
        val textureSize = IntArray(1)
        val viewportDims = IntArray(2)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureSize, 0)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, viewportDims, 0)
        checkGl("query GL target size limits")
        val maxWidth = min(textureSize[0], viewportDims[0])
        val maxHeight = min(textureSize[0], viewportDims[1])
        check((maxWidth > 0) && (maxHeight > 0)) { "GL returned invalid target size limits." }
        return ProjectionTargetSizeLimits(maxWidth = maxWidth, maxHeight = maxHeight)
    }

    private fun closeTarget(target: ProjectionTarget, surface: Surface, surfaceTexture: SurfaceTexture, textureId: Int) {
        stateLock.withLock {
            if (!target.markClosedForOwner()) return
            if (!liveTargets.remove(target)) return
            activeReleases += 1
        }
        try {
            releaseTargetResources(surface, surfaceTexture, textureId)
        } finally {
            stateLock.withLock {
                activeReleases -= 1
                noActiveWork.signalAll()
            }
        }
    }

    private fun releaseTargetResources(surface: Surface, surfaceTexture: SurfaceTexture, textureId: Int) {
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collect {
            runOnGlThread {
                clearSurfaceTextureFrameListenerOnGlThread(surfaceTexture)
            }
        }
        cleanupFailures.collect { surface.release() }
        cleanupFailures.collect {
            runOnGlThread {
                releaseSurfaceTextureAndTextureOnGlThread(surfaceTexture, textureId)
            }
        }
        cleanupFailures.throwIfAny()
    }

    private fun clearSurfaceTextureFrameListenerOnGlThread(surfaceTexture: SurfaceTexture) {
        runCatching { surfaceTexture.setOnFrameAvailableListener(null) }
    }

    private fun releaseSurfaceTextureAndTextureOnGlThread(surfaceTexture: SurfaceTexture, textureId: Int) {
        runCatching { surfaceTexture.release() }
        deleteTextureOnGlThread(textureId)
    }

    private fun deleteTextureOnGlThread(textureId: Int) {
        if ((textureId == 0) || (eglState == null)) return
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun releaseEglOnGlThread() {
        val state = eglState ?: return
        EGL14.eglMakeCurrent(state.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(state.display, state.surface)
        EGL14.eglDestroyContext(state.display, state.context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(state.display)
        eglState = null
    }

    private fun makeCurrent(state: EglState) {
        if (!EGL14.eglMakeCurrent(state.display, state.surface, state.surface, state.context)) {
            throwEglFailure("eglMakeCurrent")
        }
    }

    private fun isClosedLocked(): Boolean = stateLock.withLock { isClosed }

    private fun <T> runOnGlThread(block: () -> T): T {
        if (Thread.currentThread() == handlerThread.looper.thread) {
            return block()
        }

        val latch = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        if (!handler.post {
                try {
                    value = block()
                } catch (cause: Throwable) {
                    failure = cause
                } finally {
                    latch.countDown()
                }
            }) {
            throw IllegalStateException("GL thread is not accepting work.")
        }
        try {
            latch.await()
        } catch (cause: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for GL thread.", cause)
        }
        failure?.let { cause -> throw cause }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun throwEglFailure(operation: String): Nothing {
        throw IllegalStateException("$operation failed with EGL error 0x${Integer.toHexString(EGL14.eglGetError())}.")
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation failed with GL error 0x${Integer.toHexString(error)}." }
    }

    private data class EglState(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface,
    )

    internal class ProjectionTarget internal constructor(
        override val generation: Long,
        override val width: Int,
        override val height: Int,
        override val densityDpi: Int,
        private val androidSurface: Surface,
        private val owner: ProjectionTargetOwner,
        private val surfaceTexture: SurfaceTexture,
        private val textureId: Int,
    ) : ProjectionTargetHandle {
        private val isClosed = AtomicBoolean()
        override val surface: ProjectionSurfaceHandle = AndroidProjectionSurfaceHandle(androidSurface)

        override fun close() {
            owner.closeTarget(target = this, surface = androidSurface, surfaceTexture = surfaceTexture, textureId = textureId)
        }

        internal fun markClosedForOwner(): Boolean = isClosed.compareAndSet(false, true)

        internal fun releaseOwnedResources() {
            owner.releaseTargetResources(surface = androidSurface, surfaceTexture = surfaceTexture, textureId = textureId)
        }
    }
}
