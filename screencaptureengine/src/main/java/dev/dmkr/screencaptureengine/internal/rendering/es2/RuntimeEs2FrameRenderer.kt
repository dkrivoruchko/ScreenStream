package dev.dmkr.screencaptureengine.internal.rendering.es2

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.dmkr.screencaptureengine.internal.gl.AndroidRuntimeGles20Api
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.RuntimeGles20Api
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTargetIdentity
import dev.dmkr.screencaptureengine.internal.target.RuntimeProjectionTextureFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Consumes one already-admitted runtime OES frame, renders it through the first-plan ES2 program,
 * and returns a lease over the top-to-bottom RGBA readback buffer for encoding.
 *
 * The renderer never owns frame scheduling or drop counters. If the single readback lease is already
 * borrowed, it reports [RuntimeEs2RenderReadbackResult.ReadbackBusy] so the materialized attempt can
 * be accounted by the session core exactly once.
 */
internal class RuntimeEs2FrameRenderer internal constructor(
    private val gles: RuntimeGles20Api = AndroidRuntimeGles20Api,
) {
    private val savedGlStateScratch = RuntimeEs2SavedGlStateScratch()
    private val savedGlState = RuntimeEs2SavedGlState()

    fun renderReadback(request: RuntimeEs2RenderReadbackRequest): RuntimeEs2RenderReadbackResult {
        request.gl.checkCurrentContext("runtime ES2 frame render/readback")
        validateRequest(request)

        val lease = request.resources.tryAcquireRgbaReadbackLease()
            ?: return RuntimeEs2RenderReadbackResult.ReadbackBusy
        var primaryFailure: Throwable? = null
        try {
            savedGlState.save(
                gl = request.gl,
                gles = gles,
                binding = request.transformPackage.programBinding,
                scratch = savedGlStateScratch,
            )
        } catch (cause: Throwable) {
            lease.close()
            throw cause
        }

        try {
            renderAndReadback(request = request, lease = lease)
            return RuntimeEs2RenderReadbackResult.Success(
                lease = lease,
                sourceTimestampNanos = request.frame.timestampNanos,
            )
        } catch (cause: Throwable) {
            primaryFailure = cause
            lease.close()
            throw cause
        } finally {
            val restoreFailure = savedGlState.restore(gl = request.gl, gles = gles)
            if (restoreFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure)
                } else {
                    lease.close()
                    throw restoreFailure
                }
            }
        }
    }

    private fun renderAndReadback(
        request: RuntimeEs2RenderReadbackRequest,
        lease: RgbaReadbackLease,
    ) {
        val transformPackage = request.transformPackage
        val binding = transformPackage.programBinding
        composeRuntimeOesMatrix(
            runtimeOesMatrix = request.frame.transformMatrix,
            staticPlanMatrix = transformPackage.sourceTransformMatrix.values,
            rule = transformPackage.oesCompositionRule,
            destination = request.composedTextureMatrixScratch,
        )
        POSITION_VERTICES.position(0)
        TEXTURE_COORDINATES.position(0)

        gles.activeTexture(RUNTIME_ES2_TEXTURE_UNIT)
        gles.bindTexture(request.frame.externalOesTexture.textureTarget, request.frame.externalOesTexture.textureId)
        gles.useProgram(binding.programId)
        gles.bindFramebuffer(GLES20.GL_FRAMEBUFFER, request.resources.glObjects.outputFramebufferId)
        gles.viewport(
            transformPackage.outputViewport.x,
            transformPackage.outputViewport.y,
            transformPackage.outputViewport.width,
            transformPackage.outputViewport.height,
        )
        gles.bindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        gles.vertexAttribPointer(
            binding.positionAttributeLocation,
            FLOATS_PER_POSITION_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            POSITION_VERTICES,
        )
        gles.enableVertexAttribArray(binding.positionAttributeLocation)
        gles.vertexAttribPointer(
            binding.textureCoordinateAttributeLocation,
            FLOATS_PER_TEXTURE_VERTEX,
            GLES20.GL_FLOAT,
            false,
            0,
            TEXTURE_COORDINATES,
        )
        gles.enableVertexAttribArray(binding.textureCoordinateAttributeLocation)
        gles.uniform1i(binding.textureSamplerUniformLocation, RUNTIME_ES2_TEXTURE_UNIT_INDEX)
        gles.uniformMatrix4fv(
            binding.textureMatrixUniformLocation,
            1,
            false,
            request.composedTextureMatrixScratch,
            0,
        )
        gles.pixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        gles.drawArrays(GLES20.GL_TRIANGLE_STRIP, 0, TRIANGLE_STRIP_VERTEX_COUNT)
        gles.readPixels(
            0,
            0,
            transformPackage.readbackShape.width,
            transformPackage.readbackShape.height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            lease.mutableReadbackBuffer(),
        )
        request.gl.checkGl("runtime ES2 frame render/readback")
    }

    private fun validateRequest(request: RuntimeEs2RenderReadbackRequest) {
        val transformPackage = request.transformPackage
        require(transformPackage.projectionTargetGeneration == request.projectionTargetGeneration) {
            "Runtime ES2 request generation does not match first-plan transform package."
        }
        require(request.frame.projectionTargetGeneration == request.projectionTargetGeneration) {
            "Runtime ES2 frame generation does not match render request."
        }
        require(request.frame.projectionTargetIdentity == request.projectionTargetIdentity) {
            "Runtime ES2 frame identity does not match render request."
        }
        require(request.frame.projectionTargetGeneration == transformPackage.projectionTargetGeneration) {
            "Runtime ES2 frame generation does not match first-plan transform package."
        }
        require(transformPackage.programBinding.programId == request.resources.glObjects.programId) {
            "Runtime ES2 request program does not match prepared ES2 resources."
        }
        require(transformPackage.readbackShape.width == request.resources.width) {
            "Runtime ES2 request width does not match prepared readback resources."
        }
        require(transformPackage.readbackShape.height == request.resources.height) {
            "Runtime ES2 request height does not match prepared readback resources."
        }
        require(transformPackage.readbackShape.rowStrideBytes == request.resources.rowStrideBytes) {
            "Runtime ES2 request row stride does not match prepared readback resources."
        }
        require(transformPackage.readbackShape.byteCount == request.resources.readbackByteCount.toLong()) {
            "Runtime ES2 request byte count does not match prepared readback resources."
        }
        require(request.frame.transformMatrix.size == MATRIX_4_VALUE_COUNT) {
            "Runtime OES matrix must contain 16 values."
        }
    }
}

