package io.screenstream.capture.internal.capture;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;

final class EglSentinelAccess {
    private EglSentinelAccess() {
    }

    static EGLDisplay noDisplay() {
        return EGL14.EGL_NO_DISPLAY;
    }

    static EGLContext noContext() {
        return EGL14.EGL_NO_CONTEXT;
    }

    static EGLSurface noSurface() {
        return EGL14.EGL_NO_SURFACE;
    }

    static void setNoDisplay(EGLDisplay value) {
        EGL14.EGL_NO_DISPLAY = value;
    }

    static void setNoContext(EGLContext value) {
        EGL14.EGL_NO_CONTEXT = value;
    }

    static void setNoSurface(EGLSurface value) {
        EGL14.EGL_NO_SURFACE = value;
    }
}
