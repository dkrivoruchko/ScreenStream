package io.screenstream.capture.internal.capture

import android.opengl.GLES20
import java.nio.Buffer

internal interface GlesPlatform {
    fun getError(): Int

    fun getInteger(name: Int, values: IntArray)

    fun getShaderPrecisionFormat(range: IntArray, precision: IntArray)

    fun genTextures(names: IntArray)

    fun bindTexture(target: Int, texture: Int)

    fun texParameter(target: Int, name: Int, value: Int)

    fun texImage2D(width: Int, height: Int)

    fun deleteTextures(names: IntArray)

    fun genFramebuffers(names: IntArray)

    fun bindFramebuffer(framebuffer: Int)

    fun framebufferTexture2D(texture: Int)

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
        GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)

    override fun genTextures(names: IntArray) = GLES20.glGenTextures(1, names, 0)
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun texParameter(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun texImage2D(width: Int, height: Int) = GLES20.glTexImage2D(
        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
    )

    override fun deleteTextures(names: IntArray) = GLES20.glDeleteTextures(1, names, 0)
    override fun genFramebuffers(names: IntArray) = GLES20.glGenFramebuffers(1, names, 0)
    override fun bindFramebuffer(framebuffer: Int) = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    override fun framebufferTexture2D(texture: Int) =
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)

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
    override fun readPixels(width: Int, height: Int, carrier: Buffer) =
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, carrier)
}