internal class RuntimeEs2RenderReadbackRequest internal constructor(
    internal val gl: GlLaneScope,
    internal val projectionTargetGeneration: Long,
    internal val projectionTargetIdentity: RuntimeProjectionTargetIdentity,
    internal val frame: RuntimeProjectionTextureFrame,
    internal val resources: PreparedEs2RenderingReadbackResources,
    internal val transformPackage: FirstPlanRenderTransformPackage,
    internal val composedTextureMatrixScratch: FloatArray = FloatArray(MATRIX_4_VALUE_COUNT),
) {
    init {
        require(projectionTargetGeneration > 0L) { "projectionTargetGeneration must be positive." }
        require(projectionTargetIdentity.generation == projectionTargetGeneration) {
            "projectionTargetIdentity generation must match projectionTargetGeneration."
        }
        require(composedTextureMatrixScratch.size == MATRIX_4_VALUE_COUNT) {
            "composedTextureMatrixScratch must contain 16 values."
        }
    }
}

internal sealed class RuntimeEs2RenderReadbackResult private constructor() {
    internal class Success internal constructor(
        internal val lease: RgbaReadbackLease,
        internal val sourceTimestampNanos: Long,
    ) : RuntimeEs2RenderReadbackResult()

    internal object ReadbackBusy : RuntimeEs2RenderReadbackResult()
}

private class RuntimeEs2SavedGlState {
    private var activeTexture: Int = GLES20.GL_TEXTURE0
    private var texture2dBinding: Int = 0
    private var externalOesBinding: Int = 0
    private var arrayBufferBinding: Int = 0
    private var framebufferBinding: Int = 0
    private var programBinding: Int = 0
    private var viewportX: Int = 0
    private var viewportY: Int = 0
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var packAlignment: Int = 4
    private var positionAttributeIndex: Int = 0
    private var textureCoordinateAttributeIndex: Int = 0

