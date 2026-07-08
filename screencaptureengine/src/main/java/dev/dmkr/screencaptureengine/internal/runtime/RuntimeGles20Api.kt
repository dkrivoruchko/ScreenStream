package dev.dmkr.screencaptureengine.internal.runtime

import android.opengl.GLES20
import java.nio.Buffer

/**
 * Runtime-only GLES20 seam for ES2 draw/readback work.
 *
 * Readiness preparation uses [Gles20Api]. This seam is intentionally separate so production-only
 * operations such as drawing and pixel readback do not leak into startup validation APIs.
 */
internal interface RuntimeGles20Api {
    fun getIntegerv(pname: Int, params: IntArray, offset: Int)

    fun activeTexture(texture: Int)

    fun bindTexture(target: Int, texture: Int)

    fun bindBuffer(target: Int, buffer: Int)

    fun useProgram(program: Int)

    fun bindFramebuffer(target: Int, framebuffer: Int)

    fun viewport(x: Int, y: Int, width: Int, height: Int)

    fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer)

    fun getVertexAttribiv(index: Int, pname: Int, params: IntArray, offset: Int)

    fun enableVertexAttribArray(index: Int)

    fun disableVertexAttribArray(index: Int)

    fun uniform1i(location: Int, value: Int)

    fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int)

    fun pixelStorei(pname: Int, param: Int)

    fun drawArrays(mode: Int, first: Int, count: Int)

    fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer)
}

internal object AndroidRuntimeGles20Api : RuntimeGles20Api {
    override fun getIntegerv(pname: Int, params: IntArray, offset: Int) {
        GLES20.glGetIntegerv(pname, params, offset)
    }

    override fun activeTexture(texture: Int) {
        GLES20.glActiveTexture(texture)
    }

    override fun bindTexture(target: Int, texture: Int) {
        GLES20.glBindTexture(target, texture)
    }

    override fun bindBuffer(target: Int, buffer: Int) {
        GLES20.glBindBuffer(target, buffer)
    }

    override fun useProgram(program: Int) {
        GLES20.glUseProgram(program)
    }

    override fun bindFramebuffer(target: Int, framebuffer: Int) {
        GLES20.glBindFramebuffer(target, framebuffer)
    }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {
        GLES20.glViewport(x, y, width, height)
    }

    override fun vertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, pointer: Buffer) {
        GLES20.glVertexAttribPointer(index, size, type, normalized, stride, pointer)
    }

    override fun getVertexAttribiv(index: Int, pname: Int, params: IntArray, offset: Int) {
        GLES20.glGetVertexAttribiv(index, pname, params, offset)
    }

    override fun enableVertexAttribArray(index: Int) {
        GLES20.glEnableVertexAttribArray(index)
    }

    override fun disableVertexAttribArray(index: Int) {
        GLES20.glDisableVertexAttribArray(index)
    }

    override fun uniform1i(location: Int, value: Int) {
        GLES20.glUniform1i(location, value)
    }

    override fun uniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        GLES20.glUniformMatrix4fv(location, count, transpose, value, offset)
    }

    override fun pixelStorei(pname: Int, param: Int) {
        GLES20.glPixelStorei(pname, param)
    }

    override fun drawArrays(mode: Int, first: Int, count: Int) {
        GLES20.glDrawArrays(mode, first, count)
    }

    override fun readPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: Buffer) {
        GLES20.glReadPixels(x, y, width, height, format, type, pixels)
    }
}
