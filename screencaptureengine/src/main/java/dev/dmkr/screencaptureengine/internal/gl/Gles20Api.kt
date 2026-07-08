package dev.dmkr.screencaptureengine.internal.gl

import android.opengl.GLES20
import java.nio.Buffer

/**
 * Narrow fakeable GLES20 seam used by ES2 readiness resource preparation.
 *
 * It covers shader/program validation, output texture/framebuffer setup, and GL retirement only.
 * It intentionally excludes projection-frame consumption, pixel readback, encoding, and publication.
 */
internal interface Gles20Api {
    fun getIntegerv(pname: Int, params: IntArray, offset: Int)

    fun getBooleanv(pname: Int, params: BooleanArray, offset: Int)

    fun activeTexture(texture: Int)

    fun createShader(type: Int): Int

    fun shaderSource(shader: Int, source: String)

    fun compileShader(shader: Int)

    fun getShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int)

    fun getShaderInfoLog(shader: Int): String

    fun createProgram(): Int

    fun attachShader(program: Int, shader: Int)

    fun linkProgram(program: Int)

    fun validateProgram(program: Int)

    fun getProgramiv(program: Int, pname: Int, params: IntArray, offset: Int)

    fun getProgramInfoLog(program: Int): String

    fun getAttribLocation(program: Int, name: String): Int

    fun getUniformLocation(program: Int, name: String): Int

    fun genTextures(n: Int, textures: IntArray, offset: Int)

    fun bindTexture(target: Int, texture: Int)

    fun texParameteri(target: Int, pname: Int, param: Int)

    fun texImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Buffer?,
    )

    fun genFramebuffers(n: Int, framebuffers: IntArray, offset: Int)

    fun bindFramebuffer(target: Int, framebuffer: Int)

    fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int)

    fun checkFramebufferStatus(target: Int): Int

    fun pixelStorei(pname: Int, param: Int)

    fun deleteTexture(textureId: Int)

    fun deleteFramebuffer(framebufferId: Int)

    fun deleteRenderbuffer(renderbufferId: Int)

    fun deleteProgram(programId: Int)

    fun deleteShader(shaderId: Int)
}

internal object AndroidGles20Api : Gles20Api {
    override fun getIntegerv(pname: Int, params: IntArray, offset: Int) {
        GLES20.glGetIntegerv(pname, params, offset)
    }

    override fun getBooleanv(pname: Int, params: BooleanArray, offset: Int) {
        GLES20.glGetBooleanv(pname, params, offset)
    }

    override fun activeTexture(texture: Int) {
        GLES20.glActiveTexture(texture)
    }

    override fun createShader(type: Int): Int =
        GLES20.glCreateShader(type)

    override fun shaderSource(shader: Int, source: String) {
        GLES20.glShaderSource(shader, source)
    }

    override fun compileShader(shader: Int) {
        GLES20.glCompileShader(shader)
    }

    override fun getShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {
        GLES20.glGetShaderiv(shader, pname, params, offset)
    }

    override fun getShaderInfoLog(shader: Int): String =
        GLES20.glGetShaderInfoLog(shader).orEmpty()

    override fun createProgram(): Int =
        GLES20.glCreateProgram()

    override fun attachShader(program: Int, shader: Int) {
        GLES20.glAttachShader(program, shader)
    }

    override fun linkProgram(program: Int) {
        GLES20.glLinkProgram(program)
    }

    override fun validateProgram(program: Int) {
        GLES20.glValidateProgram(program)
    }

    override fun getProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
        GLES20.glGetProgramiv(program, pname, params, offset)
    }

    override fun getProgramInfoLog(program: Int): String =
        GLES20.glGetProgramInfoLog(program).orEmpty()

    override fun getAttribLocation(program: Int, name: String): Int =
        GLES20.glGetAttribLocation(program, name)

    override fun getUniformLocation(program: Int, name: String): Int =
        GLES20.glGetUniformLocation(program, name)

    override fun genTextures(n: Int, textures: IntArray, offset: Int) {
        GLES20.glGenTextures(n, textures, offset)
    }

    override fun bindTexture(target: Int, texture: Int) {
        GLES20.glBindTexture(target, texture)
    }

    override fun texParameteri(target: Int, pname: Int, param: Int) {
        GLES20.glTexParameteri(target, pname, param)
    }

    override fun texImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: Buffer?,
    ) {
        GLES20.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels)
    }

    override fun genFramebuffers(n: Int, framebuffers: IntArray, offset: Int) {
        GLES20.glGenFramebuffers(n, framebuffers, offset)
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(target, framebuffer)
    }

    override fun framebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int) {
        GLES20.glFramebufferTexture2D(target, attachment, textarget, texture, level)
    }

    override fun checkFramebufferStatus(target: Int): Int =
        GLES20.glCheckFramebufferStatus(target)

    override fun pixelStorei(pname: Int, param: Int) {
        GLES20.glPixelStorei(pname, param)
    }

    override fun deleteTexture(textureId: Int) {
        if (textureId == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    override fun deleteFramebuffer(framebufferId: Int) {
        if (framebufferId == 0) return
        GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
    }

    override fun deleteRenderbuffer(renderbufferId: Int) {
        if (renderbufferId == 0) return
        GLES20.glDeleteRenderbuffers(1, intArrayOf(renderbufferId), 0)
    }

    override fun deleteProgram(programId: Int) {
        if (programId == 0) return
        GLES20.glDeleteProgram(programId)
    }

    override fun deleteShader(shaderId: Int) {
        if (shaderId == 0) return
        GLES20.glDeleteShader(shaderId)
    }
}