    fun restore(gl: GlLaneScope, gles: RuntimeGles20Api): Throwable? {
        var failure: Throwable? = null
        failure = collectRestoreFailure(failure) { gles.activeTexture(RUNTIME_ES2_TEXTURE_UNIT) }
        failure = collectRestoreFailure(failure) { gles.bindTexture(GLES20.GL_TEXTURE_2D, texture2dBinding) }
        failure = collectRestoreFailure(failure) { gles.bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalOesBinding) }
        failure = collectRestoreFailure(failure) { gles.disableVertexAttribArray(positionAttributeIndex) }
        failure = collectRestoreFailure(failure) { gles.disableVertexAttribArray(textureCoordinateAttributeIndex) }
        failure = collectRestoreFailure(failure) { gles.bindBuffer(GLES20.GL_ARRAY_BUFFER, arrayBufferBinding) }
        failure = collectRestoreFailure(failure) { gles.bindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferBinding) }
        failure = collectRestoreFailure(failure) { gles.useProgram(programBinding) }
        failure = collectRestoreFailure(failure) { gles.viewport(viewportX, viewportY, viewportWidth, viewportHeight) }
        failure = collectRestoreFailure(failure) { gles.pixelStorei(GLES20.GL_PACK_ALIGNMENT, packAlignment) }
        failure = collectRestoreFailure(failure) { gles.activeTexture(activeTexture) }
        failure = collectRestoreFailure(failure) { gl.checkGl("restore runtime ES2 GL state") }
        return failure
    }

    fun save(
        gl: GlLaneScope,
        gles: RuntimeGles20Api,
        binding: FirstPlanEs2ProgramBinding,
        scratch: RuntimeEs2SavedGlStateScratch,
    ) {
        val activeTexture = scratch.activeTexture
        val texture2dBinding = scratch.texture2dBinding
        val externalOesBinding = scratch.externalOesBinding
        val arrayBufferBinding = scratch.arrayBufferBinding
        val framebufferBinding = scratch.framebufferBinding
        val programBinding = scratch.programBinding
        val viewport = scratch.viewport
        val packAlignment = scratch.packAlignment
        gles.getIntegerv(GLES20.GL_ACTIVE_TEXTURE, activeTexture, 0)
        try {
            gles.activeTexture(RUNTIME_ES2_TEXTURE_UNIT)
            gles.getIntegerv(GLES20.GL_TEXTURE_BINDING_2D, texture2dBinding, 0)
            gles.getIntegerv(GLES11Ext.GL_TEXTURE_BINDING_EXTERNAL_OES, externalOesBinding, 0)
            gles.getIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, arrayBufferBinding, 0)
            gles.getIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebufferBinding, 0)
            gles.getIntegerv(GLES20.GL_CURRENT_PROGRAM, programBinding, 0)
            gles.getIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            gles.getIntegerv(GLES20.GL_PACK_ALIGNMENT, packAlignment, 0)
            checkVertexAttributeDisabled(
                gles = gles,
                index = binding.positionAttributeLocation,
                enabled = scratch.vertexAttributeEnabled,
            )
            checkVertexAttributeDisabled(
                gles = gles,
                index = binding.textureCoordinateAttributeLocation,
                enabled = scratch.vertexAttributeEnabled,
            )
            gl.checkGl("save runtime ES2 GL state")
        } catch (cause: Throwable) {
            runCatching { gles.activeTexture(activeTexture[0]) }.onFailure(cause::addSuppressed)
            throw cause
        }
        this.activeTexture = activeTexture[0]
        this.texture2dBinding = texture2dBinding[0]
        this.externalOesBinding = externalOesBinding[0]
        this.arrayBufferBinding = arrayBufferBinding[0]
        this.framebufferBinding = framebufferBinding[0]
        this.programBinding = programBinding[0]
        this.viewportX = viewport[0]
        this.viewportY = viewport[1]
        this.viewportWidth = viewport[2]
        this.viewportHeight = viewport[3]
        this.packAlignment = packAlignment[0]
        this.positionAttributeIndex = binding.positionAttributeLocation
        this.textureCoordinateAttributeIndex = binding.textureCoordinateAttributeLocation
    }
}

