package dev.dmkr.screencaptureengine.internal.runtime

import dev.dmkr.screencaptureengine.ColorMode
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Move-only ES2 rendering/readback resource bag with thread-agnostic logical close.
 *
 * The bag owns the validated ES2 output objects, the selected external-OES program metadata, and the
 * direct RGBA readback buffer until ownership moves into the runtime resource owner or [close] wins.
 *
 * GL object deletion is admitted through [GlResourceRetirementLane]. If the lane no longer accepts
 * retirement work, the GL objects are abandoned with the lane and close still returns promptly.
 */
internal class PreparedEs2RenderingReadbackResources internal constructor(
    private val retirementLane: GlResourceRetirementLane,
    internal val glObjects: PreparedEs2RenderingReadbackGlObjects,
    internal val width: Int,
    internal val height: Int,
    internal val rowStrideBytes: Int,
    internal val readbackBuffer: ByteBuffer,
    private val gles: Gles20Api = AndroidGles20Api,
) : PreparedRenderingReadbackResources {
    private val closed = AtomicBoolean()

    internal val programBinding: Es2RenderingProgramBindingMetadata = glObjects.programBinding
    internal val readbackBufferCapacityBytes: Int = readbackBuffer.capacity()
    internal val readbackByteCount: Int = Math.multiplyExact(rowStrideBytes, height)

    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(rowStrideBytes >= Math.multiplyExact(width, RGBA_8888_BYTES_PER_PIXEL)) {
            "rowStrideBytes must fit width and RGBA8888 input."
        }
        require(readbackBuffer.isDirect) { "readbackBuffer must be direct." }
        require(readbackBuffer.position() == 0) { "readbackBuffer position must start at 0." }
        require(readbackBuffer.capacity() == readbackByteCount) {
            "readbackBuffer capacity must equal rowStrideBytes * height."
        }
        require(readbackBuffer.limit() >= readbackByteCount) {
            "readbackBuffer limit is smaller than rowStrideBytes * height."
        }
    }

    internal fun prepareReadbackBuffer(): ByteBuffer {
        check(!closed.get()) { "Prepared ES2 rendering/readback resources are closed." }
        readbackBuffer.clear()
        readbackBuffer.limit(readbackByteCount)
        return readbackBuffer
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val outputTextureId = glObjects.outputTextureId
        val outputFramebufferId = glObjects.outputFramebufferId
        val outputRenderbufferId = glObjects.outputRenderbufferId
        val programId = glObjects.programId
        val vertexShaderId = glObjects.vertexShaderId
        val fragmentShaderId = glObjects.fragmentShaderId
        val glesApi = gles
        retirementLane.retireGlResources("ES2 prepared rendering readback resources") {
            val cleanupFailures = CleanupFailureCollector()
            cleanupFailures.collectDelete(programId, glesApi::deleteProgram)
            cleanupFailures.collectDelete(vertexShaderId, glesApi::deleteShader)
            cleanupFailures.collectDelete(fragmentShaderId, glesApi::deleteShader)
            cleanupFailures.collectDelete(outputFramebufferId, glesApi::deleteFramebuffer)
            cleanupFailures.collectDelete(outputRenderbufferId, glesApi::deleteRenderbuffer)
            cleanupFailures.collectDelete(outputTextureId, glesApi::deleteTexture)
            cleanupFailures.collect { checkGl("retire ES2 prepared rendering readback resources") }
            cleanupFailures.throwIfAny()
        }
    }
}

internal class PreparedEs2RenderingReadbackGlObjects internal constructor(
    internal val outputTextureId: Int,
    internal val outputFramebufferId: Int,
    internal val outputRenderbufferId: Int,
    internal val programId: Int,
    internal val vertexShaderId: Int,
    internal val fragmentShaderId: Int,
    internal val programBinding: Es2RenderingProgramBindingMetadata,
) {
    init {
        require(outputTextureId > 0) { "outputTextureId must be nonzero." }
        require(outputFramebufferId > 0) { "outputFramebufferId must be nonzero." }
        require(outputRenderbufferId >= 0) { "outputRenderbufferId must be non-negative." }
        require(programId > 0) { "programId must be nonzero." }
        require(vertexShaderId > 0) { "vertexShaderId must be nonzero." }
        require(fragmentShaderId > 0) { "fragmentShaderId must be nonzero." }
        require(programBinding.programId == programId) { "programBinding must describe programId." }
    }
}

/**
 * Explicit ES2 program contract carried from readiness validation into the first-plan transform.
 *
 * The shader variant must match the selected color mode, and the recorded locations are the only
 * program locations the first-plan package may rely on before runtime rendering begins.
 */
internal class Es2RenderingProgramBindingMetadata internal constructor(
    internal val programId: Int,
    internal val shaderVariant: Es2RenderingShaderVariant,
    internal val attributeLocations: Es2RenderingProgramAttributeLocations,
    internal val uniformLocations: Es2RenderingProgramUniformLocations,
    internal val dynamicOesMatrixUniformSlot: Es2DynamicOesMatrixUniformSlot,
) {
    init {
        require(programId > 0) { "programId must be nonzero." }
        require(dynamicOesMatrixUniformSlot.location == uniformLocations.textureMatrix) {
            "dynamicOesMatrixUniformSlot must use the texture matrix uniform location."
        }
    }
}

internal class Es2RenderingProgramAttributeLocations internal constructor(
    internal val position: Int,
    internal val textureCoordinate: Int,
) {
    init {
        require(position >= 0) { "position attribute location must be non-negative." }
        require(textureCoordinate >= 0) { "textureCoordinate attribute location must be non-negative." }
    }
}

internal class Es2RenderingProgramUniformLocations internal constructor(
    internal val externalOesTextureSampler: Int,
    internal val textureMatrix: Int,
) {
    init {
        require(externalOesTextureSampler >= 0) { "externalOesTextureSampler uniform location must be non-negative." }
        require(textureMatrix >= 0) { "textureMatrix uniform location must be non-negative." }
    }
}

internal class Es2DynamicOesMatrixUniformSlot internal constructor(
    internal val uniformName: String,
    internal val location: Int,
    internal val matrixElementCount: Int,
    internal val compositionRule: Es2OesMatrixCompositionRule,
) {
    init {
        require(uniformName.isNotBlank()) { "uniformName must not be blank." }
        require(location >= 0) { "location must be non-negative." }
        require(matrixElementCount == 16) { "ES2 OES matrix slot must be a 4x4 matrix." }
    }
}

internal enum class Es2RenderingShaderVariant(internal val supportedColorMode: ColorMode) {
    OriginalExternalOes(ColorMode.Original),
    GrayscaleExternalOes(ColorMode.Grayscale),
}

internal enum class Es2OesMatrixCompositionRule {
    RuntimeOesMatrixComposedWithStaticPlanTransform,
}

private const val RGBA_8888_BYTES_PER_PIXEL: Int = 4

private fun CleanupFailureCollector.collectDelete(handle: Int, delete: (Int) -> Unit) {
    if (handle == 0) return
    collect { delete(handle) }
}
