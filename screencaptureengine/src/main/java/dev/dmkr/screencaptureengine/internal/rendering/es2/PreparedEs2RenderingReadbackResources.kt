package dev.dmkr.screencaptureengine.internal.rendering.es2

import dev.dmkr.screencaptureengine.ColorMode
import dev.dmkr.screencaptureengine.ImageEncoderInputFormat
import dev.dmkr.screencaptureengine.internal.gl.AndroidGles20Api
import dev.dmkr.screencaptureengine.internal.gl.CleanupFailureCollector
import dev.dmkr.screencaptureengine.internal.gl.GlLaneScope
import dev.dmkr.screencaptureengine.internal.gl.GlResourceRetirementLane
import dev.dmkr.screencaptureengine.internal.gl.Gles20Api
import dev.dmkr.screencaptureengine.internal.rendering.pipeline.PreparedRenderingReadbackResources
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Move-only ES2 rendering/readback resource bag with thread-agnostic logical close.
 *
 * The bag owns the validated ES2 output objects, the selected external-OES program metadata, and the
 * direct RGBA readback buffer until ownership moves into the runtime resource owner or [close] wins.
 * Raw RGBA storage is intentionally reachable only through [RgbaReadbackLease]; [readbackBuffer]
 * exposes metadata only and must not be treated as a retainable alias to captured pixels.
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
    readbackBuffer: ByteBuffer,
    private val gles: Gles20Api = AndroidGles20Api,
) : PreparedRenderingReadbackResources {
    private val mutableReadbackBuffer = readbackBuffer
    private val readbackBufferMetadataView = ByteBuffer.allocateDirect(0).asReadOnlyBuffer()
    private val closeAndLeaseLock = Any()
    private var closed = false
    private var readbackLeaseInUse = false

    internal val programBinding: Es2RenderingProgramBindingMetadata = glObjects.programBinding

    /**
     * Read-only metadata sentinel for code paths that need to observe resource shape without
     * borrowing raw RGBA pixels. Use [tryAcquireRgbaReadbackLease] for actual readback or encoding.
     */
    internal val readbackBuffer: ByteBuffer
        get() =
            synchronized(closeAndLeaseLock) {
                checkOpen()
                readbackBufferMetadataView
            }
    internal val readbackBufferCapacityBytes: Int = mutableReadbackBuffer.capacity()
    internal val readbackByteCount: Int = Math.multiplyExact(rowStrideBytes, height)

    init {
        require(width > 0) { "width must be positive, was $width" }
        require(height > 0) { "height must be positive, was $height" }
        require(rowStrideBytes >= Math.multiplyExact(width, RGBA_8888_BYTES_PER_PIXEL)) {
            "rowStrideBytes must fit width and RGBA8888 input."
        }
        require(mutableReadbackBuffer.isDirect) { "readbackBuffer must be direct." }
        require(mutableReadbackBuffer.position() == 0) { "readbackBuffer position must start at 0." }
        require(mutableReadbackBuffer.capacity() == readbackByteCount) {
            "readbackBuffer capacity must equal rowStrideBytes * height."
        }
        require(mutableReadbackBuffer.limit() >= readbackByteCount) {
            "readbackBuffer limit is smaller than rowStrideBytes * height."
        }
    }

    /**
     * Acquires the sole raw RGBA readback lease, or returns null while GL/readback or encoding owns it.
     */
    internal fun tryAcquireRgbaReadbackLease(): RgbaReadbackLease? {
        synchronized(closeAndLeaseLock) {
            checkOpen()
            if (readbackLeaseInUse) return null
            readbackLeaseInUse = true
            mutableReadbackBuffer.clear()
            mutableReadbackBuffer.limit(readbackByteCount)
            return RgbaReadbackLease(
                owner = this,
                width = width,
                height = height,
                rowStrideBytes = rowStrideBytes,
                byteCount = readbackByteCount,
                buffer = mutableReadbackBuffer,
            )
        }
    }

    private fun releaseReadbackLease() {
        synchronized(closeAndLeaseLock) {
            check(readbackLeaseInUse) { "ES2 readback lease was not active." }
            readbackLeaseInUse = false
        }
    }

    override fun close() {
        synchronized(closeAndLeaseLock) {
            if (closed) return
            closed = true
        }
        val outputTextureId = glObjects.outputTextureId
        val outputFramebufferId = glObjects.outputFramebufferId
        val outputRenderbufferId = glObjects.outputRenderbufferId
        val programId = glObjects.programId
        val vertexShaderId = glObjects.vertexShaderId
        val fragmentShaderId = glObjects.fragmentShaderId
        val glesApi = gles
        retirementLane.retireGlResources(
            label = "ES2 prepared rendering readback resources",
            block = PreparedEs2RenderingReadbackResourceRetirement(
                outputTextureId = outputTextureId,
                outputFramebufferId = outputFramebufferId,
                outputRenderbufferId = outputRenderbufferId,
                programId = programId,
                vertexShaderId = vertexShaderId,
                fragmentShaderId = fragmentShaderId,
                gles = glesApi,
            ),
        )
    }

    private fun checkOpen() {
        check(!closed) { "Prepared ES2 rendering/readback resources are closed." }
    }

    internal class RgbaReadbackLease internal constructor(
        private val owner: PreparedEs2RenderingReadbackResources,
        internal val width: Int,
        internal val height: Int,
        internal val rowStrideBytes: Int,
        internal val byteCount: Int,
        private val buffer: ByteBuffer,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()
        internal val inputFormat: ImageEncoderInputFormat = ImageEncoderInputFormat.Rgba8888SrgbOpaque

        init {
            require(width > 0) { "width must be positive, was $width" }
            require(height > 0) { "height must be positive, was $height" }
            require(rowStrideBytes >= Math.multiplyExact(width, RGBA_8888_BYTES_PER_PIXEL)) {
                "rowStrideBytes must fit width and RGBA8888 input."
            }
            require(byteCount == Math.multiplyExact(rowStrideBytes, height)) {
                "byteCount must equal rowStrideBytes * height."
            }
            require(buffer.position() == 0) { "readback lease buffer position must start at 0." }
            require(buffer.limit() == byteCount) { "readback lease buffer limit must equal byteCount." }
        }

        internal fun mutableReadbackBuffer(): ByteBuffer {
            checkOpen()
            return buffer
        }

        internal fun readOnlyBufferView(): ByteBuffer {
            checkOpen()
            return buffer.asReadOnlyBuffer().apply {
                position(0)
                limit(byteCount)
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            owner.releaseReadbackLease()
        }

        private fun checkOpen() {
            check(!closed.get()) { "RGBA readback lease is closed." }
        }
    }
}

internal class PreparedEs2RenderingReadbackResourceRetirement internal constructor(
    private val outputTextureId: Int,
    private val outputFramebufferId: Int,
    private val outputRenderbufferId: Int,
    private val programId: Int,
    private val vertexShaderId: Int,
    private val fragmentShaderId: Int,
    private val gles: Gles20Api,
) : (GlLaneScope) -> Unit {
    override fun invoke(scope: GlLaneScope) {
        val cleanupFailures = CleanupFailureCollector()
        cleanupFailures.collectDelete(programId, gles::deleteProgram)
        cleanupFailures.collectDelete(vertexShaderId, gles::deleteShader)
        cleanupFailures.collectDelete(fragmentShaderId, gles::deleteShader)
        cleanupFailures.collectDelete(outputFramebufferId, gles::deleteFramebuffer)
        cleanupFailures.collectDelete(outputRenderbufferId, gles::deleteRenderbuffer)
        cleanupFailures.collectDelete(outputTextureId, gles::deleteTexture)
        cleanupFailures.collect { scope.checkGl("retire ES2 prepared rendering readback resources") }
        cleanupFailures.throwIfAny()
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

internal typealias RgbaReadbackLease = PreparedEs2RenderingReadbackResources.RgbaReadbackLease

private fun CleanupFailureCollector.collectDelete(handle: Int, delete: (Int) -> Unit) {
    if (handle == 0) return
    collect { delete(handle) }
}
