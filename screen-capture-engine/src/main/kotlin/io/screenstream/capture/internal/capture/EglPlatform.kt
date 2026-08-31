package io.screenstream.capture.internal.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface

internal interface EglPlatform {
    val currentDisplay: EGLDisplay

    val currentContext: EGLContext

    val currentReadSurface: EGLSurface

    val currentDrawSurface: EGLSurface

    fun getDisplay(): EGLDisplay

    fun initialize(display: EGLDisplay, version: IntArray): Boolean

    fun chooseConfig(display: EGLDisplay, attributes: IntArray, configs: Array<EGLConfig?>, count: IntArray): Boolean

    fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext

    fun createPbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLSurface

    fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean

    fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean

    fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean

    fun releaseThread(): Boolean

    fun getError(): Int
}

internal object AndroidEglPlatform : EglPlatform {
    override val currentDisplay: EGLDisplay
        get() = EGL14.eglGetCurrentDisplay()
    override val currentContext: EGLContext
        get() = EGL14.eglGetCurrentContext()
    override val currentReadSurface: EGLSurface
        get() = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
    override val currentDrawSurface: EGLSurface
        get() = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)

    override fun getDisplay(): EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)

    override fun initialize(display: EGLDisplay, version: IntArray): Boolean =
        EGL14.eglInitialize(display, version, 0, version, 1)

    override fun chooseConfig(display: EGLDisplay, attributes: IntArray, configs: Array<EGLConfig?>, count: IntArray): Boolean =
        EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)

    override fun createContext(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLContext =
        EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, attributes, 0)

    override fun createPbufferSurface(display: EGLDisplay, config: EGLConfig, attributes: IntArray): EGLSurface =
        EGL14.eglCreatePbufferSurface(display, config, attributes, 0)

    override fun makeCurrent(display: EGLDisplay, surface: EGLSurface, context: EGLContext): Boolean =
        EGL14.eglMakeCurrent(display, surface, surface, context)

    override fun destroyContext(display: EGLDisplay, context: EGLContext): Boolean =
        EGL14.eglDestroyContext(display, context)

    override fun destroySurface(display: EGLDisplay, surface: EGLSurface): Boolean =
        EGL14.eglDestroySurface(display, surface)

    override fun releaseThread(): Boolean = EGL14.eglReleaseThread()
    override fun getError(): Int = EGL14.eglGetError()
}
