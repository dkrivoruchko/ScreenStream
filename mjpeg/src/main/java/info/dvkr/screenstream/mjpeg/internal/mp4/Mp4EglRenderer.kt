package info.dvkr.screenstream.mjpeg.internal.mp4

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import com.elvishew.xlog.XLog
import info.dvkr.screenstream.common.getLog
import info.dvkr.screenstream.mjpeg.internal.audio.MjpegMasterClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis

internal class Mp4EglRenderer(
    private val width: Int,
    private val height: Int,
    private val encoderSurface: Surface,
    private val onError: (Throwable) -> Unit
) {
    private companion object {
        private const val RECORDABLE_ANDROID = 0x3142

        private val MVP_MATRIX = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
            Matrix.scaleM(this, 0, 1f, -1f, 1f)
        }

        private val FULL_RECT_VERTICES_BUFFER: FloatBuffer =
            ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f))
                    position(0)
                }

        private val FULL_RECT_TEX_BUFFER: FloatBuffer =
            ByteBuffer.allocateDirect(8 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(floatArrayOf(0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f))
                    position(0)
                }

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }

    private val isRendering = AtomicBoolean(false)
    private val errorOccurred = AtomicBoolean(false)
    private val handlerThread: HandlerThread = HandlerThread("Mp4EglRendererThread", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler: Handler = Handler(handlerThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    @Volatile private var surfaceTexture: SurfaceTexture? = null
    private var textureId = -1
    private var programId = 0

    private var uMVPMatrixLoc = -1
    private var uSTMatrixLoc = -1
    private var aPositionHandle = -1
    private var aTextureCoordHandle = -1

    private val stMatrix = FloatArray(16)
    private val renderFrameTask = Runnable { renderFrame() }
    private var fps = 30
    private var frameIntervalMs = 1000.0 / fps
    private var nextRenderTimeMs = 0.0

    internal val inputSurfaceTexture: SurfaceTexture
        get() = surfaceTexture ?: throw IllegalStateException("SurfaceTexture is not initialized.")

    init {
        val latch = CountDownLatch(1)
        handler.post {
            runSafely {
                eglSetup(encoderSurface)
                createGLObjects()
            }
            latch.countDown()
        }
        latch.await()
    }

    internal fun startAsync() {
        if (isRendering.compareAndSet(false, true)) handler.post(renderFrameTask)
    }

    internal fun stop() {
        isRendering.set(false)
        val isHandlerThread = Thread.currentThread() == handlerThread.looper?.thread
        if (isHandlerThread) {
            stopOnHandlerThread()
        } else {
            val latch = CountDownLatch(1)
            val posted = handler.post {
                runCatching { stopOnHandlerThread() }
                    .onFailure { XLog.e(getLog("stop", "Failed to stop on handler thread."), it) }
                latch.countDown()
            }
            if (posted) latch.await()
        }

        runCatching { encoderSurface.release() }.onFailure { XLog.e(getLog("stop", "Failed to release encoder surface."), it) }
    }

    internal fun setFps(fps: Int) {
        handler.post {
            this.fps = fps.coerceAtLeast(1)
            frameIntervalMs = 1000.0 / this.fps
        }
    }

    private fun stopOnHandlerThread() {
        handler.removeCallbacksAndMessages(null)
        val surfaceTextureToRelease = surfaceTexture
        surfaceTextureToRelease?.setOnFrameAvailableListener(null)
        surfaceTexture = null
        runCatching { releaseGL(surfaceTextureToRelease) }.onFailure { XLog.e(getLog("stop", "Failed to release GL resources."), it) }
        handlerThread.quitSafely()
    }

    private inline fun runSafely(block: () -> Unit) {
        if (errorOccurred.get()) return
        runCatching { block() }.onFailure { cause ->
            if (errorOccurred.compareAndSet(false, true)) {
                XLog.w(getLog("runSafely", cause.message), cause)
                isRendering.set(false)
                handler.removeCallbacksAndMessages(null)
                onError(cause)
            }
        }
    }

    private fun renderFrame() {
        runSafely {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw RuntimeException("eglMakeCurrent failed: error 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
            surfaceTexture?.apply {
                updateTexImage()
                getTransformMatrix(stMatrix)
            }
        }

        if (!isRendering.get()) return

        val nowMs = MjpegMasterClock.relativeTimeMs().toDouble()
        if (nextRenderTimeMs == 0.0) nextRenderTimeMs = nowMs
        nextRenderTimeMs += frameIntervalMs

        val renderTime = measureTimeMillis { runSafely { drawFrame() } }.toDouble()
        if (!isRendering.get()) return

        handler.removeCallbacks(renderFrameTask)
        handler.postDelayed(renderFrameTask, (nextRenderTimeMs - nowMs + renderTime).coerceAtLeast(0.0).roundToLong())
    }

    private fun drawFrame() {
        check(Thread.currentThread() == handlerThread.looper?.thread) { "All GL calls must be on Mp4EglRenderer HandlerThread." }
        if (!isRendering.get()) return

        GLES20.glUseProgram(programId)
        checkGlError("glUseProgram")

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, MVP_MATRIX, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, FULL_RECT_VERTICES_BUFFER)

        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, FULL_RECT_TEX_BUFFER)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")

        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw RuntimeException("eglSwapBuffers failed: error 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun eglSetup(encoderSurface: Surface) {
        check(Thread.currentThread() == handlerThread.looper?.thread) { "All GL calls must be on Mp4EglRenderer HandlerThread." }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("Unable to get EGL14 display")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw RuntimeException("Unable to initialize EGL14")

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        checkEglError("eglChooseConfig")
        if (numConfigs[0] <= 0) throw RuntimeException("No EGL configs found")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        checkEglError("eglCreateContext")

        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw RuntimeException("eglCreateWindowSurface returned EGL_NO_SURFACE.")

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed: error 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun createGLObjects() {
        check(Thread.currentThread() == handlerThread.looper?.thread) { "All GL calls must be on Mp4EglRenderer HandlerThread." }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }
        Matrix.setIdentityM(stMatrix, 0)
        programId = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixLoc = GLES20.glGetUniformLocation(programId, "uSTMatrix")

        GLES20.glViewport(0, 0, width, height)
    }

    private fun releaseGL(surfaceTextureToRelease: SurfaceTexture?) {
        check(Thread.currentThread() == handlerThread.looper?.thread) { "All GL calls must be on Mp4EglRenderer HandlerThread." }

        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return

        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (programId != 0) GLES20.glDeleteProgram(programId)
            if (textureId != -1) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            programId = 0
            textureId = -1
        }

        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY

        surfaceTextureToRelease?.release()
        surfaceTexture = null
    }

    private fun checkEglError(msg: String) {
        val error = EGL14.eglGetError()
        if (error != EGL14.EGL_SUCCESS) throw RuntimeException("$msg. EGL error: 0x${Integer.toHexString(error)}")
    }

    private fun checkGlError(op: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw RuntimeException("$op: glError $error")
    }

    private fun createGlProgram(vsSource: String, fsSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)

        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val infoLog = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Could not link program: $infoLog")
        }

        GLES20.glDetachShader(program, vertexShader)
        GLES20.glDetachShader(program, fragmentShader)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Could not compile shader $type: $infoLog")
        }
        return shader
    }
}