private class RuntimeEs2SavedGlStateScratch {
    val activeTexture = IntArray(1)
    val texture2dBinding = IntArray(1)
    val externalOesBinding = IntArray(1)
    val arrayBufferBinding = IntArray(1)
    val framebufferBinding = IntArray(1)
    val programBinding = IntArray(1)
    val viewport = IntArray(VIEWPORT_VALUE_COUNT)
    val packAlignment = IntArray(1)
    val vertexAttributeEnabled = IntArray(1)
}

private fun checkVertexAttributeDisabled(gles: RuntimeGles20Api, index: Int, enabled: IntArray) {
    gles.getVertexAttribiv(index, GLES20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, enabled, 0)
    // Android GLES20 has no Java binding for glGetVertexAttribPointerv, so these
    // renderer-owned locations are required to be disabled on entry.
    check(enabled[0] == 0) {
        "Runtime ES2 renderer-owned vertex attribute $index must be disabled before render/readback."
    }
}

private inline fun collectRestoreFailure(existingFailure: Throwable?, block: () -> Unit): Throwable? =
    try {
        block()
        existingFailure
    } catch (cause: Throwable) {
        if (existingFailure == null) {
            cause
        } else {
            existingFailure.addSuppressed(cause)
            existingFailure
        }
    }

private fun composeRuntimeOesMatrix(
    runtimeOesMatrix: FloatArray,
    staticPlanMatrix: FloatArray,
    rule: FirstPlanOesCompositionRule,
    destination: FloatArray,
) {
    require(destination.size == MATRIX_4_VALUE_COUNT) { "destination matrix must contain 16 values." }
    when (rule) {
        FirstPlanOesCompositionRule.DynamicOesMatrixAfterStaticPlanTransform ->
            multiplyColumnMajor4x4(left = runtimeOesMatrix, right = staticPlanMatrix, destination = destination)
    }
}

private fun multiplyColumnMajor4x4(left: FloatArray, right: FloatArray, destination: FloatArray) {
    require(left.size == MATRIX_4_VALUE_COUNT) { "left matrix must contain 16 values." }
    require(right.size == MATRIX_4_VALUE_COUNT) { "right matrix must contain 16 values." }
    require(destination.size == MATRIX_4_VALUE_COUNT) { "destination matrix must contain 16 values." }
    for (column in 0 until MATRIX_4_COLUMN_COUNT) {
        for (row in 0 until MATRIX_4_ROW_COUNT) {
            var value = 0.0f
            for (k in 0 until MATRIX_4_COLUMN_COUNT) {
                value += left[k * MATRIX_4_ROW_COUNT + row] * right[column * MATRIX_4_ROW_COUNT + k]
            }
            destination[column * MATRIX_4_ROW_COUNT + row] = value
        }
    }
}

private fun floatBufferOf(values: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * BYTES_PER_FLOAT)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }

private const val RUNTIME_ES2_TEXTURE_UNIT_INDEX: Int = 0
private const val RUNTIME_ES2_TEXTURE_UNIT: Int = GLES20.GL_TEXTURE0 + RUNTIME_ES2_TEXTURE_UNIT_INDEX
private const val FLOATS_PER_POSITION_VERTEX: Int = 2
private const val FLOATS_PER_TEXTURE_VERTEX: Int = 2
private const val TRIANGLE_STRIP_VERTEX_COUNT: Int = 4
private const val MATRIX_4_VALUE_COUNT: Int = 16
private const val MATRIX_4_COLUMN_COUNT: Int = 4
private const val MATRIX_4_ROW_COUNT: Int = 4
private const val VIEWPORT_VALUE_COUNT: Int = 4
private const val BYTES_PER_FLOAT: Int = 4

private val POSITION_VERTICES: FloatBuffer = floatBufferOf(
    floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        1.0f, 1.0f,
    ),
)

private val TEXTURE_COORDINATES: FloatBuffer = floatBufferOf(
    floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f,
    ),
)
